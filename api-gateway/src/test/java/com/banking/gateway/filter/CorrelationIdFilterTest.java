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
        // Header, yanıt tamamlanırken (beforeCommit) eklendiği için yanıtı tamamlıyoruz
        exchange.getResponse().setComplete().block();
        return exchange;
    }

    @Test
    void noHeader_generatesNewUuid() {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        MockServerWebExchange exchange = run(null, forwarded);

        String id = forwarded.get().getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER);
        assertThat(id).isNotNull();
        UUID.fromString(id); // geçerli bir UUID değilse exception fırlatır
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.HEADER)).isEqualTo(id);
    }

    @Test
    void validHeader_isKeptAsIs() {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        MockServerWebExchange exchange = run("abc12345-test", forwarded);

        assertThat(forwarded.get().getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER))
                .isEqualTo("abc12345-test");
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.HEADER))
                .isEqualTo("abc12345-test");
    }

    @Test
    void suspiciousHeader_isReplacedWithNewUuid() {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        run("kotu deger!\nsahte-log-satiri", forwarded);

        String id = forwarded.get().getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER);
        assertThat(id).doesNotContain("kotu").doesNotContain("\n");
        UUID.fromString(id);
    }

    @Test
    void downstreamAddsSameHeader_responseKeepsSingleValue() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/login")
                        .header(CorrelationIdFilter.HEADER, "abc12345-test"));

        filter.filter(exchange, ex -> {
            // Arkadaki servis de aynı header'ı yanıta eklemiş gibi davranıyoruz
            ex.getResponse().getHeaders().add(CorrelationIdFilter.HEADER, "abc12345-test");
            return Mono.empty();
        }).block();
        exchange.getResponse().setComplete().block();

        assertThat(exchange.getResponse().getHeaders().get(CorrelationIdFilter.HEADER))
                .containsExactly("abc12345-test");
    }
}