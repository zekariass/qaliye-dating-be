package com.qaliye.backend.payments.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "payment_purpose", nullable = false)
    private String paymentPurpose;

    @Column(name = "amount_minor_units", nullable = false)
    private Integer amountMinorUnits;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_transaction_id")
    private String providerTransactionId;

    @Column(nullable = false)
    private String status;

    @Column(name = "receipt_storage_bucket")
    private String receiptStorageBucket;

    @Column(name = "receipt_storage_path")
    private String receiptStoragePath;

    @Column(name = "admin_notes")
    private String adminNotes;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
