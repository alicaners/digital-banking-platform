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

@Service
public class TransactionService {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountServiceClient accountServiceClient;

    public TransactionResponse transfer(TransferRequest request) {

        Transaction transaction = new Transaction();
        transaction.setSenderAccountId(request.getSenderAccountId());
        transaction.setReceiverAccountId(request.getReceiverAccountId());
        transaction.setAmount(request.getAmount());

        boolean withdrawSucceeded = false;
        String failureReason = null;

        try {
            accountServiceClient.withdraw(
                    request.getSenderAccountId(),
                    new AmountRequest(request.getAmount())
            );
            withdrawSucceeded = true;

            accountServiceClient.deposit(
                    request.getReceiverAccountId(),
                    new AmountRequest(request.getAmount())
            );

            transaction.setStatus("COMPLETED");

        } catch (FeignException e) {

            failureReason = extractErrorMessage(e);

            if (withdrawSucceeded) {
                boolean compensationSucceeded = compensate(request);
                transaction.setStatus(compensationSucceeded ? "REVERSED" : "FAILED");
            } else {
                transaction.setStatus("FAILED");
            }

        } catch (Exception e) {

            failureReason = "Beklenmeyen bir hata oluştu: " + e.getMessage();

            if (withdrawSucceeded) {
                boolean compensationSucceeded = compensate(request);
                transaction.setStatus(compensationSucceeded ? "REVERSED" : "FAILED");
            } else {
                transaction.setStatus("FAILED");
            }
        }

        transactionRepository.save(transaction);

        TransactionResponse response = toResponse(transaction);
        if (failureReason != null) {
            response.setFailureReason(failureReason);
        }
        return response;
    }

    private boolean compensate(TransferRequest request) {
        try {
            accountServiceClient.deposit(
                    request.getSenderAccountId(),
                    new AmountRequest(request.getAmount())
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