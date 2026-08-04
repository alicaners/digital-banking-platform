package com.banking.account.dto;

import jakarta.validation.constraints.NotNull;

public class AccountRequest {

    @NotNull(message = "Müşteri ID boş olamaz")
    private Long customerId;

    @NotNull(message = "Para birimi boş olamaz")
    private String currency;

    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
}