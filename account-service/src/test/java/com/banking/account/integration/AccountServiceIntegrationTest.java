package com.banking.account.integration;

import com.banking.account.dto.AccountRequest;
import com.banking.account.dto.AccountResponse;
import com.banking.account.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class AccountServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("account_test_db")
            .withUsername("test_user")
            .withPassword("test_pass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AccountService accountService;

    private static final Long TEST_USER_ID = 1L;

    @Test
    void openAccount_savesAndRetrievesFromRealDatabase() {

        AccountRequest request = new AccountRequest();
        request.setCustomerId(1L);
        request.setCurrency("TRY");

        AccountResponse created = accountService.openAccount(request, TEST_USER_ID);

        assertNotNull(created.getId());
        assertEquals(0, BigDecimal.ZERO.compareTo(created.getBalance()));
        assertNotNull(created.getIban());

        AccountResponse fetched = accountService.getAccountById(created.getId(), TEST_USER_ID, "CUSTOMER");
        assertEquals(created.getId(), fetched.getId());
        assertEquals("TRY", fetched.getCurrency());
    }

    @Test
    void depositAndWithdraw_persistsBalanceChangesCorrectly() {

        AccountRequest request = new AccountRequest();
        request.setCustomerId(2L);
        request.setCurrency("TRY");
        AccountResponse account = accountService.openAccount(request, TEST_USER_ID);

        accountService.deposit(account.getId(), new BigDecimal("500.00"), TEST_USER_ID);
        accountService.withdraw(account.getId(), new BigDecimal("150.00"), TEST_USER_ID);

        AccountResponse result = accountService.getAccountById(account.getId(), TEST_USER_ID, "CUSTOMER");
        assertEquals(new BigDecimal("350.00"), result.getBalance());
    }
}