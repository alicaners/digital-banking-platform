package com.banking.transaction.config;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    private String runAndCaptureMdc(MockHttpServletRequest request, MockHttpServletResponse response)
            throws Exception {
        AtomicReference<String> mdcInsideChain = new AtomicReference<>();
        filter.doFilter(request, response,
                (req, res) -> mdcInsideChain.set(MDC.get(CorrelationIdFilter.MDC_KEY)));
        return mdcInsideChain.get();
    }

    @Test
    void noHeader_generatesUuid_populatesMdc_clearsMdcAfterRequest() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        String id = runAndCaptureMdc(new MockHttpServletRequest(), response);

        assertThat(id).isNotNull();
        UUID.fromString(id);
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(id);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void validHeader_isKeptAsIs() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "abc12345-test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String id = runAndCaptureMdc(request, response);

        assertThat(id).isEqualTo("abc12345-test");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc12345-test");
    }

    @Test
    void suspiciousHeader_isReplacedWithNewUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "kotu deger!\nsahte-log-satiri");

        String id = runAndCaptureMdc(request, new MockHttpServletResponse());

        assertThat(id).doesNotContain("kotu").doesNotContain("\n");
        UUID.fromString(id);
    }
}