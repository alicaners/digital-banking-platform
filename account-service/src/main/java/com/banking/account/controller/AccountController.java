package com.banking.account.controller;

import com.banking.account.dto.AccountRequest;
import com.banking.account.dto.AccountResponse;
import com.banking.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.banking.account.dto.AmountRequest;
import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    @Autowired
    private AccountService accountService;

    @PostMapping
    public ResponseEntity<AccountResponse> openAccount(
            @Valid @RequestBody AccountRequest request,
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(accountService.openAccount(request, userId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> getAccountById(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(accountService.getAccountById(id, userId, role));
    }

    @GetMapping
    public ResponseEntity<List<AccountResponse>> getAllAccounts(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(accountService.getAllAccounts(userId, role));
    }

    @PostMapping("/{id}/deposit")
    public ResponseEntity<AccountResponse> deposit(
            @PathVariable Long id,
            @Valid @RequestBody AmountRequest request,
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(accountService.deposit(id, request.getAmount(), userId));
    }

    @PostMapping("/{id}/withdraw")
    public ResponseEntity<AccountResponse> withdraw(
            @PathVariable Long id,
            @Valid @RequestBody AmountRequest request,
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(accountService.withdraw(id, request.getAmount(), userId));
    }
}