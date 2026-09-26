package com.banking.account.service;

import com.banking.account.dto.AccountRequest;
import com.banking.account.dto.AccountResponse;
import com.banking.account.dto.InternalTransferRequest;
import com.banking.account.entity.Account;
import com.banking.account.exception.AccessDeniedException;
import com.banking.account.repository.AccountRepository;
import com.banking.account.util.IbanGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;

@Service
public class AccountService {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private IbanGenerator ibanGenerator;

    public AccountResponse openAccount(AccountRequest request, Long userId) {

        Account account = new Account();
        account.setUserId(userId);
        account.setCustomerId(request.getCustomerId());
        account.setCurrency(request.getCurrency());
        account.setIban(ibanGenerator.generate());

        accountRepository.save(account);

        return toResponse(account);
    }

    @Cacheable(value = "accounts", key = "#id")
    public AccountResponse getAccountById(Long id, Long userId, String role) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Hesap bulunamadı"));

        checkReadAccess(account, userId, role);

        return toResponse(account);
    }

    public List<AccountResponse> getAllAccounts(Long userId, String role) {
        if ("ADMIN".equals(role)) {
            return accountRepository.findAll()
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }

        return accountRepository.findAll()
                .stream()
                .filter(account -> account.getUserId().equals(userId))
                .map(this::toResponse)
                .toList();
    }

    @CacheEvict(value = "accounts", key = "#accountId")
    public AccountResponse deposit(Long accountId, BigDecimal amount, Long userId) {

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Yatırılacak miktar sıfırdan büyük olmalı");
        }

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Hesap bulunamadı"));

        account.setBalance(account.getBalance().add(amount));
        accountRepository.save(account);

        return toResponse(account);
    }

    @CacheEvict(value = "accounts", key = "#accountId")
    public AccountResponse withdraw(Long accountId, BigDecimal amount, Long userId) {

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Çekilecek miktar sıfırdan büyük olmalı");
        }

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Hesap bulunamadı"));

        checkWriteAccess(account, userId);

        if (account.getBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("Yetersiz bakiye");
        }

        account.setBalance(account.getBalance().subtract(amount));
        accountRepository.save(account);

        return toResponse(account);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "accounts", key = "#request.senderAccountId"),
            @CacheEvict(value = "accounts", key = "#request.receiverAccountId")
    })
    public AccountResponse transfer(InternalTransferRequest request, Long userId) {

        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Transfer miktarı sıfırdan büyük olmalı");
        }

        Account sender = accountRepository.findById(request.getSenderAccountId())
                .orElseThrow(() -> new IllegalArgumentException("Gönderen hesap bulunamadı"));

        checkWriteAccess(sender, userId);

        Account receiver = accountRepository.findById(request.getReceiverAccountId())
                .orElseThrow(() -> new IllegalArgumentException("Alıcı hesap bulunamadı"));

        if (!"ACTIVE".equals(sender.getStatus())) {
            throw new IllegalArgumentException("Gönderen hesap aktif değil");
        }

        if (!"ACTIVE".equals(receiver.getStatus())) {
            throw new IllegalArgumentException("Alıcı hesap aktif değil");
        }

        if (!sender.getCurrency().equals(receiver.getCurrency())) {
            throw new IllegalArgumentException("Gönderen ve alıcı hesapların para birimleri farklı");
        }

        if (sender.getBalance().compareTo(request.getAmount()) < 0) {
            throw new IllegalArgumentException("Yetersiz bakiye");
        }

        sender.setBalance(sender.getBalance().subtract(request.getAmount()));
        receiver.setBalance(receiver.getBalance().add(request.getAmount()));

        accountRepository.save(sender);
        accountRepository.save(receiver);

        return toResponse(sender);
    }

    private void checkReadAccess(Account account, Long userId, String role) {
        if ("ADMIN".equals(role)) {
            return;
        }
        if (!account.getUserId().equals(userId)) {
            throw new AccessDeniedException("Bu hesaba erişim yetkiniz yok");
        }
    }

    private void checkWriteAccess(Account account, Long userId) {
        if (!account.getUserId().equals(userId)) {
            throw new AccessDeniedException("Bu hesap üzerinde işlem yapma yetkiniz yok");
        }
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