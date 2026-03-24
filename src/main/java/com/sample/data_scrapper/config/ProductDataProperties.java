package com.sample.data_scrapper.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "product-data")
public class ProductDataProperties {

    /**
     * Directory to watch for CSV files (e.g. Product_data folder).
     */
    private String watchDirectory = "C:\\Users\\amikr\\OneDrive\\Documents\\Product_data";

    /** CSV file name for Kingpower product data. */
    private String kingpowerFileName = "Kingpower_data.csv";

    /** CSV file name for Tomford product data. */
    private String tomfordFileName = "Tomford_data.csv";

    /**
     * Delay in milliseconds between consecutive LLM calls when fetching details for missing Tomford products.
     * Helps avoid rate limiting / "Failed to execute HTTP request" when many products are missing. 0 = no delay.
     */
    private long llmFetchDelayMs = 500;

    /**
     * When true, before saving brandsite rows for a workflow run, delete existing {@code brandsite_product_extractions}
     * rows for each brand present in the batch. Prevents duplicate DB rows when the workflow runs again (e.g. CSV watcher).
     * Set false to append every run (not recommended unless you dedupe downstream).
     */
    private boolean brandsiteReplaceByBrandOnRun = true;
}
