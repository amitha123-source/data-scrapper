package com.sample.data_scrapper.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowRunResponse {

    private String runId;
    private String status;
    private Instant completedAt;
    private TaskResultSummary robot1;
    private TaskResultSummary robot2;
    private TaskResultSummary robot3;
    private List<String> productLinks;
    private List<String> productNames;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskResultSummary {
        private String taskId;
        private String status;
        private int recordsCount;
    }
}
