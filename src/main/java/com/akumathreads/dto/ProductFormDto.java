package com.akumathreads.dto;

import com.akumathreads.model.Product;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Form-backing DTO for the admin product create / edit form.
 *
 * <p>Holds flat product fields plus per-size stock quantities.
 * {@link com.akumathreads.service.ProductService#saveProductWithVariants(ProductFormDto)}
 * maps this back to {@link Product} + {@link com.akumathreads.model.ProductVariant} entities.
 */
@Getter
@Setter
@NoArgsConstructor
public class ProductFormDto {

    /** null → create new; non-null → update existing */
    private Long id;

    @NotBlank(message = "Title is required")
    private String name;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.01", message = "Price must be at least $0.01")
    private BigDecimal price;

    private String description;

    @NotNull(message = "Category is required")
    private Product.Category category;

    /** Direct URL to the product image (Printify CDN or static asset path). */
    private String imageUrl;

    // ── Per-size stock quantities ─────────────────────────────────────────────

    @Min(value = 0, message = "XS stock cannot be negative")
    private Integer stockXs  = 0;

    @Min(value = 0, message = "S stock cannot be negative")
    private Integer stockS   = 0;

    @Min(value = 0, message = "M stock cannot be negative")
    private Integer stockM   = 0;

    @Min(value = 0, message = "L stock cannot be negative")
    private Integer stockL   = 0;

    @Min(value = 0, message = "XL stock cannot be negative")
    private Integer stockXl  = 0;

    @Min(value = 0, message = "XXL stock cannot be negative")
    private Integer stockXxl = 0;
}
