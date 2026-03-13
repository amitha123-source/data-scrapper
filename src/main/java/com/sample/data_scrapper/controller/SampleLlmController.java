package com.sample.data_scrapper.controller;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Sample controller that demonstrates connecting to the LLM (Gemini) and sending a prompt.
 * Use this as a reference and adjust the prompt as needed.
 *
 * Example: GET /api/sample/llm?prompt=What are the main benefits of vitamin C?
 */
@RestController
@RequestMapping("/api/sample")
@RequiredArgsConstructor
public class SampleLlmController {

    private final Client geminiClient;

    @Value("${google.ai.model:gemini-2.5-flash}")
    private String modelId;

    /**
     * Sends a custom prompt to the LLM and returns the generated text.
     * This is sample code showing how to use the Gemini client.
     */
    @GetMapping(value = "/llm", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> callLlm(
            @RequestParam(defaultValue = "In one sentence, what is Java?") String prompt) {
        try {
            GenerateContentResponse response = geminiClient.models.generateContent(modelId, prompt, null);
            String text = response != null ? response.text() : "";
            return ResponseEntity.ok(Map.of(
                    "model", modelId,
                    "prompt", prompt,
                    "response", text != null ? text : ""
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "error", e.getMessage() != null ? e.getMessage() : "LLM call failed",
                            "prompt", prompt
                    ));
        }
    }
}
