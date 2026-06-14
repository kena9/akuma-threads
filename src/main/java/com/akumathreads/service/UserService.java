package com.akumathreads.service;

import com.akumathreads.model.User;
import com.akumathreads.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Business logic for user account management.
 *
 * <p>Class-level {@code readOnly = true} optimises all reads; every write
 * method overrides with {@code readOnly = false, rollbackFor = Exception.class}.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    // ── Read operations ──────────────────────────────────────────────────────

    public boolean emailExists(String email) {
        return userRepository.existsByEmail(email);
    }

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    // ── Write operations ─────────────────────────────────────────────────────

    /**
     * Registers a new customer account.
     *
     * @param name        display name
     * @param email       must be unique
     * @param rawPassword plain-text password — BCrypt-hashed before persistence
     * @return the saved {@link User}
     * @throws IllegalArgumentException if a user with this email already exists
     */
    @Transactional(readOnly = false, rollbackFor = Exception.class)
    public User register(String name, String email, String rawPassword) {
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already in use: " + email);
        }
        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(User.Role.CUSTOMER);
        return userRepository.save(user);
    }

    /**
     * Soft-deletes a user account. The {@code @SQLDelete} on {@link User} rewrites
     * the DELETE to an UPDATE SET deleted = true, preserving the user's order history.
     *
     * @param userId PK of the user to soft-delete
     * @throws EntityNotFoundException if no user with the given ID exists
     */
    @Transactional(readOnly = false, rollbackFor = Exception.class)
    public void softDeleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));
        userRepository.delete(user);
    }

    /**
     * Promotes a user to the ADMIN role.
     *
     * @param userId PK of the user to promote
     * @return the updated {@link User}
     */
    @Transactional(readOnly = false, rollbackFor = Exception.class)
    public User promoteToAdmin(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));
        user.setRole(User.Role.ADMIN);
        return userRepository.save(user);
    }

    /**
     * Updates a user's display name.
     *
     * @param userId  PK of the user
     * @param newName the new display name (must not be blank)
     * @return the updated {@link User}
     */
    @Transactional(readOnly = false, rollbackFor = Exception.class)
    public User updateName(Long userId, String newName) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("Name must not be blank.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));
        user.setName(newName.strip());
        return userRepository.save(user);
    }
}
