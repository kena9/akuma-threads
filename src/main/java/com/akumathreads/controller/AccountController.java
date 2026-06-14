package com.akumathreads.controller;

import com.akumathreads.model.Order;
import com.akumathreads.model.User;
import com.akumathreads.service.OrderService;
import com.akumathreads.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

/**
 * Handles authenticated customer account pages.
 *
 * <p>All routes under {@code /account} require the user to be logged in.
 * The {@code @PreAuthorize} annotation works in tandem with the
 * Spring Security filter-chain rule in {@link com.akumathreads.config.SecurityConfig}.
 */
@Controller
@RequestMapping("/account")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class AccountController {

    private final OrderService orderService;
    private final UserService userService;

    /**
     * Customer order history page — shows all orders for the logged-in user,
     * newest first, with full item detail eagerly loaded.
     *
     * <p>Uses {@link OrderService#findAllOrdersForUser(Long)} which issues a single
     * JOIN FETCH query — no N+1 even with many orders.
     *
     * @param principal the currently authenticated user's Spring Security principal
     * @param model     Spring MVC model
     * @return the {@code account/orders} Thymeleaf template
     */
    @GetMapping("/orders")
    public String orderHistory(@AuthenticationPrincipal UserDetails principal,
                               Model model) {
        User user = userService.findByEmail(principal.getUsername())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));

        List<Order> orders = orderService.findAllOrdersForUser(user.getId());
        model.addAttribute("orders", orders);
        return "account/orders";
    }
}
