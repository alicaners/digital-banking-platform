package com.banking.transaction.service;

import com.banking.transaction.client.AccountServiceClient;
import com.banking.transaction.dto.AccountResponse;
import com.banking.transaction.dto.AmountRequest;
import com.banking.transaction.dto.TransactionResponse;
import com.banking.transaction.dto.TransferRequest;
import com.banking.transaction.entity.Transaction;
import com.banking.transaction.event.TransactionEvent;
import com.banking.transaction.kafka.TransactionEventProducer;
import com.banking.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountServiceClient accountServiceClient;

    @Mock
    private TransactionEventProducer eventProducer;

    @InjectMocks
    private TransactionService transactionService;

    private TransferRequest transferRequest;
    private static final Long TEST_USER_ID = 1L;

    @BeforeEach
    void setUp() {
        transferRequest = new TransferRequest();
        transferRequest.setSenderAccountId(1L);
        transferRequest.setReceiverAccountId(2L);
        transferRequest.setAmount(new BigDecimal("100.00"));

        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void transfer_bothStepsSucceed_returnsCompletedStatus() {

        when(accountServiceClient.withdraw(eq(1L), any(AmountRequest.class), eq(TEST_USER_ID)))
                .thenReturn(new AccountResponse());
        when(accountServiceClient.deposit(eq(2L), any(AmountRequest.class), eq(TEST_USER_ID)))
                .thenReturn(new AccountResponse());

        TransactionResponse response = transactionService.transfer(transferRequest, TEST_USER_ID);

        assertEquals("COMPLETED", response.getStatus());
        assertNull(response.getFailureReason());
        verify(eventProducer, times(1)).publish(any(TransactionEvent.class));
    }

    @Test
    void transfer_withdrawFails_returnsFailedStatusAndNeverAttemptsDeposit() {

        when(accountServiceClient.withdraw(eq(1L), any(AmountRequest.class), eq(TEST_USER_ID)))
                .thenThrow(new RuntimeException("Yetersiz bakiye"));

        TransactionResponse response = transactionService.transfer(transferRequest, TEST_USER_ID);

        assertEquals("FAILED", response.getStatus());
        assertEquals("Yetersiz bakiye", response.getFailureReason());
        verify(accountServiceClient, never()).deposit(anyLong(), any(AmountRequest.class), anyLong());
    }

    @Test
    void transfer_depositFailsButCompensationSucceeds_returnsReversedStatus() {

        when(accountServiceClient.withdraw(eq(1L), any(AmountRequest.class), eq(TEST_USER_ID)))
                .thenReturn(new AccountResponse());
        when(accountServiceClient.deposit(eq(2L), any(AmountRequest.class), eq(TEST_USER_ID)))
                .thenThrow(new RuntimeException("Hesap bulunamadı"));
        when(accountServiceClient.deposit(eq(1L), any(AmountRequest.class), eq(TEST_USER_ID)))
                .thenReturn(new AccountResponse());

        TransactionResponse response = transactionService.transfer(transferRequest, TEST_USER_ID);

        assertEquals("REVERSED", response.getStatus());
        assertEquals("Hesap bulunamadı", response.getFailureReason());
        verify(accountServiceClient, times(1)).deposit(eq(1L), any(AmountRequest.class), eq(TEST_USER_ID));
    }

    @Test
    void transfer_depositAndCompensationBothFail_returnsFailedStatus() {

        when(accountServiceClient.withdraw(eq(1L), any(AmountRequest.class), eq(TEST_USER_ID)))
                .thenReturn(new AccountResponse());
        when(accountServiceClient.deposit(eq(2L), any(AmountRequest.class), eq(TEST_USER_ID)))
                .thenThrow(new RuntimeException("Hesap bulunamadı"));
        when(accountServiceClient.deposit(eq(1L), any(AmountRequest.class), eq(TEST_USER_ID)))
                .thenThrow(new RuntimeException("Hesap servisi kapalı"));

        TransactionResponse response = transactionService.transfer(transferRequest, TEST_USER_ID);

        assertEquals("FAILED", response.getStatus());
    }
}