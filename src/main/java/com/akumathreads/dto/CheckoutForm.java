package com.akumathreads.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Form-backing DTO for the checkout shipping form.
 * Validated by {@code @Valid} in {@link com.akumathreads.controller.CheckoutController}.
 */
@Getter
@Setter
@NoArgsConstructor
public class CheckoutForm {

    @NotBlank(message = "Full name is required")
    private String fullName;

    @NotBlank(message = "Email is required")
    @Email(message = "Please enter a valid email address")
    private String email;

    @NotBlank(message = "Street address is required")
    private String address;

    private String address2; // optional — apartment, suite, etc.

    @NotBlank(message = "City is required")
    private String city;

    @NotBlank(message = "State is required")
    private String state;

    @NotBlank(message = "ZIP code is required")
    @Pattern(regexp = "\\d{5}(-\\d{4})?", message = "Enter a valid ZIP code (e.g. 12345 or 12345-6789)")
    private String zip;

    @NotBlank(message = "Country is required")
    private String country;
}
