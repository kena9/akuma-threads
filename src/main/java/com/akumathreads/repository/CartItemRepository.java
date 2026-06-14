package com.akumathreads.repository;

import com.akumathreads.model.CartItem;
import com.akumathreads.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    List<CartItem> findByUser(User user);
    Optional<CartItem> findByUserAndVariantId(User user, Long variantId);
    void deleteByUser(User user);
    int countByUser(User user);
}
