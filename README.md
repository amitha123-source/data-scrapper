# Data Scrapper – Browse AI Workflow Automation

Automates the full product-scraping workflow via API: **Robot 1** (product list) → **Robot 2** (product detail) → **Robot 3** (brand detail). Trigger everything by calling a single REST endpoint.

## Workflow Architecture

```
Java Service
    |
    |-- runRobot1()
    |
    |-- waitForCompletion()
    |
    |-- extractProductLinks()
    |
    |-- runRobot2Bulk()
    |
    |-- waitForCompletion()
    |
    |-- saveRobot2Data()   ✅ (IMPORTANT STEP)
    |
    |-- extractProductNames()
    |
    |-- runRobot3Bulk()
    |
    |-- waitForCompletion()
    |
    |-- saveRobot3Data()   ✅
    |
    |-- returnSuccess()
```

## Tech Stack

- **Java 17**, **Spring Boot** (Web, Data JPA)
- **H2** (in-memory DB for run metadata)
- **Browse AI** API for running robots and fetching results

## Configuration

Set your Browse AI API key and robot IDs in `application.yaml` or via environment variables:

| Property | Env var | Description |
|----------|---------|-------------|
| `browse-ai.api-key` | `BROWSE_AI_API_KEY` | Your Browse AI API key |
| `browse-ai.robot-ids.product-list` | `BROWSE_AI_ROBOT_1_ID` | Robot 1 – product list (King Power) |
| `browse-ai.robot-ids.product-detail` | `BROWSE_AI_ROBOT_2_ID` | Robot 2 – product detail per link |
| `browse-ai.robot-ids.brand-detail` | `BROWSE_AI_ROBOT_3_ID` | Robot 3 – brand site detail by product name |

Optional: `browse-ai.poll-interval-ms`, `browse-ai.poll-max-attempts` for task polling.

## Run the application

```bash
mvn spring-boot:run
```

Or set env vars and run:

```bash
set BROWSE_AI_API_KEY=your-api-key
set BROWSE_AI_ROBOT_1_ID=robot-id-1
set BROWSE_AI_ROBOT_2_ID=robot-id-2
set BROWSE_AI_ROBOT_3_ID=robot-id-3
mvn spring-boot:run
```

## API

All responses are **JSON** (`Content-Type: application/json`). Error responses from the exception handler are also JSON.

### Trigger full workflow

**POST** `/api/workflow/run`

Runs Robot 1 (default King Power fragrances URL), then Robot 2 for each product link, then Robot 3 for each product name. Waits for each step to complete and returns a JSON summary.

**Request body (optional):**

```json
{
  "categoryUrl": "https://www.kingpower.com/en/tom-ford-beauty/category/fragrances/private-blend-fragrances"
}
```

Omit body to use the default category URL.

**Response (201):**

```json
{
  "runId": "uuid",
  "status": "COMPLETED",
  "completedAt": "2025-03-05T...",
  "robot1": { "taskId": "...", "status": "successful", "recordsCount": 10 },
  "robot2": { "taskId": "...", "status": "successful", "recordsCount": 10 },
  "robot3": { "taskId": "...", "status": "successful", "recordsCount": 10 },
  "productLinks": ["https://..."],
  "productNames": ["..."]
}
```

### Get run status

**GET** `/api/workflow/runs/{runId}`

Returns the stored automation run (status, task IDs, counts). Returns 404 if not found.

## Project structure

- **config** – `BrowseAiProperties`, `BrowseAiConfig` (RestTemplate with API key)
- **client** – `BrowseAiApiClient` (run task, get task status, bulk run)
- **controller** – `WorkflowController` (POST `/run`, GET `/runs/{runId}`)
- **dto** – `WorkflowRunRequest`, `WorkflowRunResponse`; `browseai.*` for API request/response
- **entity** – `AutomationRun`
- **repository** – `AutomationRunRepository`
- **service** – `BrowseAiWorkflowService` (implements the workflow steps above)
- **exception** – `GlobalExceptionHandler` (Browse AI and workflow errors)

## Customising robot input/output keys

If your Browse AI robots use different **input parameter** or **captured field** names, update:

1. **Robot 1 output (product links)** – In `BrowseAiWorkflowService.extractProductLinks()`, the code looks for `productLink`, `product_url`, `link`, or `productLinks` in `capturedTexts`. Add or change keys to match your robot’s captured fields.
2. **Robot 2 input** – In `runRobot2Bulk()`, the map key for the product URL is `productUrl`. If your robot expects e.g. `originUrl`, change it there.
3. **Robot 2 output (product names)** – In `extractProductNames()`, the code looks for `productName`, `name`, or `title`. Adjust to match your robot.
4. **Robot 3 input** – In `runRobot3Bulk()`, the map key is `productName`. Change if your robot uses another parameter name.

## Persisting Robot 2 / Robot 3 data

`saveRobot2Data()` and `saveRobot3Data()` currently log and leave a placeholder for persistence. To save to DB or files:

- Add entities (e.g. `Robot2Record`, `Robot3Record`) and JPA repositories.
- In the service, map each `TaskStatusResponse.getResult().getCapturedTexts()` to your entity and save (or write to Excel/CSV).

## Browse AI API notes

- **Run task:** `POST /v2/robots/{robotId}/run` with body `{ "inputParameters": { "originUrl": "..." } }`.
- **Get task:** `GET /v2/robots/{robotId}/tasks/{taskId}`.
- **Bulk run:** `POST /v2/robots/{robotId}/bulk-runs` with body `{ "input_parameters": [ {...}, ... ] }`.

If your API version uses different paths or body keys, adjust `BrowseAiApiClient` and the DTOs (`RunTaskRequest`, `BulkRunRequest`, etc.) accordingly.
