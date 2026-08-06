package com.banking.transaction.dto;

import java.math.BigDecimal;

public class AmountRequest {

    private BigDecimal amount;

    public AmountRequest() {}

    public AmountRequest(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}