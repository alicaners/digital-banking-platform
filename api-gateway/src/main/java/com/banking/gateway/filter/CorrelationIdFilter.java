package com.banking.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-Correlation-Id";

    // Log injection'a karşı: sadece harf, rakam ve tire, 8-64 karakter
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String correlationId = (incoming != null && SAFE_ID.matcher(incoming).matches())
                ? incoming
                : UUID.randomUUID().toString();

        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(h -> h.set(HEADER, correlationId)))
                .build();

        // Yanıt istemciye gönderilmeden hemen önce header'ı set ediyoruz. Bu an, servisin
        // kendi eklediği aynı header'ın da yanıta geçtiği andan sonradır; set() hepsini
        // tek değere indirir. 401/429 gibi erken dönen yanıtlar da bu aşamadan geçer.
        mutated.getResponse().beforeCommit(() -> {
            mutated.getResponse().getHeaders().set(HEADER, correlationId);
            return Mono.empty();
        });

        String method = mutated.getRequest().getMethod().name();
        String path = mutated.getRequest().getURI().getRawPath();
        long start = System.currentTimeMillis();

        return chain.filter(mutated).doFinally(signal -> {
            Integer status = mutated.getResponse().getStatusCode() != null
                    ? mutated.getResponse().getStatusCode().value()
                    : null;
            log.info("{} {} -> {} ({} ms) {}",
                    method, path, status, System.currentTimeMillis() - start,
                    keyValue("correlationId", correlationId));
        });
    }

    @Override
    public int getOrder() {
        return -3;
    }
}