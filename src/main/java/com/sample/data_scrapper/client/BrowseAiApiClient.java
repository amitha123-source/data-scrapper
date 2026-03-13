package com.sample.data_scrapper.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sample.data_scrapper.config.BrowseAiProperties;
import com.sample.data_scrapper.dto.browseai.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client for Browse AI v2 API: run task, get task status/result, bulk run.
 * Base URL: https://api.browse.ai/v2
 */
@Slf4j
@Component
public class BrowseAiApiClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestTemplate browseAiRestTemplate;
    private final RestTemplate browseAiRestTemplateXApiKeyOnly;
    private final BrowseAiProperties properties;

    public BrowseAiApiClient(
            RestTemplate browseAiRestTemplate,
            @Qualifier("browseAiRestTemplateXApiKeyOnly") RestTemplate browseAiRestTemplateXApiKeyOnly,
            BrowseAiProperties properties) {
        this.browseAiRestTemplate = browseAiRestTemplate;
        this.browseAiRestTemplateXApiKeyOnly = browseAiRestTemplateXApiKeyOnly;
        this.properties = properties;
    }

    /**
     * Run a single robot task with the given input parameters.
     *
     * @param robotId          robot ID from Browse AI
     * @param inputParameters  e.g. Map.of("originUrl", "https://...") or ("productUrl", "...")
     * @return task ID from the response
     */
    public String runTask(String robotId, Map<String, String> inputParameters) {
        String url = properties.getBaseUrl() + "/robots/" + robotId + "/tasks";
        RunTaskRequest request = RunTaskRequest.builder()
                .inputParameters(inputParameters)
                .build();
        try {
            ResponseEntity<RunTaskResponse> response = browseAiRestTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(request),
                    RunTaskResponse.class
            );
            RunTaskResponse body = response.getBody();
            if (body == null || body.getResult() == null || body.getResult().getId() == null) {
                throw new BrowseAiApiException("Run task failed: no task id in response");
            }
            return body.getResult().getId();
        } catch (HttpClientErrorException.Unauthorized ex) {
            throw new BrowseAiApiException("Browse AI rejected the API key. Verify browse-ai.api-key and the robot access permissions.", ex);
        }
    }

    /**
     * Get task IDs for a bulk run. Browse AI create bulk-run response does not include task IDs;
     * this method fetches them from the bulk-run tasks endpoint if available.
     *
     * @return list of task IDs, or empty list if endpoint is not available or returns no tasks
     */
    public List<String> getBulkRunTaskIds(String robotId, String bulkRunId) {
        if (bulkRunId == null || bulkRunId.isBlank()) {
            return List.of();
        }
        String url = properties.getBaseUrl() + "/robots/" + robotId + "/bulk-runs/" + bulkRunId + "/tasks";
        try {
            // Prefer template that sends Bearer + Base64(apiKey) so gateway accepts token without ":".
            ResponseEntity<String> response = browseAiRestTemplateXApiKeyOnly.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    String.class
            );
            return parseTaskIdsFromBody(response.getBody());
        } catch (HttpClientErrorException.Forbidden | HttpClientErrorException.Unauthorized e) {
            // If 403/401 (e.g. backend rejects Base64), retry once with main template (Bearer + raw key).
            log.debug("Bulk run tasks auth failed, retrying with main auth for bulkRunId={}", bulkRunId);
            try {
                ResponseEntity<String> retry = browseAiRestTemplate.exchange(url, HttpMethod.GET, null, String.class);
                return parseTaskIdsFromBody(retry.getBody());
            } catch (Exception ex) {
                log.warn("Failed to get bulk run task IDs for bulkRunId={}: {}", bulkRunId, ex.getMessage());
                return List.of();
            }
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("Bulk run tasks endpoint not found for bulkRunId={}", bulkRunId);
            return List.of();
        } catch (Exception e) {
            log.warn("Failed to get bulk run task IDs for bulkRunId={}: {}", bulkRunId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Get task status and result (including capturedTexts when completed).
     */
    public TaskStatusResponse getTaskStatus(String robotId, String taskId) {
        String url = properties.getBaseUrl() + "/robots/" + robotId + "/tasks/" + taskId;
        ResponseEntity<TaskStatusResponse> response = browseAiRestTemplate.exchange(
                url,
                HttpMethod.GET,
                null,
                TaskStatusResponse.class
        );
        TaskStatusResponse body = response.getBody();
        if (body == null) {
            throw new BrowseAiApiException("Get task failed: empty response for task " + taskId);
        }
        return body;
    }

    /**
     * Run robot in bulk with a list of input parameter maps.
     *
     * @param robotId              robot ID
     * @param inputParametersList  list of maps, e.g. [{"originUrl": "url1"}, {"originUrl": "url2"}]
     * @return bulk run ID and list of task IDs
     */
    public BulkRunResponse runBulk(String robotId, List<Map<String, String>> inputParametersList) {
        return runBulk(robotId, null, inputParametersList);
    }

    public BulkRunResponse runBulk(String robotId, String title, List<Map<String, String>> inputParametersList) {
        String url = properties.getBaseUrl() + "/robots/" + robotId + "/bulk-runs";
        String resolvedTitle = title != null ? title : "Bulk run " + System.currentTimeMillis();

        try {
            return executeBulkRun(url, resolvedTitle, inputParametersList, "inputParameters");
        } catch (HttpClientErrorException.BadRequest ex) {
            if (!ex.getResponseBodyAsString().contains("invalid_input_parameters")) {
                throw ex;
            }
            log.warn("Bulk run rejected camelCase payload. Retrying with snake_case payload. First input={}",
                    inputParametersList.isEmpty() ? "{}" : inputParametersList.get(0));
            return executeBulkRun(url, resolvedTitle, inputParametersList, "input_parameters");
        }
    }

    private BulkRunResponse executeBulkRun(String url, String title, List<Map<String, String>> inputParametersList,
                                           String inputParametersFieldName) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("title", title);
        request.put(inputParametersFieldName, inputParametersList);

        ResponseEntity<String> response = browseAiRestTemplate.exchange(
                url,
                HttpMethod.POST,
                new HttpEntity<>(request),
                String.class
        );
        String rawBody = response.getBody();
        if (rawBody == null || rawBody.isBlank()) {
            throw new BrowseAiApiException("Bulk run failed: empty response");
        }
        BulkRunResponse body = parseBulkRunResponse(rawBody);
        if (body.getResult() != null && body.getResult().resolvedTaskIds().isEmpty()) {
            log.warn("Bulk run response returned no task IDs. bulkRunId={}, rawBody={}",
                    body.getResult().getBulkRunId(), rawBody);
        }
        return body;
    }

    private BulkRunResponse parseBulkRunResponse(String rawBody) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(rawBody);
            BulkRunResponse response = new BulkRunResponse();

            JsonNode statusCode = root.get("statusCode");
            if (statusCode != null && statusCode.canConvertToInt()) {
                response.setStatusCode(statusCode.intValue());
            }

            JsonNode resultNode = firstObjectNode(root.get("result"), root.get("data"), root);
            // Browse AI returns result.bulkRun with id, tasksCount, etc. – not result.id or result.taskIds
            JsonNode bulkRunNode = resultNode.get("bulkRun");
            JsonNode effectiveResult = (bulkRunNode != null && bulkRunNode.isObject()) ? bulkRunNode : resultNode;

            BulkRunResponse.BulkRunResult result = new BulkRunResponse.BulkRunResult();
            result.setBulkRunId(firstText(effectiveResult,
                    "id", "bulkRunId", "robotBulkRunId", "bulk_run_id"));
            result.setTaskIds(extractTaskIds(resultNode));
            if (result.getTaskIds() == null || result.getTaskIds().isEmpty()) {
                result.setTaskIds(extractTaskIds(effectiveResult));
            }
            response.setResult(result);
            return response;
        } catch (IOException ex) {
            throw new BrowseAiApiException("Failed to parse Browse AI bulk run response", ex);
        }
    }

    private JsonNode firstObjectNode(JsonNode... candidates) {
        for (JsonNode node : candidates) {
            if (node != null && node.isObject()) {
                return node;
            }
        }
        return OBJECT_MAPPER.createObjectNode();
    }

    private String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isValueNode() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private List<String> parseTaskIdsFromBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(rawBody);
            JsonNode resultNode = firstObjectNode(root.get("result"), root.get("data"), root);
            return extractTaskIds(resultNode);
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<String> extractTaskIds(JsonNode resultNode) {
        Set<String> taskIds = new LinkedHashSet<>();
        addTextArray(taskIds, resultNode.get("taskIds"));
        addTextArray(taskIds, resultNode.get("task_ids"));
        addTaskObjects(taskIds, resultNode.get("tasks"));
        addTaskObjects(taskIds, resultNode.get("results"));
        addTaskObjects(taskIds, resultNode.get("items"));

        JsonNode nestedData = resultNode.get("data");
        if (nestedData != null && nestedData.isObject()) {
            addTextArray(taskIds, nestedData.get("taskIds"));
            addTextArray(taskIds, nestedData.get("task_ids"));
            addTaskObjects(taskIds, nestedData.get("tasks"));
            addTaskObjects(taskIds, nestedData.get("results"));
            addTaskObjects(taskIds, nestedData.get("items"));
        }

        return new ArrayList<>(taskIds);
    }

    private void addTextArray(Set<String> taskIds, JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return;
        }
        for (JsonNode item : arrayNode) {
            if (item != null && item.isValueNode() && !item.asText().isBlank()) {
                taskIds.add(item.asText());
            }
        }
    }

    private void addTaskObjects(Set<String> taskIds, JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return;
        }
        for (JsonNode item : arrayNode) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String id = firstText(item, "id", "taskId");
            if (id != null) {
                taskIds.add(id);
            }
        }
    }

    @SuppressWarnings("unused")
    public static class BrowseAiApiException extends RuntimeException {
        public BrowseAiApiException(String message) {
            super(message);
        }

        public BrowseAiApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
