package com.banking.transaction.service;

import com.banking.transaction.client.AccountServiceClient;
import com.banking.transaction.dto.AmountRequest;
import com.banking.transaction.dto.TransactionResponse;
import com.banking.transaction.dto.TransferRequest;
import com.banking.transaction.entity.Transaction;
import com.banking.transaction.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import feign.FeignException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import com.banking.transaction.event.TransactionEvent;
import com.banking.transaction.kafka.TransactionEventProducer;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import java.net.ConnectException;
import com.banking.transaction.dto.AccountResponse;

@Service
public class TransactionService {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountServiceClient accountServiceClient;

    @Autowired
    private TransactionEventProducer eventProducer;

    public TransactionResponse transfer(TransferRequest request, Long userId) {

        Transaction transaction = new Transaction();
        transaction.setSenderAccountId(request.getSenderAccountId());
        transaction.setReceiverAccountId(request.getReceiverAccountId());
        transaction.setAmount(request.getAmount());

        boolean withdrawSucceeded = false;
        String failureReason = null;

        try {
            withdrawWithRetry(request.getSenderAccountId(), new AmountRequest(request.getAmount()), userId);
            withdrawSucceeded = true;

            accountServiceClient.deposit(
                    request.getReceiverAccountId(),
                    new AmountRequest(request.getAmount()),
                    userId
            );

            transaction.setStatus("COMPLETED");

        } catch (FeignException e) {

            failureReason = extractErrorMessage(e);

            if (withdrawSucceeded) {
                boolean compensationSucceeded = compensate(request, userId);
                transaction.setStatus(compensationSucceeded ? "REVERSED" : "FAILED");
            } else {
                transaction.setStatus("FAILED");
            }

        } catch (RuntimeException e) {

            failureReason = (e.getMessage() != null)
                    ? e.getMessage()
                    : "Hesap servisi şu anda kullanılamıyor";

            if (withdrawSucceeded) {
                boolean compensationSucceeded = compensate(request, userId);
                transaction.setStatus(compensationSucceeded ? "REVERSED" : "FAILED");
            } else {
                transaction.setStatus("FAILED");
            }

        } catch (Exception e) {

            failureReason = "Beklenmeyen bir hata oluştu: " + e.getMessage();

            if (withdrawSucceeded) {
                boolean compensationSucceeded = compensate(request, userId);
                transaction.setStatus(compensationSucceeded ? "REVERSED" : "FAILED");
            } else {
                transaction.setStatus("FAILED");
            }
        }

        transactionRepository.save(transaction);

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

    @Retryable(
            retryFor = {ConnectException.class, java.io.IOException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 500)
    )
    public AccountResponse withdrawWithRetry(Long accountId, AmountRequest request, Long userId) {
        return accountServiceClient.withdraw(accountId, request, userId);
    }

    private boolean compensate(TransferRequest request, Long userId) {
        try {
            accountServiceClient.deposit(
                    request.getSenderAccountId(),
                    new AmountRequest(request.getAmount()),
                    userId
            );
            return true;
        } catch (Exception e) {
            return false;
        }
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