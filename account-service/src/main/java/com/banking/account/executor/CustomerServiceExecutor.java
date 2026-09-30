package com.banking.account.executor;

import com.banking.account.exception.NonRetryableException;
import feign.FeignException;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

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
        return retryTemplate.execute(context ->
                circuitBreaker.run(() -> callAndClassify(call), throwable -> {
                    if (throwable instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new RuntimeException("Müşteri servisi şu anda kullanılamıyor", throwable);
                })
        );
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