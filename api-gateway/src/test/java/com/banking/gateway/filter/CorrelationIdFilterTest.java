package com.banking.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    private MockServerWebExchange run(String incomingHeader, AtomicReference<ServerWebExchange> forwarded) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/customers");
        if (incomingHeader != null) {
            builder.header(CorrelationIdFilter.HEADER, incomingHeader);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(builder);
        filter.filter(exchange, ex -> {
            forwarded.set(ex);
            return Mono.empty();
        }).block();
        return exchange;
    }

    @Test
    void headerYoksa_yeniUuidUretilir() {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        MockServerWebExchange exchange = run(null, forwarded);

        String id = forwarded.get().getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER);
        assertThat(id).isNotNull();
        UUID.fromString(id); // geçerli bir UUID değilse exception fırlatır
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.HEADER)).isEqualTo(id);
    }

    @Test
    void gecerliHeader_aynenKorunur() {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        MockServerWebExchange exchange = run("abc12345-test", forwarded);

        assertThat(forwarded.get().getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER))
                .isEqualTo("abc12345-test");
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.HEADER))
                .isEqualTo("abc12345-test");
    }

    @Test
    void supheliHeader_yeniUuidIleDegistirilir() {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        run("kotu deger!\nsahte-log-satiri", forwarded);

        String id = forwarded.get().getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER);
        assertThat(id).doesNotContain("kotu").doesNotContain("\n");
        UUID.fromString(id);
    }
}