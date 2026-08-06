package com.banking.transaction.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public class TransferRequest {

    @NotNull(message = "Gönderen hesap ID boş olamaz")
    private Long senderAccountId;

    @NotNull(message = "Alıcı hesap ID boş olamaz")
    private Long receiverAccountId;

    @NotNull(message = "Miktar boş olamaz")
    @Positive(message = "Miktar sıfırdan büyük olmalı")
    private BigDecimal amount;

    public Long getSenderAccountId() { return senderAccountId; }
    public void setSenderAccountId(Long senderAccountId) { this.senderAccountId = senderAccountId; }

    public Long getReceiverAccountId() { return receiverAccountId; }
    public void setReceiverAccountId(Long receiverAccountId) { this.receiverAccountId = receiverAccountId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}