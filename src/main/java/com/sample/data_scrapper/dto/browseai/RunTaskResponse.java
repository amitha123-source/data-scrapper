package com.sample.data_scrapper.dto.browseai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RunTaskResponse {

    @JsonProperty("statusCode")
    private Integer statusCode;

    @JsonProperty("messageCode")
    private String messageCode;

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
    }
}
