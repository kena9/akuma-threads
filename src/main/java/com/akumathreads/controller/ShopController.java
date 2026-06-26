package com.akumathreads.controller;

import com.akumathreads.dto.ProductCardDto;
import com.akumathreads.model.Product;
import com.akumathreads.repository.OrderRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/shop")
@RequiredArgsConstructor
public class ShopController {

    private final ProductService  productService;
    private final OrderRepository orderRepository;

    private static final int PAGE_SIZE = 12;

    @GetMapping
    public String shop(
            @RequestParam(required = false)        String keyword,
            @RequestParam(required = false)        Product.Category category,
            @RequestParam(required = false)        BigDecimal minPrice,
            @RequestParam(required = false)        BigDecimal maxPrice,
            @RequestParam(defaultValue = "newest") String sort,
            @RequestParam(defaultValue = "0")      int page,
            Model model) {

        Sort jpaSort = switch (sort) {
            case "price_asc"  -> Sort.by("price").ascending();
            case "price_desc" -> Sort.by("price").descending();
            default           -> Sort.by("createdDate").descending();
        };

        Pageable pageable = PageRequest.of(Math.max(page, 0), PAGE_SIZE, jpaSort);

        BigDecimal effectiveMin = (minPrice != null && minPrice.compareTo(BigDecimal.ZERO) < 0) ? null : minPrice;
        BigDecimal effectiveMax = (maxPrice != null && maxPrice.compareTo(BigDecimal.ZERO) < 0) ? null : maxPrice;

        Page<ProductCardDto> productPage = productService.findFiltered(
                blankToNull(keyword), category, effectiveMin, effectiveMax, pageable);

        // Social proof — batch sold count for the page (single query)
        List<ProductCardDto> pageProducts = productPage.getContent();
        Map<Long, Long> soldCountMap = new HashMap<>();
        if (!pageProducts.isEmpty()) {
            List<Long> ids = pageProducts.stream().map(ProductCardDto::getId).collect(Collectors.toList());
            for (Object[] row : orderRepository.countUnitsSoldByProductIds(ids)) {
                soldCountMap.put((Long) row[0], (Long) row[1]);
            }
        }

        model.addAttribute("products",     pageProducts);
        model.addAttribute("productPage",  productPage);
        model.addAttribute("soldCountMap", soldCountMap);
        model.addAttribute("keyword",      keyword);
        model.addAttribute("category",     category);
        model.addAttribute("minPrice",     effectiveMin);
        model.addAttribute("maxPrice",     effectiveMax);
        model.addAttribute("sort",         sort);
        model.addAttribute("categories",   Product.Category.values());
        model.addAttribute("currentPage",  productPage.getNumber());
        model.addAttribute("totalPages",   productPage.getTotalPages());
        model.addAttribute("totalItems",   productPage.getTotalElements());
        model.addAttribute("hasNext",      productPage.hasNext());
        model.addAttribute("hasPrev",      productPage.hasPrevious());

        return "shop";
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }
}
