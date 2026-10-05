package com.banking.account.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class FeignCorrelationIdInterceptor implements RequestInterceptor {

    private static final String HEADER = "X-Correlation-Id";
    private static final String MDC_KEY = "correlationId";

    @Override
    public void apply(RequestTemplate template) {
        String correlationId = MDC.get(MDC_KEY);
        if (correlationId != null && !correlationId.isBlank()) {
            template.header(HEADER, correlationId);
        }
    }
}