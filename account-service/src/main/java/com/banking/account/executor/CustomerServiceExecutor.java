package com.banking.account.executor;

import com.banking.account.exception.NonRetryableException;
import feign.FeignException;
import org.slf4j.MDC;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

@Component
public class CustomerServiceExecutor {

    private static final String INSTANCE_NAME = "customerService";

    private final CircuitBreaker circuitBreaker;
    private final RetryTemplate retryTemplate;

    public CustomerServiceExecutor(CircuitBreakerFactory circuitBreakerFactory, RetryTemplate retryTemplate) {
        this.circuitBreaker = circuitBreakerFactory.create(INSTANCE_NAME);
        this.retryTemplate = retryTemplate;
    }

    public <T> T execute(Supplier<T> call) {
        // TimeLimiter çağrıyı başka bir thread'de çalıştırır; MDC'yi (correlationId) o thread'e aktarıyoruz.
        Map<String, String> callerMdc = MDC.getCopyOfContextMap();

        return retryTemplate.execute(context ->
                circuitBreaker.run(() -> callWithMdc(callerMdc, call), throwable -> {
                    if (throwable instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new RuntimeException("Müşteri servisi şu anda kullanılamıyor", throwable);
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
                throw new NonRetryableException(e);
            }
            throw e;
        }
    }
}