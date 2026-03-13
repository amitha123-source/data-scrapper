package com.sample.data_scrapper.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;

@Configuration
public class BrowseAiConfig {

    /** For run task, bulk run, get task status – uses x-api-key and Authorization Bearer. */
    @Primary
    @Bean
    public RestTemplate browseAiRestTemplate(BrowseAiProperties properties) {
        RestTemplate restTemplate = new RestTemplate();
        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            String apiKey = properties.getApiKey() != null ? properties.getApiKey().trim() : "";
            request.getHeaders().set("x-api-key", apiKey);
            request.getHeaders().set("Authorization", "Bearer " + apiKey);
            request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            request.getHeaders().setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            return execution.execute(request, body);
        };
        restTemplate.setInterceptors(Collections.singletonList(interceptor));
        return restTemplate;
    }

    /**
     * For GET .../bulk-runs/{bulkRunId}/tasks only. Gateway requires Authorization and expects
     * "key=value" (with equal-sign); "Bearer &lt;key&gt;" is rejected. Use apiKey=&lt;base64&gt; so value has no colon.
     */
    @Bean(name = "browseAiRestTemplateXApiKeyOnly")
    public RestTemplate browseAiRestTemplateXApiKeyOnly(BrowseAiProperties properties) {
        RestTemplate restTemplate = new RestTemplate();
        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            String apiKey = properties.getApiKey() != null ? properties.getApiKey().trim() : "";
            request.getHeaders().set("x-api-key", apiKey);
            String token = Base64.getEncoder().encodeToString(apiKey.getBytes(StandardCharsets.UTF_8));
            request.getHeaders().set("Authorization", "apiKey=" + token);
            request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            request.getHeaders().setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            return execution.execute(request, body);
        };
        restTemplate.setInterceptors(Collections.singletonList(interceptor));
        return restTemplate;
    }
}
