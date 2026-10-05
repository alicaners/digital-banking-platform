package com.banking.transaction.service;

import com.banking.transaction.client.AccountServiceClient;
import com.banking.transaction.dto.AccountResponse;
import com.banking.transaction.dto.InternalTransferRequest;
import com.banking.transaction.dto.TransactionResponse;
import com.banking.transaction.dto.TransferRequest;
import com.banking.transaction.entity.Transaction;
import com.banking.transaction.entity.TransactionStatus;
import com.banking.transaction.event.TransactionEvent;
import com.banking.transaction.exception.IdempotencyConflictException;
import com.banking.transaction.exception.NonRetryableException;
import com.banking.transaction.executor.AccountServiceExecutor;
import com.banking.transaction.kafka.TransactionEventProducer;
import com.banking.transaction.repository.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;
    private final AccountServiceExecutor accountServiceExecutor;
    private final TransactionEventProducer eventProducer;

    public TransactionService(TransactionRepository transactionRepository,
                              AccountServiceClient accountServiceClient,
                              AccountServiceExecutor accountServiceExecutor,
                              TransactionEventProducer eventProducer) {
        this.transactionRepository = transactionRepository;
        this.accountServiceClient = accountServiceClient;
        this.accountServiceExecutor = accountServiceExecutor;
        this.eventProducer = eventProducer;
    }

    public TransactionResponse transfer(TransferRequest request, Long userId, String idempotencyKey) {

        if (request.getSenderAccountId().equals(request.getReceiverAccountId())) {
            throw new IllegalArgumentException("Gönderen ve alıcı hesap aynı olamaz");
        }

        Optional<Transaction> existing = transactionRepository.findByIdempotencyKeyAndUserId(idempotencyKey, userId);
        if (existing.isPresent()) {
            Transaction existingTransaction = existing.get();
            if (!matchesRequest(existingTransaction, request)) {
                throw new IdempotencyConflictException(
                        "Bu Idempotency-Key daha önce farklı bir işlem için kullanılmıs, aynı key ile farklı bir transfer yapamazsınız"
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

            transaction.setStatus(TransactionStatus.COMPLETED);

        } catch (FeignException e) {

            log.warn("Account Service çağrısı Feign hatasıyla başarısız oldu: status={}, mesaj={}",
                    e.status(), e.getMessage());
            transaction.setFailureReason(extractErrorMessage(e));
            transaction.setStatus(TransactionStatus.FAILED);

        } catch (NonRetryableException e) {

            log.info("Account Service iş kuralı nedeniyle işlemi reddetti: {}", e.getMessage());
            transaction.setFailureReason(
                    (e.getCause() instanceof FeignException fe)
                            ? extractErrorMessage(fe)
                            : e.getMessage()
            );
            transaction.setStatus(TransactionStatus.FAILED);

        } catch (CallNotPermittedException e) {

            log.warn("Circuit breaker açık, Account Service çağrısı yapılmadı");
            transaction.setFailureReason("Hesap servisi şu anda geçici olarak kullanılamıyor, lütfen birazdan tekrar deneyin");
            transaction.setStatus(TransactionStatus.FAILED);

        } catch (RuntimeException e) {

            // Stack trace bilerek yazdırılıyor: asıl neden (cause) burada görünecek
            log.warn("Account Service çağrısı beklenmeyen bir hatayla başarısız oldu", e);
            transaction.setFailureReason(
                    (e.getMessage() != null)
                            ? e.getMessage()
                            : "Hesap servisi şu anda kullanılamıyor"
            );
            transaction.setStatus(TransactionStatus.FAILED);

        } catch (Exception e) {

            log.error("Account Service çağrısında beklenmeyen hata", e);
            transaction.setFailureReason("Beklenmeyen bir hata oluştu: " + e.getMessage());
            transaction.setStatus(TransactionStatus.FAILED);
        }

        try {
            transactionRepository.save(transaction);
        } catch (DataIntegrityViolationException e) {
            Transaction raceWinner = transactionRepository.findByIdempotencyKeyAndUserId(idempotencyKey, userId)
                    .orElseThrow(() -> e);
            if (!matchesRequest(raceWinner, request)) {
                throw new IdempotencyConflictException(
                        "Bu Idempotency-Key daha once farklı bir işlem için kullanılmış, aynı key ile farklı bir transfer yapamazsınız"
                );
            }
            return toResponse(raceWinner);
        }

        try {
            eventProducer.publish(new TransactionEvent(
                    transaction.getId(),
                    transaction.getSenderAccountId(),
                    transaction.getReceiverAccountId(),
                    transaction.getAmount(),
                    transaction.getStatus().name()
            ));
        } catch (Exception e) {
            log.error("Transaction id={} için Kafka event yayınlanamadı, işlem DB'de kayıtlı ama bildirim gitmedi: {}",
                    transaction.getId(), e.getMessage());
        }

        return toResponse(transaction);
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
        TransactionResponse response = new TransactionResponse(
                transaction.getId(),
                transaction.getSenderAccountId(),
                transaction.getReceiverAccountId(),
                transaction.getAmount(),
                transaction.getStatus(),
                transaction.getCreatedAt()
        );
        response.setFailureReason(transaction.getFailureReason());
        return response;
    }
}