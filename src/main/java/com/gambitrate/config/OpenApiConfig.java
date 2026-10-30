package com.gambitrate.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI gambitRateOpenApi() {
        return new OpenAPI().info(new Info()
                .title("GambitRate API")
                .version("v1")
                .description("Ingests a chess.com player's public games and finds what is costing them rating."));
    }
}
