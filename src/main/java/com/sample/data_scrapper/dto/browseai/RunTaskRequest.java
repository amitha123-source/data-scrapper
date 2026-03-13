package com.sample.data_scrapper.dto.browseai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request body for running a single Browse AI robot task.
 * Input parameters depend on robot (e.g. originUrl for list, productUrl for detail).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RunTaskRequest {

    /**
     * Input parameters for the robot (e.g. "originUrl", "productUrl", "productName").
     */
    private Map<String, String> inputParameters;
}
