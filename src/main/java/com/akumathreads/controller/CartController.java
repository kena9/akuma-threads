package com.akumathreads.controller;

import com.akumathreads.model.SessionCart;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * MVC controller for the cart page at {@code GET /cart}.
 *
 * <p>The cart is stored in the HTTP session by {@link CartRestController}.
 * This controller simply reads it from the session and passes it to the template.
 * Cart mutations (add / remove) are handled by {@link CartRestController} via AJAX.
 */
@Controller
public class CartController {

    @GetMapping("/cart")
    public String cartPage(HttpSession session, Model model) {
        Object attr = session.getAttribute(CartRestController.CART_KEY);
        SessionCart cart = (attr instanceof SessionCart sc) ? sc : new SessionCart();
        model.addAttribute("cart", cart);
        return "cart";
    }
}
