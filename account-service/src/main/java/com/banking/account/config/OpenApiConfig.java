package com.banking.account.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI accountServiceOpenAPI() {
        Server localServer = new Server();
        localServer.setUrl("http://localhost:8083");
        localServer.setDescription("Local ortam");

        return new OpenAPI()
                .info(new Info()
                        .title("Account Service API")
                        .description("Hesap açma, bakiye sorgulama ve para transferi işlemlerini yöneten servis")
                        .version("1.0"))
                .servers(List.of(localServer));
    }
}