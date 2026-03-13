package com.sample.data_scrapper.dto.browseai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Request for bulk run. Browse AI may expect "input_parameters" and optionally "title".
 * Adjust @JsonProperty if your API uses different keys.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BulkRunRequest {

    @JsonProperty("title")
    private String title;

    @JsonProperty("input_parameters")
    private List<Map<String, String>> inputParametersList;
}
