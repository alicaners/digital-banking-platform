package com.banking.account.service;

import com.banking.account.dto.AccountResponse;
import com.banking.account.entity.Account;
import com.banking.account.repository.AccountRepository;
import com.banking.account.util.IbanGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private IbanGenerator ibanGenerator;

    @InjectMocks
    private AccountService accountService;

    private Account existingAccount;

    @BeforeEach
    void setUp() {
        existingAccount = new Account();
        existingAccount.setId(1L);
        existingAccount.setIban("TR123456789012345678901234");
        existingAccount.setCustomerId(1L);
        existingAccount.setBalance(new BigDecimal("500.00"));
        existingAccount.setCurrency("TRY");
        existingAccount.setStatus("ACTIVE");
    }

    @Test
    void deposit_validAmount_increasesBalance() {

        when(accountRepository.findById(1L)).thenReturn(Optional.of(existingAccount));
        when(accountRepository.save(any(Account.class))).thenReturn(existingAccount);

        AccountResponse response = accountService.deposit(1L, new BigDecimal("100.00"));

        assertEquals(new BigDecimal("600.00"), response.getBalance());
        verify(accountRepository, times(1)).save(existingAccount);
    }

    @Test
    void deposit_negativeAmount_throwsException() {

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> accountService.deposit(1L, new BigDecimal("-50.00"))
        );

        assertEquals("Yatırılacak miktar sıfırdan büyük olmalı", exception.getMessage());
        verify(accountRepository, never()).findById(anyLong());
    }

    @Test
    void withdraw_sufficientBalance_decreasesBalance() {

        when(accountRepository.findById(1L)).thenReturn(Optional.of(existingAccount));
        when(accountRepository.save(any(Account.class))).thenReturn(existingAccount);

        AccountResponse response = accountService.withdraw(1L, new BigDecimal("200.00"));

        assertEquals(new BigDecimal("300.00"), response.getBalance());
        verify(accountRepository, times(1)).save(existingAccount);
    }

    @Test
    void withdraw_insufficientBalance_throwsException() {

        when(accountRepository.findById(1L)).thenReturn(Optional.of(existingAccount));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> accountService.withdraw(1L, new BigDecimal("999999.00"))
        );

        assertEquals("Yetersiz bakiye", exception.getMessage());
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void getAccountById_accountNotFound_throwsException() {

        when(accountRepository.findById(99L)).thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> accountService.getAccountById(99L)
        );

        assertEquals("Hesap bulunamadı", exception.getMessage());
    }
}