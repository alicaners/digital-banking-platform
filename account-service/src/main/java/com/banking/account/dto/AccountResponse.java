package com.banking.account.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class AccountResponse {

    private Long id;
    private String iban;
    private Long customerId;
    private BigDecimal balance;
    private String currency;
    private String status;
    private LocalDateTime createdAt;

    public AccountResponse(Long id, String iban, Long customerId, BigDecimal balance,
                           String currency, String status, LocalDateTime createdAt) {
        this.id = id;
        this.iban = iban;
        this.customerId = customerId;
        this.balance = balance;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getIban() { return iban; }
    public Long getCustomerId() { return customerId; }
    public BigDecimal getBalance() { return balance; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}