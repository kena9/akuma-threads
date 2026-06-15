package com.akumathreads.controller;

import com.akumathreads.model.Product;
import com.akumathreads.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

/**
 * Handles the public-facing product catalog at {@code /shop}.
 *
 * <p>All parameters are optional and gracefully degrade:
 * <ul>
 *   <li>No keyword → no keyword filter</li>
 *   <li>No category → all categories shown</li>
 *   <li>No price range → no price bound</li>
 *   <li>No sort → newest products first</li>
 *   <li>No page → page 0 (first page, 12 items)</li>
 * </ul>
 *
 * <p>Page size is fixed at {@value #PAGE_SIZE} to keep the 4-column grid balanced.
 */
@Controller
@RequestMapping("/shop")
@RequiredArgsConstructor
public class ShopController {

    private final ProductService productService;

    /** Products per page — fills a 4-column grid exactly (3 rows). */
    private static final int PAGE_SIZE = 12;

    /**
     * Main shop listing with dynamic filtering and pagination.
     *
     * @param keyword  search term matched against product name + description
     * @param category exact category from the {@link Product.Category} enum
     * @param minPrice lower price bound (inclusive)
     * @param maxPrice upper price bound (inclusive)
     * @param sort     one of {@code newest} (default), {@code price_asc}, {@code price_desc}
     * @param page     zero-based page index; defaults to 0
     */
    @GetMapping
    public String shop(
            @RequestParam(required = false)                 String keyword,
            @RequestParam(required = false)                 Product.Category category,
            @RequestParam(required = false)                 BigDecimal minPrice,
            @RequestParam(required = false)                 BigDecimal maxPrice,
            @RequestParam(defaultValue = "newest")          String sort,
            @RequestParam(defaultValue = "0")               int page,
            Model model) {

        // Build Sort from the user-selected option
        Sort jpaSort = switch (sort) {
            case "price_asc"  -> Sort.by("price").ascending();
            case "price_desc" -> Sort.by("price").descending();
            default           -> Sort.by("createdDate").descending(); // "newest"
        };

        Pageable pageable = PageRequest.of(Math.max(page, 0), PAGE_SIZE, jpaSort);

        // Clamp price bounds — negative values are semantically nonsense and are
        // treated as "no bound" rather than rejected outright (graceful degradation).
        // ProductSpecification uses JPA Criteria API, so there is no SQL-injection risk
        // regardless, but negative values would return 0 results which is confusing UX.
        BigDecimal effectiveMin = (minPrice != null && minPrice.compareTo(BigDecimal.ZERO) < 0) ? null : minPrice;
        BigDecimal effectiveMax = (maxPrice != null && maxPrice.compareTo(BigDecimal.ZERO) < 0) ? null : maxPrice;

        // Execute filtered, paginated query — variants loaded via @BatchSize (no N+1)
        Page<Product> productPage = productService.findFiltered(
                blankToNull(keyword), category, effectiveMin, effectiveMax, pageable);

        // ── Thymeleaf model ───────────────────────────────────────────────────
        model.addAttribute("products",    productPage.getContent());
        model.addAttribute("productPage", productPage);

        // Active filter values — reflected back into form inputs (use effective values after clamping)
        model.addAttribute("keyword",   keyword);
        model.addAttribute("category",  category);
        model.addAttribute("minPrice",  effectiveMin);
        model.addAttribute("maxPrice",  effectiveMax);
        model.addAttribute("sort",      sort);

        // Available categories for the sidebar filter
        model.addAttribute("categories", Product.Category.values());

        // Pagination helpers for the template
        model.addAttribute("currentPage",  productPage.getNumber());
        model.addAttribute("totalPages",   productPage.getTotalPages());
        model.addAttribute("totalItems",   productPage.getTotalElements());
        model.addAttribute("hasNext",      productPage.hasNext());
        model.addAttribute("hasPrev",      productPage.hasPrevious());

        return "shop";
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /** Converts blank/whitespace strings to null so the Specification ignores them. */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }
}
