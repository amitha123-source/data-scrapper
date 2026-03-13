package com.sample.data_scrapper.service;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Connects to Gemini LLM and restructures product details into clear product features.
 * Adjust the PROMPT_TEMPLATE constant below to change the prompt.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiProductService {

    private final Client geminiClient;

    @Value("${google.ai.model:gemini-2.5-flash}")
    private String modelId;

    /** Prompt template. Placeholders: {productName}, {brand}, {description} */
    private static final String PROMPT_TEMPLATE = """
            Restructure the following product description into clear product features.
            Use bullet points. Include only factual features (e.g. ingredients, benefits, usage, size).
            Keep the output concise.

            Product: {productName}
            Brand: {brand}

            Raw description:
            {description}
            """;

    /**
     * Sends the product description to the LLM and returns restructured text by product features.
     *
     * @param rawDetails  raw product details/description from CSV
     * @param productName product name for context
     * @param brand       brand for context
     * @return restructured text, or the original rawDetails if the call fails or input is blank
     */
    public String restructureProductDetails(String rawDetails, String productName, String brand) {
        if (rawDetails == null || rawDetails.isBlank()) {
            return rawDetails;
        }
        String prompt = buildPrompt(rawDetails, productName, brand);
        try {
            GenerateContentResponse response = geminiClient.models.generateContent(modelId, prompt, null);
            String text = response != null ? response.text() : null;
            if (text != null && !text.isBlank()) {
                return text.trim();
            }
        } catch (Exception e) {
            log.warn("LLM restructure failed for product '{}': {}", productName, e.getMessage());
        }
        log.info("Descrption of the data : {}", rawDetails);
        return rawDetails;
    }

    private String buildPrompt(String rawDetails, String productName, String brand) {
        log.info("Raw Details : {} Product Name : {}  Brand : {}", rawDetails, productName, brand);
        return PROMPT_TEMPLATE
                .replace("{productName}", nullToEmpty(productName))
                .replace("{brand}", nullToEmpty(brand))
                .replace("{description}", nullToEmpty(rawDetails));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
