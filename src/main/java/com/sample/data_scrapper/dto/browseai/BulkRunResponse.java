package com.sample.data_scrapper.dto.browseai;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BulkRunResponse {

    @JsonProperty("statusCode")
    private Integer statusCode;

    @JsonProperty("result")
    private BulkRunResult result;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BulkRunResult {
        @JsonAlias({"id", "bulkRunId", "robotBulkRunId"})
        private String bulkRunId;

        @JsonAlias({"taskIds", "task_ids"})
        private List<String> taskIds;

        @JsonProperty("tasks")
        private List<TaskRef> tasks;

        public List<String> resolvedTaskIds() {
            if (taskIds != null && !taskIds.isEmpty()) {
                return taskIds;
            }
            if (tasks == null || tasks.isEmpty()) {
                return Collections.emptyList();
            }
            return tasks.stream()
                    .map(TaskRef::getId)
                    .filter(Objects::nonNull)
                    .toList();
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TaskRef {
        @JsonAlias({"id", "taskId"})
        private String id;
    }
}
