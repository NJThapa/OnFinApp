package com.onfinapp.api.llm;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

@Configuration
@EnableConfigurationProperties(LlmConfig.LlmProperties.class)
public class LlmConfig {

    /**
     * LLM settings (llm.* in application.properties).
     *
     * @param provider "mock" answers from fixed rules; no API key needed
     * @param sharedDir folder shared by both APIs (app/api/shared); prompts and schemas are in its llm folder
     * @param mockDelayMs how long the mock waits before answering, so the "generating" state is visible
     * @param mockFailSymbols comma-separated tickers for which the mock fails
     */
    @Validated
    @ConfigurationProperties("llm")
    public record LlmProperties(
            @NotBlank String provider,
            @NotBlank String sharedDir,
            @PositiveOrZero long mockDelayMs,
            @NotNull String mockFailSymbols) {
    }

    @Bean
    LlmClient llmClient(LlmProperties properties, Clock clock) {
        LoggerFactory.getLogger(LlmConfig.class).info("Using the {} LLM", properties.provider());
        return switch (properties.provider()) {
            case "mock" -> {
                Set<String> failSymbols = Arrays.stream(properties.mockFailSymbols().split(","))
                        .map(String::trim)
                        .filter(symbol -> !symbol.isEmpty())
                        .map(symbol -> symbol.toUpperCase(Locale.ROOT))
                        .collect(Collectors.toSet());
                yield new MockLlmClient(clock, Duration.ofMillis(properties.mockDelayMs()), failSymbols);
            }
            default -> throw new IllegalStateException("Unknown LLM_PROVIDER '" + properties.provider() + "'.");
        };
    }

    @Bean
    PromptTemplate analysisPrompt(LlmProperties properties) {
        return PromptTemplate.load(properties.sharedDir(), "analysis");
    }
}
