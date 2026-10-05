package com.banking.transaction.executor;

import com.banking.transaction.exception.NonRetryableException;
import feign.FeignException;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

@Component
public class AccountServiceExecutor {

    private static final String INSTANCE_NAME = "accountService";

    private final CircuitBreaker circuitBreaker;
    private final RetryTemplate retryTemplate;

    @Autowired
    public AccountServiceExecutor(CircuitBreakerFactory circuitBreakerFactory, RetryTemplate retryTemplate) {
        this.circuitBreaker = circuitBreakerFactory.create(INSTANCE_NAME);
        this.retryTemplate = retryTemplate;
    }

    public <T> T execute(Supplier<T> call) {
        // Resilience4J TimeLimiter çağrıyı başka bir thread'de çalıştırır.
        // MDC thread'e özel olduğu için correlationId'yi burada (istek thread'inde) kopyalayıp aktarıyoruz.
        Map<String, String> callerMdc = MDC.getCopyOfContextMap();

        return retryTemplate.execute(context ->
                circuitBreaker.run(() -> callWithMdc(callerMdc, call), throwable -> {
                    if (throwable instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new RuntimeException("Hesap servisi şu anda kullanılamıyor", throwable);
                })
        );
    }

    private <T> T callWithMdc(Map<String, String> callerMdc, Supplier<T> call) {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        if (callerMdc != null) {
            MDC.setContextMap(callerMdc);
        }
        try {
            return callAndClassify(call);
        } finally {
            // Havuzdaki thread bir sonraki isteğe başka bir correlationId ile gitmesin
            if (previousMdc != null) {
                MDC.setContextMap(previousMdc);
            } else {
                MDC.clear();
            }
        }
    }

    private <T> T callAndClassify(Supplier<T> call) {
        try {
            return call.get();
        } catch (FeignException e) {
            if (e.status() >= 400 && e.status() < 500) {
                // İş kuralı hatası (yetersiz bakiye, yetkisiz, hesap bulunamadı vb.)
                // Retry etmek anlamsız çünkü sonuç değişmeyecek.
                throw new NonRetryableException(e);
            }
            // 5xx (sunucu hatası) veya bağlantı sorunu — retry edilebilir, olduğu gibi fırlat.
            throw e;
        }
    }
}