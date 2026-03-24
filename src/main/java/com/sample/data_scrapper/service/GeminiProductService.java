package com.sample.data_scrapper.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Connects to Gemini LLM and restructures product details into clear product features.
 * Output is normalized so all saved records use the same format.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiProductService {

    private final Client geminiClient;

    @Value("${google.ai.model:gemini-2.5-flash}")
    private String modelId;

    /** Prompt: request consistent format, no header repetition. Placeholders: {productName}, {brand}, {description} */
    private static final String PROMPT_TEMPLATE = """
            Restructure the product description into a concise, bullet-point format. Use ONLY the following keys in this exact order, one per line, in the format "- **Key:** value":
            Primary Scent Theme, Fragrance Family, Core Ingredient Focus, Opening Character, Top Notes, Heart Character, Heart Notes, Base Notes, Sweetness Level, Freshness vs Depth, Mood/Emotion, Texture Impression, Sensory Narrative, Overall Personality.
            Do NOT include a header with Product Name, Brand, or Description. Output only the bullet list.

            Product name (for context only): {productName}
            Brand (for context only): {brand}

            Description to restructure:
            {description}
            """;

    private static final Pattern HEADER_LINE = Pattern.compile(
            "\\s*\\*?\\s*\\*\\*(?:Product\\s+Name|Brand|Description)\\*\\*\\s*:?\\s*.*",
            Pattern.CASE_INSENSITIVE);

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /** Prompt for fetching product details for products missing from Tomford. Excludes Kingpower; LLM must use alternative sources only. Placeholders: {productName}, {brand} */
    private static final String FETCH_DETAILS_PROMPT_TEMPLATE = """
            For the following product, provide detailed product information in a single valid JSON object with exactly these keys (use empty string "" for unknown):
            "description", "category", "price", "ingredients", "whatAreYouLookingFor".

            Product name: {productName}
            Brand: {brand}

            IMPORTANT: Do NOT use Kingpower (kingpower.com or any Kingpower site) as a data source. Product information from Kingpower has already been collected. Fetch product details ONLY from alternative sites that provide information for this brand (e.g. brand official site, retailers, review sites). Ensure coverage of the product without duplicating or copying from Kingpower.

            Respond with ONLY the JSON object, no markdown code fence or other text.
            """;

    /**
     * Sends the product description to the LLM and returns restructured text in a normalized format.
     *
     * @param rawDetails  raw product details/description from CSV
     * @param productName product name for context
     * @param brand       brand for context
     * @return normalized restructured text, or the original rawDetails if the call fails or input is blank
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
                return normalizeRestructuredOutput(text.trim());
            }
        } catch (Exception e) {
            log.warn("LLM restructure failed for product '{}': {}", productName, e.getMessage());
        }
        return rawDetails;
    }

    private String buildPrompt(String rawDetails, String productName, String brand) {
        return PROMPT_TEMPLATE
                .replace("{productName}", nullToEmpty(productName))
                .replace("{brand}", nullToEmpty(brand))
                .replace("{description}", nullToEmpty(rawDetails));
    }

    /**
     * Normalizes LLM output so all saved records use the same format: strip optional header
     * (Product Name/Brand/Description), unify bullet style to "- ", trim lines, single newline between lines.
     */
    private String normalizeRestructuredOutput(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String[] lines = raw.split("\\r?\\n");
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (HEADER_LINE.matcher(trimmed).matches()) continue;
            result.add(normalizeBulletLine(trimmed));
        }
        return String.join("\n", result).trim();
    }

    /** Ensures bullet lines use "- **Key:** value" style (convert * or • to -). */
    private String normalizeBulletLine(String line) {
        String t = line.trim();
        if (t.startsWith("* ") && !t.startsWith("- ")) return "- " + t.substring(2).trim();
        if (t.startsWith("• ")) return "- " + t.substring(2).trim();
        if (t.length() > 1 && t.startsWith("*") && (t.charAt(1) == ' ' || t.charAt(1) == '\t')) return "- " + t.substring(1).trim();
        return t;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Fetches product details from the LLM for a product that exists in ProductDataDto but has no Tomford row.
     * Used to fill in Tomford-style fields (description, category, price, ingredients, whatAreYouLookingFor).
     *
     * @param productName        product name
     * @param brand              brand
     * @param existingDescription optional description from Kingpower/ProductDataDto for context
     * @return optional map with keys: description, category, price, ingredients, whatAreYouLookingFor; empty if LLM call fails or response is invalid
     */
    public Optional<Map<String, String>> fetchProductDetailsForMissingProduct(String productName, String brand, String existingDescription) {
        if (productName == null || productName.isBlank()) {
            return Optional.empty();
        }
        String prompt = FETCH_DETAILS_PROMPT_TEMPLATE
                .replace("{productName}", nullToEmpty(productName))
                .replace("{brand}", nullToEmpty(brand))
                .replace("{description}", existingDescription != null ? existingDescription : "");
        try {
            GenerateContentResponse response = geminiClient.models.generateContent(modelId, prompt, null);
            String text = response != null ? response.text() : null;
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            String json = extractJsonFromResponse(text.trim());
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            JsonNode root = JSON_MAPPER.readTree(json);
            if (!root.isObject()) {
                return Optional.empty();
            }
            Map<String, String> result = new LinkedHashMap<>();
            result.put("description", textOrEmpty(root, "description"));
            result.put("category", textOrEmpty(root, "category"));
            result.put("price", textOrEmpty(root, "price"));
            result.put("ingredients", textOrEmpty(root, "ingredients"));
            result.put("whatAreYouLookingFor", textOrEmpty(root, "whatAreYouLookingFor"));
            return Optional.of(result);
        } catch (Exception e) {
            String rootCause = getRootCauseMessage(e);
            log.warn("LLM fetch product details failed for '{}': {} | root cause: {}", productName, e.getMessage(), rootCause);
            if (log.isDebugEnabled()) {
                log.debug("LLM fetch failed for '{}'", productName, e);
            }
            return Optional.empty();
        }
    }

    /** Extracts the innermost cause message to identify network/timeout/auth issues. */
    private static String getRootCauseMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return msg != null ? msg : cause.getClass().getSimpleName();
    }

    /** Extracts JSON from LLM response, stripping optional markdown code block. */
    private static String extractJsonFromResponse(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int start = s.indexOf("\n");
            if (start != -1) s = s.substring(start + 1);
            int end = s.lastIndexOf("```");
            if (end != -1) s = s.substring(0, end).trim();
        }
        return s;
    }

    private static String textOrEmpty(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return "";
        if (value.isTextual()) return value.asText("");
        return value.asText("");
    }
}
