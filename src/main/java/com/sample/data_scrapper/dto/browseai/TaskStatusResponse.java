package com.sample.data_scrapper.dto.browseai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskStatusResponse {

    @JsonProperty("statusCode")
    private Integer statusCode;

    @JsonProperty("result")
    private TaskResult result;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TaskResult {
        @JsonProperty("id")
        private String id;

        @JsonProperty("status")
        private String status;

        @JsonProperty("robotId")
        private String robotId;

        @JsonProperty("capturedTexts")
        private Map<String, Object> capturedTexts;

        @JsonProperty("capturedLists")
        private Map<String, Object> capturedLists;

        @JsonProperty("capturedDataTemporaryUrl")
        private String capturedDataTemporaryUrl;
    }
}
