package com.banking.gateway.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {

    private JwtValidator jwtValidator;
    private JwtAuthenticationFilter filter;
    private AtomicReference<ServerWebExchange> passedExchange;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        jwtValidator = mock(JwtValidator.class);
        filter = new JwtAuthenticationFilter(jwtValidator);
        passedExchange = new AtomicReference<>();
        chain = exchange -> {
            passedExchange.set(exchange);
            return Mono.empty();
        };
    }

    private MockServerWebExchange post(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path).build());
    }

    private MockServerWebExchange postWithToken(String path, String token) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.post(path).header("Authorization", "Bearer " + token).build());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/logout"
    })
    void openAuthEndpoints_passWithoutToken(String path) {

        MockServerWebExchange exchange = post(path);

        filter.filter(exchange, chain).block();

        assertNotNull(passedExchange.get(), "İstek zincire devam etmeli");
        assertNull(exchange.getResponse().getStatusCode());
        verifyNoInteractions(jwtValidator);
    }

    @Test
    void protectedEndpoint_withoutToken_returns401() {

        MockServerWebExchange exchange = post("/api/accounts");

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertNull(passedExchange.get(), "İstek zincire gitmemeli");
    }

    @Test
    void protectedEndpoint_withInvalidToken_returns401() {

        when(jwtValidator.isValid("bozuk")).thenReturn(false);
        MockServerWebExchange exchange = postWithToken("/api/accounts", "bozuk");

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertNull(passedExchange.get());
    }

    @Test
    void protectedEndpoint_withValidToken_passesAndAddsUserHeaders() {

        when(jwtValidator.isValid("gecerli")).thenReturn(true);
        when(jwtValidator.getUserId("gecerli")).thenReturn(7L);
        when(jwtValidator.getRole("gecerli")).thenReturn("CUSTOMER");
        MockServerWebExchange exchange = postWithToken("/api/accounts", "gecerli");

        filter.filter(exchange, chain).block();

        ServerWebExchange forwarded = passedExchange.get();
        assertNotNull(forwarded);
        assertEquals("7", forwarded.getRequest().getHeaders().getFirst("X-User-Id"));
        assertEquals("CUSTOMER", forwarded.getRequest().getHeaders().getFirst("X-User-Role"));
    }

    @Test
    void internalEndpoint_isForbiddenEvenWithValidToken() {

        MockServerWebExchange exchange = postWithToken("/api/accounts/internal/transfer", "gecerli");

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertNull(passedExchange.get());
    }
}