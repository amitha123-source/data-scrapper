package com.sample.data_scrapper.controller;

import com.sample.data_scrapper.dto.WorkflowRunRequest;
import com.sample.data_scrapper.dto.WorkflowRunResponse;
import com.sample.data_scrapper.entity.AutomationRun;
import com.sample.data_scrapper.service.BrowseAiWorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/workflow", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class WorkflowController {

    private final BrowseAiWorkflowService workflowService;

    /**
     * Trigger the full automation: Robot 1 (product list) → Robot 2 (product detail) → Robot 3 (brand detail).
     * Returns JSON. Optionally pass a category URL for Robot 1; otherwise the default King Power fragrances URL is used.
     */
    @PostMapping(value = "/run", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkflowRunResponse> runFullWorkflow(
            @RequestBody(required = false) WorkflowRunRequest request
    ) {
        WorkflowRunResponse response = workflowService.runFullWorkflow(request != null ? request : new WorkflowRunRequest());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping(value = "/runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AutomationRun> getRun(@PathVariable String runId) {
        return workflowService.getRun(runId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
