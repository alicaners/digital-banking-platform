package com.banking.customer.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customerServiceOpenAPI() {
        Server localServer = new Server();
        localServer.setUrl("http://localhost:8082");
        localServer.setDescription("Local ortam");

        return new OpenAPI()
                .info(new Info()
                        .title("Customer Service API")
                        .description("Müşteri kayıtlarının yönetildiği servis")
                        .version("1.0"))
                .servers(List.of(localServer));
    }
}