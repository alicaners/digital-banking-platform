package com.banking.transaction.service;

import com.banking.transaction.client.AccountServiceClient;
import com.banking.transaction.dto.AccountResponse;
import com.banking.transaction.dto.InternalTransferRequest;
import com.banking.transaction.dto.TransactionResponse;
import com.banking.transaction.dto.TransferRequest;
import com.banking.transaction.entity.Transaction;
import com.banking.transaction.event.TransactionEvent;
import com.banking.transaction.exception.IdempotencyConflictException;
import com.banking.transaction.exception.NonRetryableException;
import com.banking.transaction.executor.AccountServiceExecutor;
import com.banking.transaction.kafka.TransactionEventProducer;
import com.banking.transaction.repository.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Service
public class TransactionService {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountServiceClient accountServiceClient;

    @Autowired
    private AccountServiceExecutor accountServiceExecutor;

    @Autowired
    private TransactionEventProducer eventProducer;

    public TransactionResponse transfer(TransferRequest request, Long userId, String idempotencyKey) {

        Optional<Transaction> existing = transactionRepository.findByIdempotencyKeyAndUserId(idempotencyKey, userId);
        if (existing.isPresent()) {
            Transaction existingTransaction = existing.get();
            if (!matchesRequest(existingTransaction, request)) {
                throw new IdempotencyConflictException(
                        "Bu Idempotency-Key daha once farkli bir islem icin kullanilmis, ayni key ile farkli bir transfer yapamazsiniz"
                );
            }
            return toResponse(existingTransaction);
        }

        Transaction transaction = new Transaction();
        transaction.setUserId(userId);
        transaction.setSenderAccountId(request.getSenderAccountId());
        transaction.setReceiverAccountId(request.getReceiverAccountId());
        transaction.setAmount(request.getAmount());
        transaction.setIdempotencyKey(idempotencyKey);

        String failureReason = null;

        try {
            InternalTransferRequest transferRequest = new InternalTransferRequest(
                    request.getSenderAccountId(),
                    request.getReceiverAccountId(),
                    request.getAmount(),
                    idempotencyKey
            );

            accountServiceExecutor.execute(
                    () -> accountServiceClient.transfer(transferRequest, userId)
            );

            transaction.setStatus("COMPLETED");

        } catch (FeignException e) {

            failureReason = extractErrorMessage(e);
            transaction.setStatus("FAILED");

        } catch (NonRetryableException e) {

            failureReason = (e.getCause() instanceof FeignException fe)
                    ? extractErrorMessage(fe)
                    : e.getMessage();
            transaction.setStatus("FAILED");

        } catch (CallNotPermittedException e) {

            failureReason = "Hesap servisi şu anda geçici olarak kullanılamıyor, lütfen birazdan tekrar deneyin";
            transaction.setStatus("FAILED");

        } catch (RuntimeException e) {

            failureReason = (e.getMessage() != null)
                    ? e.getMessage()
                    : "Hesap servisi şu anda kullanılamıyor";
            transaction.setStatus("FAILED");

        } catch (Exception e) {

            failureReason = "Beklenmeyen bir hata oluştu: " + e.getMessage();
            transaction.setStatus("FAILED");
        }

        try {
            transactionRepository.save(transaction);
        } catch (DataIntegrityViolationException e) {
            Transaction raceWinner = transactionRepository.findByIdempotencyKeyAndUserId(idempotencyKey, userId)
                    .orElseThrow(() -> e);
            if (!matchesRequest(raceWinner, request)) {
                throw new IdempotencyConflictException(
                        "Bu Idempotency-Key daha once farklı bir işlem icin kullanılmış, aynı key ile farklı bir transfer yapamazsınız"
                );
            }
            return toResponse(raceWinner);
        }

        eventProducer.publish(new TransactionEvent(
                transaction.getId(),
                transaction.getSenderAccountId(),
                transaction.getReceiverAccountId(),
                transaction.getAmount(),
                transaction.getStatus()
        ));

        TransactionResponse response = toResponse(transaction);
        if (failureReason != null) {
            response.setFailureReason(failureReason);
        }
        return response;
    }

    private boolean matchesRequest(Transaction existing, TransferRequest request) {
        return existing.getSenderAccountId().equals(request.getSenderAccountId())
                && existing.getReceiverAccountId().equals(request.getReceiverAccountId())
                && existing.getAmount().compareTo(request.getAmount()) == 0;
    }

    private String extractErrorMessage(FeignException e) {

        try {
            String responseBody = e.contentUTF8();
            if (responseBody != null && !responseBody.isBlank()) {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> errorMap = mapper.readValue(responseBody, Map.class);
                if (errorMap.containsKey("error")) {
                    return errorMap.get("error").toString();
                }
            }
        } catch (Exception parseException) {
            // JSON parse edilemezse, aşağıdaki genel mesajlara düşüyoruz
        }

        if (e.status() == 404) {
            return "Hesap bulunamadı";
        } else if (e.status() == 403) {
            return "Bu hesap üzerinde işlem yapma yetkiniz yok";
        } else if (e.status() >= 500) {
            return "Hesap servisi şu anda yanıt vermiyor";
        } else {
            return "İşlem reddedildi";
        }
    }

    private TransactionResponse toResponse(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getSenderAccountId(),
                transaction.getReceiverAccountId(),
                transaction.getAmount(),
                transaction.getStatus(),
                transaction.getCreatedAt()
        );
    }
}