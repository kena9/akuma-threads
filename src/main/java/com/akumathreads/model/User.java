package com.akumathreads.model;

import com.akumathreads.entity.BaseAuditEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.util.List;

/**
 * User entity with soft delete and JPA auditing.
 *
 * <p>Soft-delete semantics mirror those of {@link Product}:
 * {@link SQLDelete} rewrites physical deletes to a flag update;
 * {@link SQLRestriction} hides soft-deleted users from all standard queries;
 * {@link Filter} provides a dynamic admin escape hatch.
 *
 * <p>Passwords are never stored in plain text — {@code passwordHash} holds
 * the BCrypt output from {@link org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder}.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@SQLDelete(sql = "UPDATE users SET deleted = true, last_modified_date = NOW() WHERE id = ?")
@SQLRestriction("deleted = false")
@FilterDef(
    name = "deletedUserFilter",
    parameters = @ParamDef(name = "isDeleted", type = Boolean.class)
)
@Filter(name = "deletedUserFilter", condition = "deleted = :isDeleted")
public class User extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Email
    @NotBlank
    @Column(nullable = false, unique = true)
    private String email;

    @NotBlank
    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.CUSTOMER;

    /** Soft-deleted flag. Set by @SQLDelete; never toggled directly in application code. */
    @Column(nullable = false)
    private boolean deleted = false;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Order> orders;

    // ── Enum ─────────────────────────────────────────────────────────────────

    public enum Role {
        CUSTOMER, ADMIN
    }
}
