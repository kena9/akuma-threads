package com.akumathreads.controller;

import com.akumathreads.model.Product;
import com.akumathreads.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

/**
 * Handles the public product detail page at {@code /product/{id}}.
 *
 * <p>Loads the product with all variants eagerly via a JOIN FETCH query —
 * no N+1, no lazy-loading surprises on the template. Returns 404 for
 * soft-deleted or inactive products even if accessed by direct URL.
 */
@Controller
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping("/product/{id}")
    public String productDetail(@PathVariable Long id, Model model) {
        Product product = productService.findByIdWithVariants(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Product not found"));

        // Soft-deleted products are excluded by @SQLRestriction, so findByIdWithVariants
        // already returns empty. Active check catches admin-hidden (active=false) products.
        if (!product.isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not available");
        }

        model.addAttribute("product", product);
        return "product-detail";
    }
}
