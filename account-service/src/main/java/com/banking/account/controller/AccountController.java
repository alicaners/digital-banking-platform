package com.banking.account.controller;

import com.banking.account.dto.AccountRequest;
import com.banking.account.dto.AccountResponse;
import com.banking.account.dto.InternalTransferRequest;
import com.banking.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.banking.account.dto.AmountRequest;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

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
    public ResponseEntity<Page<AccountResponse>> getAllAccounts(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(accountService.getAllAccounts(userId, role, pageable));
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

    @PostMapping("/internal/transfer")
    public ResponseEntity<AccountResponse> transfer(
            @RequestBody InternalTransferRequest request,
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(accountService.transfer(request, userId));
    }
}