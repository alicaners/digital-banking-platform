package com.banking.transaction.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI transactionServiceOpenAPI() {
        Server localServer = new Server();
        localServer.setUrl("http://localhost:8084");
        localServer.setDescription("Local ortam");

        return new OpenAPI()
                .info(new Info()
                        .title("Transaction Service API")
                        .description("Para transferi ve Saga Pattern ile distributed transaction yönetimi")
                        .version("1.0"))
                .servers(List.of(localServer));
    }
}