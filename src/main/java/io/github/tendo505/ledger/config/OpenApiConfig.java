package io.github.tendo505.ledger.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI api() {
        return new OpenAPI().info(new Info()
                .title("Ledger")
                .version("1.0.0")
                .description("A double-entry payments ledger: accounts, idempotent transfers, holds, signed webhooks and a self-audit. "
                        + "Amounts are integers in minor units (cents).")
                .license(new License().name("MIT")));
    }
}
