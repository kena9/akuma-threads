package com.akumathreads.controller;

import com.akumathreads.dto.RegisterRequest;
import com.akumathreads.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    // ── Login ──────────────────────────────────────────────────────────────
    @GetMapping("/login")
    public String loginPage(@RequestParam(required = false) String error,
                            @RequestParam(required = false) String logout,
                            Model model) {
        if (error != null) model.addAttribute("errorMsg", "Invalid email or password.");
        if (logout != null) model.addAttribute("logoutMsg", "You have been logged out.");
        return "auth/login";
    }

    // ── Register ───────────────────────────────────────────────────────────
    @GetMapping("/register")
    public String registerPage(Model model) {
        model.addAttribute("registerRequest", new RegisterRequest());
        return "auth/register";
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute RegisterRequest form,
                           BindingResult result,
                           RedirectAttributes redirectAttributes,
                           Model model) {

        // Passwords must match
        if (!form.getPassword().equals(form.getConfirmPassword())) {
            result.rejectValue("confirmPassword", "mismatch", "Passwords do not match.");
        }

        // Email must not already exist
        if (userService.emailExists(form.getEmail())) {
            result.rejectValue("email", "duplicate", "An account with this email already exists.");
        }

        if (result.hasErrors()) {
            return "auth/register";
        }

        userService.register(form.getName(), form.getEmail(), form.getPassword());
        redirectAttributes.addFlashAttribute("successMsg",
                "Account created! Please log in.");
        return "redirect:/login";
    }
}
