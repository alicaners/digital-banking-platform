package com.banking.transaction.client;

import com.banking.transaction.dto.AccountResponse;
import com.banking.transaction.dto.AmountRequest;
import com.banking.transaction.dto.InternalTransferRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "account-service")
public interface AccountServiceClient {

    @PostMapping("/api/accounts/{id}/deposit")
    AccountResponse deposit(@PathVariable("id") Long id, @RequestBody AmountRequest request, @RequestHeader("X-User-Id") Long userId);

    @PostMapping("/api/accounts/{id}/withdraw")
    AccountResponse withdraw(@PathVariable("id") Long id, @RequestBody AmountRequest request, @RequestHeader("X-User-Id") Long userId);

    @PostMapping("/api/accounts/internal/transfer")
    AccountResponse transfer(@RequestBody InternalTransferRequest request, @RequestHeader("X-User-Id") Long userId);
}