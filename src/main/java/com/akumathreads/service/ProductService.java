package com.akumathreads.service;

import com.akumathreads.dto.ProductCardDto;
import com.akumathreads.dto.ProductFormDto;
import com.akumathreads.model.Product;
import com.akumathreads.model.ProductVariant;
import com.akumathreads.repository.ProductRepository;
import com.akumathreads.repository.ProductSpecification;
import com.akumathreads.repository.ProductVariantRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Business logic for product catalogue management.
 *
 * <p>All reads use the class-level {@code readOnly = true} transaction.
 * All writes override with {@code readOnly = false, rollbackFor = Exception.class}.
 *
 * <p>Soft deletes are handled transparently by the {@code @SQLDelete} annotation
 * on {@link Product} — calling {@code productRepository.delete(product)} issues a
 * {@code UPDATE products SET deleted = true} rather than a physical DELETE.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;

    // ── Read operations ──────────────────────────────────────────────────────

    /**
     * Returns all active, non-deleted products with their variants pre-loaded.
     * Safe for the shop listing page — no N+1 queries.
     */
    public List<Product> findAllActiveWithVariants() {
        return productRepository.findAllActiveWithVariants();
    }

    /**
     * Finds one product by ID with variants eagerly loaded.
     * Used for the product detail page and add-to-cart flow.
     *
     * @param id PK of the product
     * @return {@link Optional} with the product + variants, or empty
     */
    public Optional<Product> findByIdWithVariants(Long id) {
        return productRepository.findByIdWithVariants(id);
    }

    /**
     * Finds products matching the given keyword (case-insensitive name search).
     * Used by the shop search bar.
     *
     * @param keyword search term
     * @return matching active products
     */
    public List<Product> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return productRepository.findByActiveTrueOrderByCreatedDateDesc();
        }
        return productRepository.findByNameContainingIgnoreCaseAndActiveTrue(keyword.strip());
    }

    /**
     * Filters active products by category.
     *
     * @param category the category to filter on
     * @return matching active products
     */
    public List<Product> findByCategory(Product.Category category) {
        return productRepository.findByCategoryAndActiveTrue(category);
    }

    /**
     * Admin: retrieves all soft-deleted products for the trash/restore view.
     */
    public List<Product> findAllSoftDeleted() {
        return productRepository.findAllSoftDeleted();
    }

    /**
     * Paginated, dynamically filtered shop listing — the primary shop read path.
     *
     * <p>Composes a {@link Specification} from the caller's active filters and delegates
     * to {@link ProductRepository#findAll(Specification, Pageable)}.
     * Null or blank filter values are ignored (pass-through, no predicate added).
     * Variant loading per page is handled by {@code @BatchSize(size = 30)} on
     * {@link Product#variants} — no N+1 queries even with 12 products per page.
     *
     * @param keyword   case-insensitive substring matched against name AND description; null = ignore
     * @param category  exact category filter; null = all categories
     * @param minPrice  minimum price inclusive; null = no lower bound
     * @param maxPrice  maximum price inclusive; null = no upper bound
     * @param pageable  page, size (12), and sort direction from the request
     * @return one page of matching active products
     */
    /**
     * Paginated, dynamically filtered shop listing — the primary shop read path.
     *
     * <p>Returns {@link ProductCardDto} (not raw entities) so the result is safe to cache.
     * Caching JPA entities with lazy {@code variants} causes
     * {@code LazyInitializationException} on cache hits because the session is closed.
     * Mapping to a DTO inside this method (while the session is still open) avoids that.
     *
     * <p>{@code Page.map()} preserves pagination metadata while converting each element.
     */
    @Cacheable(value = "products", key = "#keyword + '_' + #category + '_' + #minPrice + '_' + #maxPrice + '_' + #pageable.pageNumber + '_' + #pageable.sort")
    public Page<ProductCardDto> findFiltered(String keyword,
                                             Product.Category category,
                                             BigDecimal minPrice,
                                             BigDecimal maxPrice,
                                             Pageable pageable) {
        Specification<Product> spec = ProductSpecification.withFilters(keyword, category, minPrice, maxPrice);
        // Map inside the transaction — session is open, variant batch-load succeeds
        return productRepository.findAll(spec, pageable).map(ProductCardDto::from);
    }

    // ── Write operations ─────────────────────────────────────────────────────

    /**
     * Persists a new product or updates an existing one.
     *
     * @param product the product to save (new entity if id is null, update otherwise)
     * @return the saved product with generated ID populated
     */
    @Transactional(readOnly = false, rollbackFor = Exception.class)
    public Product save(Product product) {
        return productRepository.save(product);
    }

    /**
     * Soft-deletes a product by PK.
     *
     * <p>Calls {@code repository.delete()} which the {@code @SQLDelete} on {@link Product}
     * rewrites to {@code UPDATE products SET deleted = true WHERE id = ?}.
     * The product's {@code active} flag is also set to {@code false} so it is
     * consistently excluded from all active-product queries.
     *
     * @param productId PK of the product to soft-delete
     * @throws EntityNotFoundException if no product with the given ID exists
     */
    @CacheEvict(value = "products", allEntries = true)
    @Transactional(readOnly = false, rollbackFor = Exception.class)
    public void softDeleteProduct(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Product not found: " + productId));
        product.setActive(false);
        // Calling delete() triggers @SQLDelete — issues UPDATE SET deleted=true, not a physical DELETE
        productRepository.delete(product);
    }

    /**
     * Admin: returns all non-deleted products (active AND inactive) sorted newest-first.
     * Respects the {@code @SQLRestriction("deleted = false")} on {@link Product} —
     * physically deleted rows are never shown, but inactive / hidden products are included.
     *
     * @return all non-deleted products
     */
    public List<Product> findAllForAdmin() {
        return productRepository.findAll(Sort.by(Sort.Direction.DESC, "createdDate"));
    }

    /**
     * Flips the {@code active} flag for a product, toggling its storefront visibility.
     *
     * @param productId PK of the product to toggle
     * @throws EntityNotFoundException if the product does not exist
     */
    @CacheEvict(value = "products", allEntries = true)
    @Transactional(readOnly = false, rollbackFor = Exception.class)
    public void toggleActive(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + productId));
        product.setActive(!product.isActive());
        productRepository.save(product);
    }

    /**
     * Creates or updates a product and upserts its per-size stock variants from a
     * flat admin form DTO.
     *
     * <p>New products are created with {@code active = true}; existing products preserve
     * their current active state. For each of the six standard sizes (XS–XXL), the method
     * finds an existing {@link ProductVariant} or creates one, then sets its stock quantity
     * from the DTO.
     *
     * @param form the validated product form DTO
     * @return the saved {@link Product}
     */
    @CacheEvict(value = "products", allEntries = true)
    @Transactional(readOnly = false, rollbackFor = Exception.class)
    public Product saveProductWithVariants(ProductFormDto form) {
        Product product;
        if (form.getId() != null) {
            product = productRepository.findByIdWithVariants(form.getId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Product not found: " + form.getId()));
        } else {
            product = new Product();
            product.setActive(true);
        }

        product.setName(form.getName());
        product.setPrice(form.getPrice());
        product.setDescription(form.getDescription());
        product.setCategory(form.getCategory());
        product.setImageUrl(form.getImageUrl());
        product.setAdditionalImages(form.getAdditionalImages());
        product.setDropDate(form.getDropDate());
        product.setEditionSize(form.getEditionSize());

        // Save first to ensure the product has an ID before variant upsert
        product = productRepository.save(product);

        // Build size → qty map from the flat DTO fields
        Map<ProductVariant.Size, Integer> sizeQty = new EnumMap<>(ProductVariant.Size.class);
        sizeQty.put(ProductVariant.Size.XS,  coerce(form.getStockXs()));
        sizeQty.put(ProductVariant.Size.S,   coerce(form.getStockS()));
        sizeQty.put(ProductVariant.Size.M,   coerce(form.getStockM()));
        sizeQty.put(ProductVariant.Size.L,   coerce(form.getStockL()));
        sizeQty.put(ProductVariant.Size.XL,  coerce(form.getStockXl()));
        sizeQty.put(ProductVariant.Size.XXL, coerce(form.getStockXxl()));

        // Fetch existing variants for this product and key by size
        Map<ProductVariant.Size, ProductVariant> existingBySize = variantRepository
                .findByProductId(product.getId())
                .stream()
                .collect(Collectors.toMap(ProductVariant::getSize, v -> v));

        // Upsert: update if exists, create if not
        for (Map.Entry<ProductVariant.Size, Integer> entry : sizeQty.entrySet()) {
            ProductVariant variant = existingBySize.getOrDefault(entry.getKey(), null);
            if (variant == null) {
                variant = new ProductVariant();
                variant.setProduct(product);
                variant.setSize(entry.getKey());
            }
            variant.setStockQty(entry.getValue());
            variantRepository.save(variant);
        }

        return product;
    }

    /**
     * Converts a {@link Product} (with variants pre-loaded) to a flat {@link ProductFormDto}
     * for populating the admin edit form.
     *
     * @param product the product entity with variants eagerly loaded
     * @return a populated DTO ready for Thymeleaf form binding
     */
    public ProductFormDto toFormDto(Product product) {
        ProductFormDto dto = new ProductFormDto();
        dto.setId(product.getId());
        dto.setName(product.getName());
        dto.setPrice(product.getPrice());
        dto.setDescription(product.getDescription());
        dto.setCategory(product.getCategory());
        dto.setImageUrl(product.getImageUrl());
        dto.setAdditionalImages(product.getAdditionalImages());
        dto.setDropDate(product.getDropDate());
        dto.setEditionSize(product.getEditionSize());

        if (product.getVariants() != null) {
            Map<ProductVariant.Size, Integer> stockMap = product.getVariants().stream()
                    .collect(Collectors.toMap(ProductVariant::getSize, ProductVariant::getStockQty));
            dto.setStockXs( stockMap.getOrDefault(ProductVariant.Size.XS,  0));
            dto.setStockS(  stockMap.getOrDefault(ProductVariant.Size.S,   0));
            dto.setStockM(  stockMap.getOrDefault(ProductVariant.Size.M,   0));
            dto.setStockL(  stockMap.getOrDefault(ProductVariant.Size.L,   0));
            dto.setStockXl( stockMap.getOrDefault(ProductVariant.Size.XL,  0));
            dto.setStockXxl(stockMap.getOrDefault(ProductVariant.Size.XXL, 0));
        }
        return dto;
    }

    /** Null-safe integer coercion — treats null form values as zero. */
    private static int coerce(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    /**
     * Restores a soft-deleted product by setting {@code deleted = false} via a
     * direct field update followed by a save. The {@code @SQLRestriction} is a
     * Hibernate-level filter that cannot be bypassed with JPQL for a single entity
     * read, so we use a native query approach via {@link ProductRepository#findAllSoftDeleted()}
     * combined with an ID lookup on the raw result.
     *
     * @param productId PK of the product to restore
     * @throws EntityNotFoundException if no soft-deleted product with the given ID exists
     */
    @CacheEvict(value = "products", allEntries = true)
    @Transactional(readOnly = false, rollbackFor = Exception.class)
    public Product restoreProduct(Long productId) {
        Product product = productRepository.findAllSoftDeleted().stream()
                .filter(p -> p.getId().equals(productId))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException(
                        "Soft-deleted product not found: " + productId));
        product.setDeleted(false);
        product.setActive(true);
        return productRepository.save(product);
    }
}
