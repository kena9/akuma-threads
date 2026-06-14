package com.akumathreads.controller;

import com.akumathreads.model.Product;
import com.akumathreads.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class HomeController {

    private final ProductService productService;

    @GetMapping("/")
    public String home(Model model) {
        // Pull the 3 newest active products for the Latest Arrivals section
        List<Product> latest = productService
                .findFiltered(null, null, null, null,
                        PageRequest.of(0, 3, Sort.by("createdDate").descending()))
                .getContent();

        model.addAttribute("latestProducts", latest);
        return "home";
    }
}
