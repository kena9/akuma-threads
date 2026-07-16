package com.akumathreads.controller;

import com.akumathreads.dto.ProductCardDto;
import com.akumathreads.model.Product;
import com.akumathreads.repository.OrderRepository;
import com.akumathreads.service.ProductService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles the public product detail page at {@code /product/{id}}.
 *
 * <p>Loads the product with all variants eagerly via a JOIN FETCH query.
 * Also computes drop-model metadata (drop countdown, edition position)
 * so the template can render scarcity signals without JS-only tricks.
 */
@Controller
@RequiredArgsConstructor
public class ProductController {

    private static final Logger log = LoggerFactory.getLogger(ProductController.class);

    private final ProductService  productService;
    private final OrderRepository orderRepository;
    private final ObjectMapper    objectMapper;

    @Value("${app.base-url:http://localhost:8080}")
    private String appBaseUrl;

    @GetMapping("/product/{id}")
    public String productDetail(@PathVariable Long id, Model model) {
        Product product = productService.findByIdWithVariants(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Product not found"));

        if (!product.isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not available");
        }

        model.addAttribute("product", product);

        // ── Drop model ────────────────────────────────────────────────────────
        LocalDateTime now        = LocalDateTime.now();
        boolean hasDropDate      = product.getDropDate() != null;
        boolean dropIsFuture     = hasDropDate && product.getDropDate().isAfter(now);
        boolean hasEditionSize   = product.getEditionSize() != null;

        // "NEW DROP" badge — true if the product was created within the last 14 days.
        // Computed here (not in the template) to avoid #temporals utility dependency.
        boolean isNewDrop = product.getCreatedDate() != null
                && product.getCreatedDate().isAfter(now.minusDays(14));
        model.addAttribute("isNewDrop", isNewDrop);

        // Convenience flag for JSON-LD availability — avoids totalStock call in inline JS
        boolean inStock = product.getTotalStock() > 0;
        model.addAttribute("inStock", inStock);

        model.addAttribute("hasDropDate",  hasDropDate);
        model.addAttribute("dropIsFuture", dropIsFuture);

        if (hasDropDate) {
            // Pass epoch millis so JS countdown can use it without locale issues
            model.addAttribute("dropDateMs",
                    product.getDropDate().atZone(java.time.ZoneId.systemDefault())
                           .toInstant().toEpochMilli());
        }

        if (hasEditionSize) {
            long soldCount = orderRepository.countUnitsSoldByProductId(id);
            model.addAttribute("soldCount",         soldCount);
            model.addAttribute("editionSize",       product.getEditionSize());
            // editionNumber = which edition the NEXT buyer gets (soldCount + 1)
            model.addAttribute("nextEditionNumber", soldCount + 1);
        }

        // ── OG meta tags ──────────────────────────────────────────────────────
        if (product.getImageUrl() != null) {
            model.addAttribute("ogImage", product.getImageUrl());
        }
        model.addAttribute("ogDescription",
                product.getName() + " — Original anime art on premium clothing by @oliver_jin_wang. " +
                "Printed on demand, no restocks." +
                (hasEditionSize ? " Limited to " + product.getEditionSize() + " pieces." : ""));

        // ── Related products ──────────────────────────────────────────────────
        List<ProductCardDto> related = productService
                .findFiltered(null, product.getCategory(), null, null,
                        PageRequest.of(0, 5, Sort.by("createdDate").descending()))
                .getContent()
                .stream()
                .filter(p -> !p.getId().equals(id))
                .limit(4)
                .collect(Collectors.toList());
        model.addAttribute("relatedProducts", related);

        // ── JSON-LD structured data ───────────────────────────────────────────
        // Build via Jackson so every string value is properly escaped —
        // no risk of Thymeleaf th:inline="javascript" mis-escaping descriptions
        // that contain quotes, newlines, or </script> sequences.
        String productUrl = appBaseUrl + "/product/" + id;
        model.addAttribute("productJsonLd",    buildProductJsonLd(product, productUrl, inStock));
        model.addAttribute("breadcrumbJsonLd", buildBreadcrumbJsonLd(product, productUrl));

        return "product-detail";
    }

    // ── JSON-LD helpers ───────────────────────────────────────────────────────

    private String buildProductJsonLd(Product product, String productUrl, boolean inStock) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("@context", "https://schema.org/");
            root.put("@type", "Product");
            root.put("name", product.getName());
            if (product.getImageUrl() != null) root.put("image", product.getImageUrl());
            if (product.getDescription() != null && !product.getDescription().isBlank()) {
                root.put("description", product.getDescription());
            }

            ObjectNode brand = root.putObject("brand");
            brand.put("@type", "Brand");
            brand.put("name", "Olly Threads");

            ObjectNode offer = root.putObject("offers");
            offer.put("@type", "Offer");
            offer.put("url", productUrl);
            offer.put("priceCurrency", "USD");
            if (product.getPrice() != null) {
                offer.put("price", product.getPrice().toPlainString());
            }
            offer.put("availability",
                    inStock ? "https://schema.org/InStock" : "https://schema.org/OutOfStock");

            ObjectNode seller = offer.putObject("seller");
            seller.put("@type", "Organization");
            seller.put("name", "Olly Threads");

            return toHtmlSafeJson(root);
        } catch (JsonProcessingException e) {
            log.warn("Failed to build product JSON-LD for id={}", product.getId(), e);
            return "{}";
        }
    }

    private String buildBreadcrumbJsonLd(Product product, String productUrl) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("@context", "https://schema.org/");
            root.put("@type", "BreadcrumbList");

            ArrayNode items = root.putArray("itemListElement");

            ObjectNode home = items.addObject();
            home.put("@type", "ListItem");
            home.put("position", 1);
            home.put("name", "Home");
            home.put("item", appBaseUrl);

            ObjectNode shop = items.addObject();
            shop.put("@type", "ListItem");
            shop.put("position", 2);
            shop.put("name", "Shop");
            shop.put("item", appBaseUrl + "/shop");

            ObjectNode detail = items.addObject();
            detail.put("@type", "ListItem");
            detail.put("position", 3);
            detail.put("name", product.getName());
            detail.put("item", productUrl);

            return toHtmlSafeJson(root);
        } catch (JsonProcessingException e) {
            log.warn("Failed to build breadcrumb JSON-LD for id={}", product.getId(), e);
            return "{}";
        }
    }

    /**
     * Serializes a Jackson node to JSON and escapes {@code </} as {@code <\/}.
     * This prevents a product description containing {@code </script>} from
     * prematurely closing the surrounding {@code <script type="application/ld+json">}
     * element. {@code <\/} is valid JSON (escaped forward slash) and is ignored
     * by JSON parsers.
     */
    private String toHtmlSafeJson(ObjectNode node) throws JsonProcessingException {
        return objectMapper.writeValueAsString(node).replace("</", "<\\/");
    }
}
