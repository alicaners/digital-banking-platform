package com.banking.transaction.client;

import com.banking.transaction.dto.AccountResponse;
import com.banking.transaction.dto.AmountRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "account-service")
public interface AccountServiceClient {

    @PostMapping("/api/accounts/{id}/deposit")
    AccountResponse deposit(@PathVariable("id") Long id, @RequestBody AmountRequest request);

    @PostMapping("/api/accounts/{id}/withdraw")
    AccountResponse withdraw(@PathVariable("id") Long id, @RequestBody AmountRequest request);
}