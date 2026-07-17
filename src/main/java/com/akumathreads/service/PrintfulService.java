package com.akumathreads.service;

import com.akumathreads.dto.CheckoutForm;
import com.akumathreads.model.Order;
import com.akumathreads.model.OrderItem;
import com.akumathreads.model.SessionCart;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;

/**
 * Printful fulfillment API integration.
 *
 * <p>Provides:
 * <ul>
 *   <li>Cheapest shipping rate lookup with flat-rate fallback</li>
 *   <li>Tax calculation for US orders (international = $0 via Printful)</li>
 *   <li>Order submission to Printful for print-and-ship fulfillment</li>
 *   <li>Sync product listing and detail for the admin import center</li>
 * </ul>
 *
 * <p>Every public method catches all API exceptions and returns a safe default
 * so that checkout never fails solely due to a Printful outage.
 *
 * <p>API key is read from the {@code PRINTFUL_API_KEY} environment variable via
 * {@code application.properties} — never hard-coded or committed to version control.
 */
@Service
@Slf4j
public class PrintfulService {

    private static final String BASE_URL            = "https://api.printful.com";
    private static final BigDecimal FALLBACK_US     = new BigDecimal("4.99");
    private static final BigDecimal FALLBACK_INTL   = new BigDecimal("14.99");

    private final RestClient    restClient;
    private final ObjectMapper  objectMapper;

    public PrintfulService(
            @Value("${printful.api.key:not-configured}") String apiKey,
            @Value("${printful.store-id:}") String storeId,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json");
        // Pin requests to the correct store when multiple stores exist on the account
        if (storeId != null && !storeId.isBlank()) {
            builder.defaultHeader("X-PF-Store-Id", storeId);
        }
        this.restClient = builder.build();
    }

    // ── Shipping ─────────────────────────────────────────────────────────────

    /**
     * Returns the cheapest Printful shipping rate for the given address and cart.
     *
     * <p>Items that have a {@code printfulSyncVariantId} in the provided map are sent
     * with their Printful ID; the rest are sent value-only. If Printful rejects the
     * request or returns no rates, a flat fallback is returned:
     * $4.99 domestic (US), $14.99 international.
     *
     * @param form               validated checkout form (contains address + country)
     * @param cartItems          cart entries (quantity + unit price)
     * @param printfulVariantIds map of our variantId → Printful syncVariantId
     * @return cheapest shipping rate, never null
     */
    public BigDecimal getCheapestShippingRate(CheckoutForm form,
                                              Collection<SessionCart.CartEntry> cartItems,
                                              Map<Long, Long> printfulVariantIds) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.set("recipient", buildRecipient(form));
            body.set("items",     buildShippingItems(cartItems, printfulVariantIds));

