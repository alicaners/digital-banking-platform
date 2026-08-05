package com.banking.account.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public class AmountRequest {

    @NotNull(message = "Miktar boş olamaz")
    @Positive(message = "Miktar sıfırdan büyük olmalı")
    private BigDecimal amount;

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}