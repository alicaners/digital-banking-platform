package com.banking.account.service;

import com.banking.account.client.CustomerServiceClient;
import com.banking.account.dto.AccountRequest;
import com.banking.account.dto.AccountResponse;
import com.banking.account.dto.InternalTransferRequest;
import com.banking.account.entity.Account;
import com.banking.account.entity.AccountStatus;
import com.banking.account.entity.Role;
import com.banking.account.exception.AccessDeniedException;
import com.banking.account.exception.ResourceNotFoundException;
import com.banking.account.repository.AccountRepository;
import com.banking.account.util.IbanGenerator;
import feign.FeignException;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final IbanGenerator ibanGenerator;
    private final CustomerServiceClient customerServiceClient;

    public AccountService(AccountRepository accountRepository,
                          IbanGenerator ibanGenerator,
                          CustomerServiceClient customerServiceClient) {
        this.accountRepository = accountRepository;
        this.ibanGenerator = ibanGenerator;
        this.customerServiceClient = customerServiceClient;
    }

    public AccountResponse openAccount(AccountRequest request, Long userId) {

        try {
            customerServiceClient.checkCustomerExists(request.getCustomerId());
        } catch (FeignException.NotFound e) {
            throw new ResourceNotFoundException("Belirtilen müşteri bulunamadı");
        }

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
                .orElseThrow(() -> new ResourceNotFoundException("Hesap bulunamadı"));

        checkReadAccess(account, userId, role);

        return toResponse(account);
    }

    public Page<AccountResponse> getAllAccounts(Long userId, String role, Pageable pageable) {
        if (Role.valueOf(role) == Role.ADMIN) {
            return accountRepository.findAll(pageable)
                    .map(this::toResponse);
        }

        return accountRepository.findByUserId(userId, pageable)
                .map(this::toResponse);
    }

    @CacheEvict(value = "accounts", key = "#accountId")
    public AccountResponse deposit(Long accountId, BigDecimal amount, Long userId) {

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Yatırılacak miktar sıfırdan büyük olmalı");
        }

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Hesap bulunamadı"));

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
                .orElseThrow(() -> new ResourceNotFoundException("Hesap bulunamadı"));

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
                .orElseThrow(() -> new ResourceNotFoundException("Gönderen hesap bulunamadı"));

        checkWriteAccess(sender, userId);

        Account receiver = accountRepository.findById(request.getReceiverAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Alıcı hesap bulunamadı"));

        if (sender.getStatus() != AccountStatus.ACTIVE) {
            throw new IllegalArgumentException("Gönderen hesap aktif değil");
        }

        if (receiver.getStatus() != AccountStatus.ACTIVE) {
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
        if (Role.valueOf(role) == Role.ADMIN) {
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