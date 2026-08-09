package com.banking.account.service;

import com.banking.account.dto.AccountRequest;
import com.banking.account.dto.AccountResponse;
import com.banking.account.entity.Account;
import com.banking.account.repository.AccountRepository;
import com.banking.account.util.IbanGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.List;

@Service
public class AccountService {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private IbanGenerator ibanGenerator;

    public AccountResponse openAccount(AccountRequest request) {

        Account account = new Account();
        account.setCustomerId(request.getCustomerId());
        account.setCurrency(request.getCurrency());
        account.setIban(ibanGenerator.generate());

        accountRepository.save(account);

        return toResponse(account);
    }

    public AccountResponse getAccountById(Long id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Hesap bulunamadı"));
        return toResponse(account);
    }

    public List<AccountResponse> getAllAccounts() {
        return accountRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public AccountResponse deposit(Long accountId, BigDecimal amount) {

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Yatırılacak miktar sıfırdan büyük olmalı");
        }

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Hesap bulunamadı"));

        account.setBalance(account.getBalance().add(amount));
        accountRepository.save(account);

        return toResponse(account);
    }

    public AccountResponse withdraw(Long accountId, BigDecimal amount) {

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Çekilecek miktar sıfırdan büyük olmalı");
        }

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Hesap bulunamadı"));

        if (account.getBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("Yetersiz bakiye");
        }

        account.setBalance(account.getBalance().subtract(amount));
        accountRepository.save(account);

        return toResponse(account);
    }

    private AccountResponse toResponse(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getIban(),
                account.getCustomerId(),
                account.getBalance(),
                account.getCurrency(),
                account.getStatus(),
                account.getCreatedAt()
        );
    }
}