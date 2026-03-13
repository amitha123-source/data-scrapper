package com.sample.data_scrapper.config;

import com.google.genai.Client;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Application configuration for Gemini AI (Google Gen AI SDK with API key).
 * DataSource is provided by Spring Boot auto-configuration from application.yaml.
 */
@Configuration
public class AppConfig {

    @Value("${GEMINI_API_KEY:${google.ai.api-key:}}")
    private String geminiApiKey;

    /**
     * Gemini client for calling gemini-2.5-flash (or configured model).
     * Uses API key from GEMINI_API_KEY env or google.ai.api-key in application.yaml.
     */
    @Bean
    public Client geminiClient() {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            System.err.println("\n\u274C NO GEMINI API KEY — set GEMINI_API_KEY or google.ai.api-key\n");
        }
        return Client.builder()
                .apiKey(geminiApiKey != null ? geminiApiKey : "")
                .build();
    }
}
