package com.sample.data_scrapper.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "browse-ai")
public class BrowseAiProperties {

    private String baseUrl = "https://api.browse.ai/v2";
    private String apiKey;
    private RobotIds robotIds = new RobotIds();
    private long pollIntervalMs = 10_000;
    private int pollMaxAttempts = 120;

    @Data
    public static class RobotIds {
        private String productList;   // Robot 1 - product list from King Power
        private String productDetail; // Robot 2 - product data from product link
        private String brandDetail;   // Robot 3 - product details from brand site
    }
}
