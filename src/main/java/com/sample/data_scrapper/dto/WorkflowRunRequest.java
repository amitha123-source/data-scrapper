package com.sample.data_scrapper.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to trigger the full workflow (Robot 1 → 2 → 3).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowRunRequest {

    /**
     * Optional. Category URL for Robot 1. If null, uses default King Power fragrances URL.
     */
    private String categoryUrl;

    /**
     * Optional. Browse AI input parameter for Robot 1 item limit.
     */
    @JsonProperty("tom_ford_beauty_fragrances_limit")
    private String tomFordBeautyFragrancesLimit;
}
