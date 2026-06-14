package com.akumathreads.controller;

import com.akumathreads.dto.AddToCartRequest;
import com.akumathreads.dto.CartSummaryResponse;
import com.akumathreads.model.ProductVariant;
import com.akumathreads.model.SessionCart;
import com.akumathreads.repository.ProductVariantRepository;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * REST endpoints for async cart operations called from {@code cart.js}.
 *
 * <p>The cart is stored in the HTTP session (key: {@value #CART_KEY}) as a
 * {@link SessionCart}. This avoids a database round-trip on every badge refresh
 * and keeps the cart available even for unauthenticated (guest) users.
 *
 * <p>All monetary values use {@link java.math.BigDecimal} with
 * {@link java.math.RoundingMode#HALF_UP}; {@code double}/{@code float} are never used.
 */
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartRestController {

    static final String CART_KEY = "cart";

    private final ProductVariantRepository variantRepository;

    // ── Read endpoints ───────────────────────────────────────────────────────

    /**
     * Returns the total unit count for the navbar badge.
     * Public — works for both authenticated and guest users.
     *
     * <pre>GET /api/cart/count → {"count": 3}</pre>
     */
    @GetMapping("/count")
    public Map<String, Integer> getCount(HttpSession session) {
        return Map.of("count", resolveCart(session).totalItemCount());
    }

    // ── Write endpoints ──────────────────────────────────────────────────────

    /**
     * Adds a variant to the session cart.
     *
     * <p>Validates that:
     * <ul>
     *   <li>The variant exists in the database.</li>
     *   <li>The requested quantity does not exceed current stock.</li>
     * </ul>
     *
     * <pre>POST /api/cart/add — body: AddToCartRequest</pre>
     *
     * @return updated {@link CartSummaryResponse} with item count, subtotal, and toast message
     */
    @PostMapping("/add")
    @Transactional(readOnly = false)
    public ResponseEntity<CartSummaryResponse> addToCart(
            @Valid @RequestBody AddToCartRequest request,
            HttpSession session) {

        ProductVariant variant = variantRepository.findById(request.variantId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Variant " + request.variantId() + " not found"));

        if (variant.getStockQty() < request.quantity()) {
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(new CartSummaryResponse(
                            0,
                            java.math.BigDecimal.ZERO,
                            "Only " + variant.getStockQty() + " in stock."));
        }

        SessionCart cart = resolveCart(session);
        cart.addOrUpdate(
                variant.getId(),
                variant.getProduct().getName(),
                variant.getSize().name(),
                variant.getProduct().getPrice(),
                request.quantity()
        );
        session.setAttribute(CART_KEY, cart);

        return ResponseEntity.ok(new CartSummaryResponse(
                cart.totalItemCount(),
                cart.subtotal(),
                "Added to cart!"));
    }

    /**
     * Removes a variant line from the session cart.
     *
     * <pre>DELETE /api/cart/remove/{variantId}</pre>
     *
     * @param variantId the variant to remove (used as the cart line key)
     * @return updated {@link CartSummaryResponse}
     */
    @DeleteMapping("/remove/{variantId}")
    @Transactional(readOnly = false)
    public ResponseEntity<CartSummaryResponse> removeFromCart(
            @PathVariable Long variantId,
            HttpSession session) {

        SessionCart cart = resolveCart(session);
        cart.remove(variantId);
        session.setAttribute(CART_KEY, cart);

        return ResponseEntity.ok(new CartSummaryResponse(
                cart.totalItemCount(),
                cart.subtotal(),
                "Item removed."));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Retrieves the existing {@link SessionCart} from the session, or creates and
     * stores an empty one if none exists. Uses {@code instanceof} pattern match
     * to handle the case where the session held a stale/incompatible object.
     */
    private SessionCart resolveCart(HttpSession session) {
        Object attr = session.getAttribute(CART_KEY);
        if (attr instanceof SessionCart existing) {
            return existing;
        }
        SessionCart fresh = new SessionCart();
        session.setAttribute(CART_KEY, fresh);
        return fresh;
    }
}