            String json = restClient.post()
                    .uri("/shipping/rates")
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);

            JsonNode result = objectMapper.readTree(json).path("result");
            if (!result.isArray() || result.isEmpty()) {
                return fallbackRate(form.getCountry());
            }

            BigDecimal cheapest = null;
            for (JsonNode rate : result) {
                BigDecimal r = new BigDecimal(rate.path("rate").asText("0"));
                if (cheapest == null || r.compareTo(cheapest) < 0) cheapest = r;
            }
            return cheapest != null
                    ? cheapest.setScale(2, RoundingMode.HALF_UP)
                    : fallbackRate(form.getCountry());

        } catch (Exception e) {
            log.warn("[Printful] Shipping rate lookup failed: {}", e.getMessage());
            return fallbackRate(form.getCountry());
        }
    }

    // ── Tax ──────────────────────────────────────────────────────────────────

    /**
     * Returns the tax amount for the given subtotal and US shipping address.
     * International orders return {@link BigDecimal#ZERO} — Printful only
     * calculates nexus-based tax for US destinations.
     */
    public BigDecimal calculateTax(CheckoutForm form, BigDecimal subtotal) {
        if (!"US".equalsIgnoreCase(form.getCountry())) {
            return BigDecimal.ZERO;
        }
        try {
            ObjectNode recipient = objectMapper.createObjectNode();
            recipient.put("country_code", "US");
            if (form.getState() != null && !form.getState().isBlank()) {
                recipient.put("state_code", form.getState().toUpperCase());
            }
            recipient.put("city", form.getCity());
            recipient.put("zip",  form.getZip());

            ObjectNode body = objectMapper.createObjectNode();
            body.set("recipient", recipient);

            String json = restClient.post()
                    .uri("/tax/rates")
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);

            JsonNode result = objectMapper.readTree(json).path("result");
            if (!result.path("required").asBoolean(false)) {
                return BigDecimal.ZERO;
            }
            double rate = result.path("rate").asDouble(0);
            return subtotal.multiply(BigDecimal.valueOf(rate))
                           .setScale(2, RoundingMode.HALF_UP);

        } catch (Exception e) {
            log.warn("[Printful] Tax calculation failed: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    // ── Order fulfillment ────────────────────────────────────────────────────

    /**
     * Submits a saved order to Printful for print-and-ship fulfillment.
     *
     * <p>The order is submitted with {@code confirm=true} so Printful starts
     * production immediately. If any variant has no Printful sync ID, that line
     * item is sent with the product name as a manual fulfillment note.
     *
     * @return the Printful order ID (for tracking), or {@code null} if the push fails
     */
    public String submitOrder(Order order, String customerEmail) {
        try {
            ObjectNode body = buildOrderBody(order, customerEmail);

            String json = restClient.post()
                    .uri("/orders?confirm=true")
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);

            JsonNode result = objectMapper.readTree(json).path("result");
            if (result.has("id")) {
                String printfulId = String.valueOf(result.path("id").asLong());
                log.info("[Printful] Order {} submitted → Printful ID {}", order.getId(), printfulId);
                return printfulId;
            }
            return null;

        } catch (Exception e) {
            log.error("[Printful] Order {} submission failed: {}", order.getId(), e.getMessage());
            return null;
        }
    }

    // ── Admin: sync product listing ──────────────────────────────────────────

    /** Returns a human-readable API status for the admin UI — useful for debugging. */
    public String getApiStatus() {
        try {
            String json = restClient.get()
                    .uri("/store/products?limit=1")
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(json);
            int code = root.path("code").asInt(200);
            if (code == 200) return "✓ Connected to Printful";
            return "✗ API error: " + root.path("result").asText();
        } catch (Exception e) {
            return "✗ API error: " + e.getMessage();
        }
    }

    /**
     * Lists all sync products in the connected Printful store.
     * Returns an empty list if the API is unavailable or the store has no products.
     */
    public List<SyncProduct> getSyncProducts() {
        try {
            String json = restClient.get()
                    .uri("/store/products?limit=100")
                    .retrieve()
                    .body(String.class);

            JsonNode result = objectMapper.readTree(json).path("result");
            if (!result.isArray()) return List.of();

            List<SyncProduct> products = new ArrayList<>();
            for (JsonNode node : result) {
                products.add(new SyncProduct(
                        node.path("id").asLong(),
                        node.path("name").asText(),
                        node.path("variants").asInt(),
                        node.path("synced").asInt(),
                        node.path("thumbnail_url").asText(null)
                ));
            }
            return products;

        } catch (Exception e) {
            log.warn("[Printful] getSyncProducts failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Fetches full detail for one Printful sync product, including all variants.
     * Returns {@code null} if the product is not found or the API is unavailable.
     */
    public SyncProductDetail getSyncProduct(Long printfulProductId) {
        try {
            String json = restClient.get()
                    .uri("/store/products/{id}", printfulProductId)
                    .retrieve()
                    .body(String.class);

            JsonNode result      = objectMapper.readTree(json).path("result");
            JsonNode productNode = result.path("sync_product");
            JsonNode variantsNode = result.path("sync_variants");

            // Collect unique mockup preview URLs across all variants (insertion-ordered, deduped)
            SequencedSet<String> imageUrls = new LinkedHashSet<>();
            String thumbnailUrl = productNode.path("thumbnail_url").asText(null);
            if (thumbnailUrl != null && !thumbnailUrl.isBlank()) {
                imageUrls.add(thumbnailUrl);
            }

            List<SyncVariant> variants = new ArrayList<>();
            if (variantsNode.isArray()) {
                for (JsonNode v : variantsNode) {
                    // Printful variant names are typically "Product Name / SIZE"
                    String fullName = v.path("name").asText("");
                    String sizePart = fullName.contains("/")
                            ? fullName.substring(fullName.lastIndexOf('/') + 1).trim()
                            : fullName.trim();
                    variants.add(new SyncVariant(
                            v.path("id").asLong(),
                            fullName,
                            sizePart,
                            v.path("synced").asBoolean(false),
                            v.path("retail_price").asText("0.00")
                    ));

                    // Each variant's files array contains mockup/preview images
                    JsonNode files = v.path("files");
                    if (files.isArray()) {
                        for (JsonNode file : files) {
                            String previewUrl = file.path("preview_url").asText(null);
                            if (previewUrl != null && !previewUrl.isBlank()) {
                                imageUrls.add(previewUrl);
                            }
                        }
                    }
                }
            }

            return new SyncProductDetail(
                    productNode.path("id").asLong(),
                    productNode.path("name").asText(),
                    thumbnailUrl,
                    List.copyOf(imageUrls),
                    variants
            );

        } catch (Exception e) {
            log.warn("[Printful] getSyncProduct({}) failed: {}", printfulProductId, e.getMessage());
            return null;
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private ObjectNode buildRecipient(CheckoutForm form) {
        ObjectNode r = objectMapper.createObjectNode();
        r.put("address1",     form.getAddress());
        if (form.getAddress2() != null && !form.getAddress2().isBlank()) {
            r.put("address2", form.getAddress2());
        }
        r.put("city",         form.getCity());
        if (form.getState() != null && !form.getState().isBlank()) {
            r.put("state_code", form.getState().toUpperCase());
        }
        r.put("zip",          form.getZip());
        r.put("country_code", form.getCountry().toUpperCase());
        return r;
    }

    private ArrayNode buildShippingItems(Collection<SessionCart.CartEntry> cartItems,
                                          Map<Long, Long> printfulVariantIds) {
        ArrayNode items = objectMapper.createArrayNode();
        for (SessionCart.CartEntry entry : cartItems) {
            ObjectNode item = objectMapper.createObjectNode();
            Long printfulId = printfulVariantIds.get(entry.variantId());
            if (printfulId != null) {
                item.put("sync_variant_id", printfulId);
            }
            item.put("quantity", entry.quantity());
            item.put("value",    entry.unitPrice().toPlainString());
            items.add(item);
        }
        return items;
    }

    private ObjectNode buildOrderBody(Order order, String customerEmail) {
        ObjectNode body = objectMapper.createObjectNode();

        // Recipient
        ObjectNode recipient = objectMapper.createObjectNode();
        recipient.put("name",         order.getShipName());
        recipient.put("email",        customerEmail);
        recipient.put("address1",     order.getShipAddress());
        recipient.put("city",         order.getShipCity());
        if (order.getShipState() != null && !order.getShipState().isBlank()) {
            recipient.put("state_code", order.getShipState().toUpperCase());
        }
        recipient.put("zip",          order.getShipZip());
        recipient.put("country_code", order.getShipCountry() != null
                                       ? order.getShipCountry().toUpperCase() : "US");
        body.set("recipient", recipient);

        // Line items
        ArrayNode itemsArr = objectMapper.createArrayNode();
        for (OrderItem oi : order.getItems()) {
            ObjectNode item = objectMapper.createObjectNode();
            Long syncId = oi.getVariant().getPrintfulSyncVariantId();
            if (syncId != null) {
                item.put("sync_variant_id", syncId);
            } else {
                // No Printful link yet — send as a manual line for the store owner to handle
                item.put("name", oi.getVariant().getProduct().getName()
                                  + " / " + oi.getVariant().getSize().name());
            }
            item.put("quantity",      oi.getQuantity());
            item.put("retail_price",  oi.getUnitPrice().toPlainString());
            itemsArr.add(item);
        }
        body.set("items", itemsArr);

        // Retail costs snapshot
        BigDecimal subtotal = order.getTotal()
                .subtract(order.getShippingCost())
                .subtract(order.getTaxAmount());
        ObjectNode costs = objectMapper.createObjectNode();
        costs.put("currency", "USD");
        costs.put("subtotal", subtotal.toPlainString());
        costs.put("shipping", order.getShippingCost().toPlainString());
        costs.put("tax",      order.getTaxAmount().toPlainString());
        costs.put("total",    order.getTotal().toPlainString());
        body.set("retail_costs", costs);

        return body;
    }

    private BigDecimal fallbackRate(String country) {
        if (country == null) return FALLBACK_INTL;
        return "US".equalsIgnoreCase(country) ? FALLBACK_US : FALLBACK_INTL;
    }

    // ── Public record types (used by admin controller + Thymeleaf templates) ─

    /** Summary of a Printful sync product (list view). */
    public record SyncProduct(
            Long   id,
            String name,
            int    variants,
            int    synced,
            String thumbnailUrl
    ) {}

    /** One variant within a Printful sync product (detail view). */
    public record SyncVariant(
            Long    id,
            String  name,
            String  sizePart,
            boolean synced,
            String  retailPrice
    ) {}

    /**
     * Full detail for one Printful sync product including all variants and images.
     *
     * <p>{@code imageUrls} is an ordered, deduplicated list of preview/mockup URLs
     * collected from the sync product thumbnail and every variant's {@code files} array.
     * The first entry is used as the hero image; the rest populate the gallery strip.
     */
    public record SyncProductDetail(
            Long              id,
            String            name,
            String            thumbnailUrl,
            List<String>      imageUrls,
            List<SyncVariant> syncVariants
    ) {}
}
