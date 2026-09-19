package com.banking.auth.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI authServiceOpenAPI() {
        Server localServer = new Server();
        localServer.setUrl("http://localhost:8081");
        localServer.setDescription("Local ortam");

        return new OpenAPI()
                .info(new Info()
                        .title("Auth Service API")
                        .description("Kullanıcı kaydı, giriş ve JWT token üretimi")
                        .version("1.0"))
                .servers(List.of(localServer));
    }
}