package com.akumathreads.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the three static legal pages — no model attributes needed.
 */
@Controller
public class LegalController {

    @GetMapping("/privacy-policy")
    public String privacyPolicy() {
        return "legal/privacy-policy";
    }

    @GetMapping("/terms-of-service")
    public String termsOfService() {
        return "legal/terms-of-service";
    }

    @GetMapping("/refund-and-shipping")
    public String refundAndShipping() {
        return "legal/refund-and-shipping";
    }
}
