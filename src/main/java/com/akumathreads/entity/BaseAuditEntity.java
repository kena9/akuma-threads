package com.akumathreads.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * JPA mapped superclass that injects four audit columns into every entity that
 * extends it. Spring Data JPA's {@link AuditingEntityListener} populates the
 * fields automatically on persist and merge using the {@link org.springframework.data.domain.AuditorAware}
 * implementation registered in {@link com.akumathreads.config.AuditingConfig}.
 *
 * <p>Replaces the manual {@code createdAt = LocalDateTime.now()} pattern previously
 * used on {@code Product} and {@code User} so all timestamps are set consistently
 * by the framework rather than at object construction time.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public abstract class BaseAuditEntity {

    /**
     * Timestamp of first persist. Set once on INSERT; never updated.
     * Maps to {@code created_date} column.
     */
    @CreatedDate
    @Column(name = "created_date", updatable = false, nullable = false)
    private LocalDateTime createdDate;

    /**
     * Timestamp of the most recent UPDATE. Null until the entity is modified
     * after initial creation.
     * Maps to {@code last_modified_date} column.
     */
    @LastModifiedDate
    @Column(name = "last_modified_date")
    private LocalDateTime lastModifiedDate;

    /**
     * Principal name (email) of the user who created this record.
     * Falls back to {@code "SYSTEM"} for background jobs and migrations.
     * Set once on INSERT; never updated.
     * Maps to {@code created_by} column.
     */
    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 255)
    private String createdBy;

    /**
     * Principal name (email) of the user who last modified this record.
     * Maps to {@code last_modified_by} column.
     */
    @LastModifiedBy
    @Column(name = "last_modified_by", length = 255)
    private String lastModifiedBy;
}
