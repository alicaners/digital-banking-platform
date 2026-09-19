package com.banking.notification.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI notificationServiceOpenAPI() {
        Server localServer = new Server();
        localServer.setUrl("http://localhost:8085");
        localServer.setDescription("Local ortam");

        return new OpenAPI()
                .info(new Info()
                        .title("Notification Service API")
                        .description("Kafka üzerinden gelen işlem olaylarını dinleyen bildirim servisi")
                        .version("1.0"))
                .servers(List.of(localServer));
    }
}