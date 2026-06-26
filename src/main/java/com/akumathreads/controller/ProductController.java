package com.akumathreads.controller;

import com.akumathreads.dto.ProductCardDto;
import com.akumathreads.model.Product;
import com.akumathreads.repository.OrderRepository;
import com.akumathreads.service.ProductService;
import lombok.RequiredArgsConstructor;
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

    private final ProductService  productService;
    private final OrderRepository orderRepository;

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

        return "product-detail";
    }
}
