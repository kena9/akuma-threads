package com.akumathreads.controller;

import com.akumathreads.dto.ProductFormDto;
import com.akumathreads.model.Product;
import com.akumathreads.model.ProductVariant;
import com.akumathreads.repository.ProductVariantRepository;
import com.akumathreads.service.PrintfulService;
import com.akumathreads.service.PrintfulService.SyncProductDetail;
import com.akumathreads.service.PrintfulService.SyncVariant;
import com.akumathreads.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;

/**
 * Admin controller for the Printful integration center.
 *
 * <p>Allows admins to:
 * <ul>
 *   <li>Browse all sync products in their Printful store</li>
 *   <li>Import a Printful product directly into the Olly Threads catalog</li>
 *   <li>Link Printful sync variant IDs to existing product variants</li>
 * </ul>
 *
 * <p>All routes require {@code ROLE_ADMIN}.
 */
@Controller
@RequestMapping("/admin/printful")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class PrintfulAdminController {

    private final PrintfulService         printfulService;
    private final ProductService          productService;
    private final ProductVariantRepository variantRepository;

    // ── Browse Printful store products ────────────────────────────────────────

    /**
     * Lists all sync products in the connected Printful store.
     * Shows a helpful empty state if no products are set up yet.
     */
    @GetMapping
    public String printfulCenter(Model model) {
        List<PrintfulService.SyncProduct> products = printfulService.getSyncProducts();
        model.addAttribute("syncProducts", products);
        model.addAttribute("apiStatus", printfulService.getApiStatus());
        return "admin/printful";
    }

    // ── Import a Printful product into the catalog ─────────────────────────

    /**
     * Shows the import preview page for one Printful sync product.
     * Admin can review variants, set price and category before importing.
     */
    @GetMapping("/import/{printfulProductId}")
    public String importPreview(@PathVariable Long printfulProductId, Model model) {
        SyncProductDetail detail = printfulService.getSyncProduct(printfulProductId);
        if (detail == null) {
            return "redirect:/admin/printful?error=notfound";
        }
        model.addAttribute("syncProduct",  detail);
        model.addAttribute("categories",   Product.Category.values());
        return "admin/printful-import";
    }

    /**
     * Performs the actual import: creates a Product + Variants in our DB
     * with Printful sync variant IDs pre-filled. The product is created as
     * inactive so the admin can review it before making it live.
     */
    @PostMapping("/import/{printfulProductId}")
    public String doImport(@PathVariable Long printfulProductId,
                           @RequestParam              String   productName,
                           @RequestParam              BigDecimal price,
                           @RequestParam              Product.Category category,
                           @RequestParam(required = false) String imageUrl,
                           @RequestParam(required = false) String description,
                           RedirectAttributes attrs) {

        SyncProductDetail detail = printfulService.getSyncProduct(printfulProductId);
        if (detail == null) {
            attrs.addFlashAttribute("error", "Could not fetch product from Printful.");
            return "redirect:/admin/printful";
        }

        // Build a ProductFormDto from the Printful data + admin-supplied fields
        ProductFormDto form = new ProductFormDto();
        form.setName(productName.isBlank() ? detail.name() : productName);
        form.setPrice(price);
        form.setCategory(category);

        // Hero image: admin override → Printful thumbnail → first mockup image
        String heroImage = (imageUrl != null && !imageUrl.isBlank()) ? imageUrl
                : !detail.imageUrls().isEmpty() ? detail.imageUrls().get(0)
                : detail.thumbnailUrl();
        form.setImageUrl(heroImage);

        // Additional images: all Printful mockup URLs except the hero, joined as CSV
        if (detail.imageUrls().size() > 1) {
            String additional = detail.imageUrls().stream()
                    .skip(1)
                    .collect(java.util.stream.Collectors.joining(","));
            form.setAdditionalImages(additional);
        }
        form.setDescription(description);

        // Map Printful sync variant IDs by size
        for (SyncVariant sv : detail.syncVariants()) {
            mapPrintfulVariant(form, sv);
        }

        // Default stock to 999 (Printful is print-on-demand — no real stock limit)
        form.setStockXs(form.getPrintfulIdXs()   != null ? 999 : 0);
        form.setStockS( form.getPrintfulIdS()    != null ? 999 : 0);
        form.setStockM( form.getPrintfulIdM()    != null ? 999 : 0);
        form.setStockL( form.getPrintfulIdL()    != null ? 999 : 0);
        form.setStockXl(form.getPrintfulIdXl()   != null ? 999 : 0);
        form.setStockXxl(form.getPrintfulIdXxl() != null ? 999 : 0);

        Product saved = productService.saveProductWithVariants(form);

        // Imported products start as inactive — admin activates when ready
        saved.setActive(false);
        productService.save(saved);

        attrs.addFlashAttribute("success",
                "'" + saved.getName() + "' imported from Printful. Activate it when ready.");
        return "redirect:/admin/product/" + saved.getId() + "/edit";
    }

    // ── Link Printful variant IDs to an existing product ─────────────────────

    /**
     * Updates the Printful sync variant IDs for an existing product's variants.
     * Called from the product edit form's Printful section.
     */
    @PostMapping("/link")
    public String linkVariants(@RequestParam Long   productId,
                               @RequestParam(required = false) Long printfulIdXs,
                               @RequestParam(required = false) Long printfulIdS,
                               @RequestParam(required = false) Long printfulIdM,
                               @RequestParam(required = false) Long printfulIdL,
                               @RequestParam(required = false) Long printfulIdXl,
                               @RequestParam(required = false) Long printfulIdXxl,
                               RedirectAttributes attrs) {

        List<ProductVariant> variants = variantRepository.findByProductId(productId);
        for (ProductVariant v : variants) {
            switch (v.getSize()) {
                case XS  -> v.setPrintfulSyncVariantId(printfulIdXs);
                case S   -> v.setPrintfulSyncVariantId(printfulIdS);
                case M   -> v.setPrintfulSyncVariantId(printfulIdM);
                case L   -> v.setPrintfulSyncVariantId(printfulIdL);
                case XL  -> v.setPrintfulSyncVariantId(printfulIdXl);
                case XXL -> v.setPrintfulSyncVariantId(printfulIdXxl);
            }
            variantRepository.save(v);
        }

        attrs.addFlashAttribute("success", "Printful variant IDs updated.");
        return "redirect:/admin/product/" + productId + "/edit";
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Maps a Printful sync variant to the corresponding size field on the DTO.
     * Matches on common size abbreviations in the variant name.
     */
    private void mapPrintfulVariant(ProductFormDto form, SyncVariant sv) {
        String size = sv.sizePart().toUpperCase().trim();
        // Normalise common aliases
        if (size.equals("2XL") || size.equals("2X") || size.equals("DOUBLE XL")) size = "XXL";

        switch (size) {
            case "XS"  -> form.setPrintfulIdXs(sv.id());
            case "S"   -> form.setPrintfulIdS(sv.id());
            case "M"   -> form.setPrintfulIdM(sv.id());
            case "L"   -> form.setPrintfulIdL(sv.id());
            case "XL"  -> form.setPrintfulIdXl(sv.id());
            case "XXL" -> form.setPrintfulIdXxl(sv.id());
            // Unrecognised size — silently skip; admin can link manually
        }
    }
}
