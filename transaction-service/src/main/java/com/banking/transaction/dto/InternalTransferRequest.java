package com.banking.transaction.dto;

import java.math.BigDecimal;

public class InternalTransferRequest {

    private Long senderAccountId;
    private Long receiverAccountId;
    private BigDecimal amount;
    private String idempotencyKey;

    public InternalTransferRequest() {}

    public InternalTransferRequest(Long senderAccountId, Long receiverAccountId, BigDecimal amount, String idempotencyKey) {
        this.senderAccountId = senderAccountId;
        this.receiverAccountId = receiverAccountId;
        this.amount = amount;
        this.idempotencyKey = idempotencyKey;
    }

    public Long getSenderAccountId() { return senderAccountId; }
    public void setSenderAccountId(Long senderAccountId) { this.senderAccountId = senderAccountId; }

    public Long getReceiverAccountId() { return receiverAccountId; }
    public void setReceiverAccountId(Long receiverAccountId) { this.receiverAccountId = receiverAccountId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}