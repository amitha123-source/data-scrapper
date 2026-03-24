package com.sample.data_scrapper.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DTO representing a single row of Tomford (brand-site) product data from CSV.
 * Maps to {@link com.sample.data_scrapper.entity.BrandsiteProductExtraction}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TomfordProductDto {

    private String productId;
    private String status;
    private String productName;
    private String price;
    private String category;
    private String brand;
    private String description;
    private String productUrl;
    private String taskLink;
    private String whatAreYouLookingFor;
    private String ingredients;

    /**
     * Product details restructured by LLM. Populated after calling the Gemini restructure service.
     */
    private String restructuredProductDetails;

    @Builder.Default
    private Map<String, String> rawRow = new LinkedHashMap<>();

    private String sourceFileName;
}
