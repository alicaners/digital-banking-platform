package com.banking.transaction.config;

import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class FeignCorrelationIdInterceptorTest {

    private final FeignCorrelationIdInterceptor interceptor = new FeignCorrelationIdInterceptor();

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    @Test
    void mdcHasCorrelationId_headerIsAddedToOutgoingRequest() {
        MDC.put("correlationId", "transfer-test-0006");
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers().get("X-Correlation-Id")).containsExactly("transfer-test-0006");
    }

    @Test
    void mdcEmpty_noHeaderIsAdded() {
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers()).doesNotContainKey("X-Correlation-Id");
    }
}