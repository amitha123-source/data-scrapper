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
}
