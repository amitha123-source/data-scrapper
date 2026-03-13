package com.sample.data_scrapper.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DTO representing a single row of product data read from a CSV file.
 * Common product fields are exposed; any extra CSV columns are available via {@link #getRawRow()}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductDataDto {

    private String productId;
    private String status;
    private String name;
    private String price;
    private String category;
    private String brand;
    private String description;
    private String productUrl;
    private String taskLink;
    private String sku;

    /**
     * Product details restructured by LLM according to product features (e.g. bullet points).
     * Populated after calling the Gemini restructure service.
     */
    private String restructuredProductDetails;

    /**
     * All CSV column values as read from the file (header name -> value).
     * Useful when the CSV has columns not mapped to the typed fields above.
     */
    @Builder.Default
    private Map<String, String> rawRow = new LinkedHashMap<>();

    /**
     * Source file name this row was read from (set by the parser/listener).
     */
    private String sourceFileName;
}
