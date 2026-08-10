package com.banking.transaction.client;

import com.banking.transaction.dto.AccountResponse;
import com.banking.transaction.dto.AmountRequest;
import org.springframework.stereotype.Component;

@Component
public class AccountServiceClientFallback implements AccountServiceClient {

    @Override
    public AccountResponse deposit(Long id, AmountRequest request) {
        throw new RuntimeException("Hesap servisi şu anda kullanılamıyor (fallback devreye girdi)");
    }

    @Override
    public AccountResponse withdraw(Long id, AmountRequest request) {
        throw new RuntimeException("Hesap servisi şu anda kullanılamıyor (fallback devreye girdi)");
    }
}