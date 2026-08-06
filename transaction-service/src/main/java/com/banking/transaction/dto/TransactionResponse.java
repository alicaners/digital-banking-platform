package com.banking.transaction.dto;

import java.time.LocalDateTime;
import java.math.BigDecimal;

public class TransactionResponse {

    private Long id;
    private Long senderAccountId;
    private Long receiverAccountId;
    private BigDecimal amount;
    private String status;
    private LocalDateTime createdAt;

    public TransactionResponse(Long id, Long senderAccountId, Long receiverAccountId,
                               BigDecimal amount, String status, LocalDateTime createdAt) {
        this.id = id;
        this.senderAccountId = senderAccountId;
        this.receiverAccountId = receiverAccountId;
        this.amount = amount;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getSenderAccountId() { return senderAccountId; }
    public Long getReceiverAccountId() { return receiverAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}