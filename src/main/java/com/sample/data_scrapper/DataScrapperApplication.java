package com.sample.data_scrapper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.sample.data_scrapper.config.BrowseAiProperties;
import com.sample.data_scrapper.config.ProductDataProperties;

@SpringBootApplication
@EnableConfigurationProperties({ BrowseAiProperties.class, ProductDataProperties.class })
public class DataScrapperApplication {

	public static void main(String[] args) {
		SpringApplication.run(DataScrapperApplication.class, args);
	}

}
