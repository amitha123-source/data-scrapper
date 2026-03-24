package com.sample.data_scrapper.service;

import com.sample.data_scrapper.client.BrowseAiApiClient;
import com.sample.data_scrapper.config.BrowseAiProperties;
import com.sample.data_scrapper.dto.WorkflowRunRequest;
import com.sample.data_scrapper.dto.WorkflowRunResponse;
import com.sample.data_scrapper.dto.browseai.TaskStatusResponse;
import com.sample.data_scrapper.entity.AutomationRun;
import com.sample.data_scrapper.repository.AutomationRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * End-to-end workflow: Robot 1 (product list) → Robot 2 (product detail) → Robot 3 (brand detail).
 * Follows the architecture: runRobot1 → wait → extractProductLinks → runRobot2Bulk → wait → saveRobot2Data
 * → extractProductNames → runRobot3Bulk → wait → saveRobot3Data → returnSuccess.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrowseAiWorkflowService {

    private static final String DEFAULT_CATEGORY_URL =
            "https://www.kingpower.com/en/tom-ford-beauty/category/fragrances/private-blend-fragrances";
    private static final String DEFAULT_TOM_FORD_BEAUTY_FRAGRANCES_LIMIT = "10";

    private final BrowseAiApiClient apiClient;
    private final BrowseAiProperties properties;
    private final AutomationRunRepository automationRunRepository;

    @Transactional
    public WorkflowRunResponse runFullWorkflow(WorkflowRunRequest request) {
        String runId = UUID.randomUUID().toString();
        String categoryUrl = request != null && request.getCategoryUrl() != null && !request.getCategoryUrl().isBlank()
                ? request.getCategoryUrl().trim()
                : DEFAULT_CATEGORY_URL;
        String tomFordBeautyFragrancesLimit =
                request != null && request.getTomFordBeautyFragrancesLimit() != null
                        && !request.getTomFordBeautyFragrancesLimit().isBlank()
                        ? request.getTomFordBeautyFragrancesLimit().trim()
                        : DEFAULT_TOM_FORD_BEAUTY_FRAGRANCES_LIMIT;

        AutomationRun run = AutomationRun.builder()
                .runId(runId)
                .status("RUNNING")
                .categoryUrl(categoryUrl)
                .startedAt(Instant.now())
                .build();
        automationRunRepository.save(run);

        try {
            // --- runRobot1() ---
            String robot1TaskId = runRobot1(categoryUrl, tomFordBeautyFragrancesLimit);
            run.setRobot1TaskId(robot1TaskId);
            automationRunRepository.save(run);

            // --- waitForCompletion() for Robot 1 ---
            TaskStatusResponse robot1Result = waitForCompletion(properties.getRobotIds().getProductList(), robot1TaskId);

            // --- extractProductLinks() ---
            List<String> productLinks = extractProductLinks(robot1Result);
            run.setProductLinksCount(productLinks != null ? productLinks.size() : 0);
            automationRunRepository.save(run);

            if (productLinks == null || productLinks.isEmpty()) {
                completeRun(run, "COMPLETED_NO_PRODUCTS", null, 0, 0);
                return buildResponse(run, robot1TaskId, null, null, productLinks, Collections.emptyList());
            }

            // --- runRobot2Bulk() ---
            BulkRunResult robot2Bulk = runRobot2Bulk(productLinks);
            run.setRobot2BulkRunId(robot2Bulk.bulkRunId);
            automationRunRepository.save(run);

            // --- waitForCompletion() for all Robot 2 tasks ---
            List<TaskStatusResponse> robot2Results = waitForBulkCompletion(
                    properties.getRobotIds().getProductDetail(),
                    robot2Bulk.taskIds
            );
            List<TaskStatusResponse> robot2Successful = filterSuccessful(robot2Results);
            if (robot2Successful.size() < robot2Results.size()) {
                log.warn("Robot 2: {} of {} tasks succeeded; {} failed or incomplete",
                        robot2Successful.size(), robot2Results.size(), robot2Results.size() - robot2Successful.size());
            }

            // --- saveRobot2Data() ---
            saveRobot2Data(runId, robot2Successful);
            run.setRobot2RecordsCount(robot2Successful.size());
            automationRunRepository.save(run);

            // --- extractProductNames() ---
            List<String> productNames = extractProductNames(robot2Successful);

            // --- runRobot3Bulk() only if we have product names (API returns 400 zero_length_parameters for empty list) ---
            if (productNames == null || productNames.isEmpty()) {
                log.info("No product names extracted from Robot 2; skipping Robot 3");
                completeRun(run, "COMPLETED", null, robot2Successful.size(), 0);
                return buildResponse(run, robot1TaskId, robot2Bulk.bulkRunId, null, productLinks, productNames != null ? productNames : Collections.emptyList());
            }

            BulkRunResult robot3Bulk = runRobot3Bulk(productNames);
            run.setRobot3BulkRunId(robot3Bulk.bulkRunId);
            automationRunRepository.save(run);

            // --- waitForCompletion() for all Robot 3 tasks ---
            List<TaskStatusResponse> robot3Results = waitForBulkCompletion(
                    properties.getRobotIds().getBrandDetail(),
                    robot3Bulk.taskIds
            );
            List<TaskStatusResponse> robot3Successful = filterSuccessful(robot3Results);
            if (robot3Successful.size() < robot3Results.size()) {
                log.warn("Robot 3: {} of {} tasks succeeded; {} failed or incomplete",
                        robot3Successful.size(), robot3Results.size(), robot3Results.size() - robot3Successful.size());
            }

            // --- saveRobot3Data() ---
            saveRobot3Data(runId, robot3Successful);
            run.setRobot3RecordsCount(robot3Successful.size());
            automationRunRepository.save(run);

            // --- returnSuccess() ---
            completeRun(run, "COMPLETED", null, robot2Successful.size(), robot3Successful.size());
            return buildResponse(run, robot1TaskId, robot2Bulk.bulkRunId, robot3Bulk.bulkRunId, productLinks, productNames);

        } catch (Exception e) {
            log.error("Workflow failed for runId={}", runId, e);
            run.setStatus("FAILED");
            run.setCompletedAt(Instant.now());
            automationRunRepository.save(run);
            throw e;
        }
    }

    private String runRobot1(String categoryUrl, String tomFordBeautyFragrancesLimit) {
        log.info("runRobot1: starting product list robot for url={} and limit={}",
                categoryUrl, tomFordBeautyFragrancesLimit);
        Map<String, String> input = Map.of(
                "originUrl", categoryUrl,
                "tom_ford_beauty_fragrances_limit", tomFordBeautyFragrancesLimit
        );
        String taskId = apiClient.runTask(properties.getRobotIds().getProductList(), input);
        log.info("runRobot1: taskId={}", taskId);
        return taskId;
    }

    private TaskStatusResponse waitForCompletion(String robotId, String taskId) {
        long intervalMs = properties.getPollIntervalMs();
        int maxAttempts = properties.getPollMaxAttempts();
        for (int i = 0; i < maxAttempts; i++) {
            TaskStatusResponse resp = apiClient.getTaskStatus(robotId, taskId);
            if (resp.getResult() == null) continue;
            String status = resp.getResult().getStatus();
            if ("successful".equalsIgnoreCase(status)) {
                return resp;
            }
            if ("failed".equalsIgnoreCase(status)) {
                log.warn("Task failed: {} (robotId={}); continuing with other tasks", taskId, robotId);
                return resp;
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for task " + taskId, e);
            }
        }
        log.warn("Task did not complete in time: {} (robotId={}); returning last response", taskId, robotId);
        return apiClient.getTaskStatus(robotId, taskId);
    }

    private List<String> extractProductLinks(TaskStatusResponse robot1Result) {
        if (robot1Result == null || robot1Result.getResult() == null) {
            return Collections.emptyList();
        }

        List<String> linksFromLists = extractLinksFromCapturedLists(robot1Result.getResult().getCapturedLists());
        if (!linksFromLists.isEmpty()) {
            log.info("extractProductLinks: found {} links in capturedLists", linksFromLists.size());
            return linksFromLists;
        }

        List<String> linksFromTexts = extractLinksFromCapturedMap(robot1Result.getResult().getCapturedTexts());
        if (!linksFromTexts.isEmpty()) {
            log.info("extractProductLinks: found {} links in capturedTexts", linksFromTexts.size());
            return linksFromTexts;
        }

        log.warn("extractProductLinks: no product links found in capturedLists or capturedTexts");
        return Collections.emptyList();
    }

    private List<String> extractLinksFromCapturedLists(Map<String, Object> capturedLists) {
        if (capturedLists == null || capturedLists.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> links = new ArrayList<>();
        for (Object listValue : capturedLists.values()) {
            if (!(listValue instanceof List<?> rows)) {
                continue;
            }
            for (Object rowObj : rows) {
                if (!(rowObj instanceof Map<?, ?> row)) {
                    continue;
                }
                String link = extractLinkFromRow(row);
                if (link != null) {
                    links.add(link);
                }
            }
        }
        return links;
    }

    private String extractLinkFromRow(Map<?, ?> row) {
        for (String key : List.of("productLink", "product_url", "link", "productLinks", "Product Link")) {
            Object value = row.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString().trim();
            }
        }
        return null;
    }

    private List<String> extractLinksFromCapturedMap(Map<String, Object> captured) {
        if (captured == null || captured.isEmpty()) {
            return Collections.emptyList();
        }

        for (String key : List.of("productLink", "product_url", "link", "productLinks", "Product Link")) {
            Object links = captured.get(key);
            if (links instanceof List<?> values) {
                return values.stream()
                        .filter(Objects::nonNull)
                        .map(Object::toString)
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toList());
            }
            if (links instanceof String value) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    return Collections.singletonList(trimmed);
                }
            }
        }

        return Collections.emptyList();
    }

    private BulkRunResult runRobot2Bulk(List<String> productLinks) {
        log.info("runRobot2Bulk: {} product links", productLinks.size());
        String robotId = properties.getRobotIds().getProductDetail();
        List<Map<String, String>> inputs = productLinks.stream()
                .map(link -> Map.<String, String>of("originUrl", link))
                .collect(Collectors.toList());
        // Robot 2 expects the product page URL as the Browse AI input parameter "originUrl".
        var response = apiClient.runBulk(robotId, inputs);
        if (response.getResult() == null) {
            throw new IllegalStateException("Bulk run Robot 2 returned no result");
        }
        String bulkRunId = response.getResult().getBulkRunId();
        List<String> taskIds = response.getResult().resolvedTaskIds();

        // Browse AI bulk-run create does not return task IDs; GET .../bulk-runs/{id}/tasks returns 403 (gateway auth).
        // Skip that call and run each link as a single task so the workflow completes without delay.
        if (taskIds.isEmpty()) {
            log.info("runRobot2Bulk: bulk run has no task IDs in response; running {} single tasks instead", productLinks.size());
            taskIds = new ArrayList<>();
            for (String link : productLinks) {
                taskIds.add(apiClient.runTask(robotId, Map.of("originUrl", link)));
            }
        }

        if (taskIds.isEmpty()) {
            throw new IllegalStateException("Bulk run Robot 2 returned no task IDs");
        }
        return new BulkRunResult(bulkRunId, taskIds);
    }

    private List<TaskStatusResponse> waitForBulkCompletion(String robotId, List<String> taskIds) {
        List<TaskStatusResponse> results = new ArrayList<>();
        for (String taskId : taskIds) {
            results.add(waitForCompletion(robotId, taskId));
        }
        return results;
    }

    /** Returns only results whose task status is "successful" (used for saving and name extraction). */
    private List<TaskStatusResponse> filterSuccessful(List<TaskStatusResponse> results) {
        if (results == null) return Collections.emptyList();
        return results.stream()
                .filter(r -> r.getResult() != null && "successful".equalsIgnoreCase(r.getResult().getStatus()))
                .collect(Collectors.toList());
    }

    private void saveRobot2Data(String runId, List<TaskStatusResponse> robot2Results) {
        log.info("saveRobot2Data: runId={}, records={}", runId, robot2Results.size());
        // Persist or export Robot 2 captured data (e.g. to DB table or file).
        // Here we only log; you can add a repository for Robot2Record and save each result.getResult().getCapturedTexts().
        for (TaskStatusResponse r : robot2Results) {
            if (r.getResult() != null && r.getResult().getCapturedTexts() != null) {
                // Optional: save to DB or append to Excel/CSV
            }
        }
    }

    private static final List<String> PRODUCT_NAME_KEYS = List.of(
            "productName", "product_name", "Product Name",
            "name", "title", "productTitle", "product_title"
    );

    private List<String> extractProductNames(List<TaskStatusResponse> robot2Results) {
        List<String> names = new ArrayList<>();
        for (TaskStatusResponse r : robot2Results) {
            if (r.getResult() == null) continue;
            String name = extractProductNameFromResult(r.getResult());
            if (name != null) {
                names.add(name);
            }
        }
        if (!names.isEmpty()) {
            log.info("extractProductNames: found {} names", names.size());
        } else {
            log.warn("extractProductNames: no product names in Robot 2 results (check capturedTexts/capturedLists keys)");
        }
        return names;
    }

    private String extractProductNameFromResult(TaskStatusResponse.TaskResult result) {
        // Try capturedLists first (rows with name-like columns).
        if (result.getCapturedLists() != null && !result.getCapturedLists().isEmpty()) {
            for (Object listValue : result.getCapturedLists().values()) {
                if (!(listValue instanceof List<?> rows)) continue;
                for (Object rowObj : rows) {
                    if (!(rowObj instanceof Map<?, ?> row)) continue;
                    String name = extractNameFromRow(row);
                    if (name != null) return name;
                }
            }
        }
        // Then try capturedTexts (flat key-value).
        if (result.getCapturedTexts() != null) {
            return extractNameFromMap(result.getCapturedTexts());
        }
        return null;
    }

    private String extractNameFromRow(Map<?, ?> row) {
        for (String key : PRODUCT_NAME_KEYS) {
            Object value = row.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString().trim();
            }
        }
        return null;
    }

    private String extractNameFromMap(Map<String, Object> captured) {
        for (String key : PRODUCT_NAME_KEYS) {
            Object value = captured.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString().trim();
            }
        }
        return null;
    }

    private BulkRunResult runRobot3Bulk(List<String> productNames) {
        if (productNames == null || productNames.isEmpty()) {
            throw new IllegalStateException("Cannot run Robot 3 bulk with no product names (Browse AI returns 400 zero_length_parameters)");
        }
        log.info("runRobot3Bulk: {} product names", productNames.size());
        String robotId = properties.getRobotIds().getBrandDetail();
        List<Map<String, String>> inputs = productNames.stream()
                .map(name -> Map.<String, String>of("productName", name))
                .collect(Collectors.toList());
        var response = apiClient.runBulk(robotId, inputs);
        if (response.getResult() == null) {
            throw new IllegalStateException("Bulk run Robot 3 returned no result");
        }
        String bulkRunId = response.getResult().getBulkRunId();
        List<String> taskIds = response.getResult().resolvedTaskIds();

        if (taskIds.isEmpty()) {
            log.info("runRobot3Bulk: bulk run has no task IDs in response; running {} single tasks instead", productNames.size());
            taskIds = new ArrayList<>();
            for (String name : productNames) {
                taskIds.add(apiClient.runTask(robotId, Map.of("productName", name)));
            }
        }

        if (taskIds.isEmpty()) {
            throw new IllegalStateException("Bulk run Robot 3 returned no task IDs");
        }
        return new BulkRunResult(bulkRunId, taskIds);
    }

    private void saveRobot3Data(String runId, List<TaskStatusResponse> robot3Results) {
        log.info("saveRobot3Data: runId={}, records={}", runId, robot3Results.size());
        // Persist or export Robot 3 captured data.
        for (TaskStatusResponse r : robot3Results) {
            if (r.getResult() != null && r.getResult().getCapturedTexts() != null) {
                // Optional: save to DB or file
            }
        }
    }

    private void completeRun(AutomationRun run, String status, String robot2BulkId, int robot2Count, int robot3Count) {
        run.setStatus(status);
        run.setCompletedAt(Instant.now());
        if (robot2BulkId != null) run.setRobot2BulkRunId(robot2BulkId);
        run.setRobot2RecordsCount(robot2Count);
        run.setRobot3RecordsCount(robot3Count);
        automationRunRepository.save(run);
    }

    private WorkflowRunResponse buildResponse(AutomationRun run, String robot1TaskId, String robot2BulkId, String robot3BulkId,
                                              List<String> productLinks, List<String> productNames) {
        return WorkflowRunResponse.builder()
                .runId(run.getRunId())
                .status(run.getStatus())
                .completedAt(run.getCompletedAt())
                .robot1(WorkflowRunResponse.TaskResultSummary.builder()
                        .taskId(robot1TaskId)
                        .status("successful")
                        .recordsCount(run.getProductLinksCount() != null ? run.getProductLinksCount() : 0)
                        .build())
                .robot2(robot2BulkId != null ? WorkflowRunResponse.TaskResultSummary.builder()
                        .taskId(robot2BulkId)
                        .status("successful")
                        .recordsCount(run.getRobot2RecordsCount() != null ? run.getRobot2RecordsCount() : 0)
                        .build() : null)
                .robot3(robot3BulkId != null ? WorkflowRunResponse.TaskResultSummary.builder()
                        .taskId(robot3BulkId)
                        .status("successful")
                        .recordsCount(run.getRobot3RecordsCount() != null ? run.getRobot3RecordsCount() : 0)
                        .build() : null)
                .productLinks(productLinks != null ? productLinks : Collections.emptyList())
                .productNames(productNames != null ? productNames : Collections.emptyList())
                .build();
    }

    public Optional<AutomationRun> getRun(String runId) {
        return automationRunRepository.findByRunId(runId);
    }

    private record BulkRunResult(String bulkRunId, List<String> taskIds) {}
}
