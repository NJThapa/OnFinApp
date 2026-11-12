package com.onfinapp.api.price;

import java.time.Clock;

import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

@Configuration
@EnableConfigurationProperties(PriceConfig.PriceProperties.class)
public class PriceConfig {

    /**
     * Price-data settings (price.* in application.properties).
     *
     * @param provider "mock" generates invented prices from the fixtures in the shared folder
     * @param sharedDir folder shared by both APIs (app/api/shared): mock fixtures now, LLM prompts later
     * @param instrumentSyncIntervalHours how often the instrument list is copied from the provider
     */
    @Validated
    @ConfigurationProperties("price")
    public record PriceProperties(
            @NotBlank String provider,
            @NotBlank String sharedDir,
            @Positive long instrumentSyncIntervalHours) {
    }

    @Bean
    PriceProvider priceProvider(PriceProperties properties, ObjectMapper json, Clock clock) {
        LoggerFactory.getLogger(PriceConfig.class).info("Using the {} price provider", properties.provider());
        return switch (properties.provider()) {
            case "mock" -> MockPriceProvider.fromSharedFolder(properties.sharedDir(), json, clock);
            default -> throw new IllegalStateException("Unknown PRICE_PROVIDER '" + properties.provider() + "'.");
        };
    }
}
