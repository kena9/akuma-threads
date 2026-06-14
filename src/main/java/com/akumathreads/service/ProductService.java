package com.akumathreads.service;

import com.akumathreads.dto.ProductFormDto;
import com.akumathreads.model.Product;
import com.akumathreads.model.ProductVariant;
import com.akumathreads.repository.ProductRepository;
import com.akumathreads.repository.ProductSpecification;
import com.akumathreads.repository.ProductVariantRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
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
    public Page<Product> findFiltered(String keyword,
                                      Product.Category category,
                                      BigDecimal minPrice,
                                      BigDecimal maxPrice,
                                      Pageable pageable) {
        Specification<Product> spec = ProductSpecification.withFilters(keyword, category, minPrice, maxPrice);
        return productRepository.findAll(spec, pageable);
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
    @Transactional(readOnly = false, rollbackFor = Except