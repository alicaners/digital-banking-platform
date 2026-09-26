package com.banking.transaction.service;

import com.banking.transaction.client.AccountServiceClient;
import com.banking.transaction.dto.AccountResponse;
import com.banking.transaction.dto.InternalTransferRequest;
import com.banking.transaction.dto.TransactionResponse;
import com.banking.transaction.dto.TransferRequest;
import com.banking.transaction.entity.Transaction;
import com.banking.transaction.event.TransactionEvent;
import com.banking.transaction.executor.AccountServiceExecutor;
import com.banking.transaction.kafka.TransactionEventProducer;
import com.banking.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountServiceClient accountServiceClient;

    @Mock
    private AccountServiceExecutor accountServiceExecutor;

    @Mock
    private TransactionEventProducer eventProducer;

    @InjectMocks
    private TransactionService transactionService;

    private TransferRequest transferRequest;
    private static final Long TEST_USER_ID = 1L;
    private static final String TEST_IDEMPOTENCY_KEY = "test-idempotency-key-123";

    @BeforeEach
    void setUp() {
        transferRequest = new TransferRequest();
        transferRequest.setSenderAccountId(1L);
        transferRequest.setReceiverAccountId(2L);
        transferRequest.setAmount(new BigDecimal("100.00"));

        lenient().when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // AccountServiceExecutor'ı, kendisine verilen Supplier'ı gerçekten çalıştıran
        // bir "pass-through" mock haline getiriyoruz. Böylece testler, retry/circuit breaker
        // mantığını değil, TransactionService'in kendi davranışını doğruluyor.
        lenient().when(accountServiceExecutor.execute(any()))
                .thenAnswer(invocation -> {
                    Supplier<?> supplier = invocation.getArgument(0);
                    return supplier.get();
                });
    }

    @Test
    void transfer_success_returnsCompletedStatus() {

        when(transactionRepository.findByIdempotencyKey(TEST_IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(accountServiceClient.transfer(any(InternalTransferRequest.class), eq(TEST_USER_ID)))
                .thenReturn(new AccountResponse());

        TransactionResponse response = transactionService.transfer(transferRequest, TEST_USER_ID, TEST_IDEMPOTENCY_KEY);

        assertEquals("COMPLETED", response.getStatus());
        assertNull(response.getFailureReason());
        verify(eventProducer, times(1)).publish(any(TransactionEvent.class));
    }

    @Test
    void transfer_accountServiceThrowsRuntimeException_returnsFailedStatus() {

        when(transactionRepository.findByIdempotencyKey(TEST_IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(accountServiceClient.transfer(any(InternalTransferRequest.class), eq(TEST_USER_ID)))
                .thenThrow(new RuntimeException("Yetersiz bakiye"));

        TransactionResponse response = transactionService.transfer(transferRequest, TEST_USER_ID, TEST_IDEMPOTENCY_KEY);

        assertEquals("FAILED", response.getStatus());
        assertEquals("Yetersiz bakiye", response.getFailureReason());
    }

    @Test
    void transfer_sendsCorrectInternalTransferRequest() {

        when(transactionRepository.findByIdempotencyKey(TEST_IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(accountServiceClient.transfer(any(InternalTransferRequest.class), eq(TEST_USER_ID)))
                .thenReturn(new AccountResponse());

        transactionService.transfer(transferRequest, TEST_USER_ID, TEST_IDEMPOTENCY_KEY);

        verify(accountServiceClient).transfer(argThat(req ->
                req.getSenderAccountId().equals(1L) &&
                        req.getReceiverAccountId().equals(2L) &&
                        req.getAmount().compareTo(new BigDecimal("100.00")) == 0 &&
                        TEST_IDEMPOTENCY_KEY.equals(req.getIdempotencyKey())
        ), eq(TEST_USER_ID));
    }

    @Test
    void transfer_existingIdempotencyKey_returnsCachedResultWithoutCallingAccountService() {

        Transaction existing = new Transaction();
        existing.setId(99L);
        existing.setSenderAccountId(1L);
        existing.setReceiverAccountId(2L);
        existing.setAmount(new BigDecimal("100.00"));
        existing.setStatus("COMPLETED");
        existing.setIdempotencyKey(TEST_IDEMPOTENCY_KEY);
        existing.setCreatedAt(LocalDateTime.now());

        when(transactionRepository.findByIdempotencyKey(TEST_IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(existing));

        TransactionResponse response = transactionService.transfer(transferRequest, TEST_USER_ID, TEST_IDEMPOTENCY_KEY);

        assertEquals("COMPLETED", response.getStatus());
        assertEquals(99L, response.getId());
        verify(accountServiceClient, never()).transfer(any(InternalTransferRequest.class), anyLong());
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(eventProducer, never()).publish(any(TransactionEvent.class));
    }
}