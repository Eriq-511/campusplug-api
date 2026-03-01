package com.campusplug.api.conversations;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "conversations")
public class ConversationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "listing_id")
    private Long listingId;

    // The student who initiated contact about the listing (role-neutral: they may also
    // post their own listings in other conversations)
    @Column(name = "inquirer_user_id", nullable = false)
    private Long inquirerUserId;

    // The student who posted the listing (role-neutral: they may also inquire about
    // other listings in other conversations)
    @Column(name = "poster_user_id", nullable = false)
    private Long posterUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getListingId() {
        return listingId;
    }

    public void setListingId(Long listingId) {
        this.listingId = listingId;
    }

    public Long getInquirerUserId() {
        return inquirerUserId;
    }

    public void setInquirerUserId(Long inquirerUserId) {
        this.inquirerUserId = inquirerUserId;
    }

    public Long getPosterUserId() {
        return posterUserId;
    }

    public void setPosterUserId(Long posterUserId) {
        this.posterUserId = posterUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
