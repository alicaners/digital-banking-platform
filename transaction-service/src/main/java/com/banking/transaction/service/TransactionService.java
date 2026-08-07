package com.banking.transaction.service;

import com.banking.transaction.client.AccountServiceClient;
import com.banking.transaction.dto.AmountRequest;
import com.banking.transaction.dto.TransactionResponse;
import com.banking.transaction.dto.TransferRequest;
import com.banking.transaction.entity.Transaction;
import com.banking.transaction.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

        } catch (Exception e) {

            if (withdrawSucceeded) {
                boolean compensationSucceeded = compensate(request);
                transaction.setStatus(compensationSucceeded ? "REVERSED" : "FAILED");
            } else {
                transaction.setStatus("FAILED");
            }
        }

        transactionRepository.save(transaction);

        return toResponse(transaction);
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