package com.apkbuilder.pro;

import okhttp3.*;
import org.json.JSONObject;
import org.json.JSONArray;
import com.apkbuilder.pro.models.WorkflowResponse;
import java.io.IOException;

public class GitHubService {
    private static final String GITHUB_API_BASE = "https://api.github.com";
    private OkHttpClient client;

    public GitHubService() {
        this.client = new OkHttpClient();
    }

    public WorkflowResponse createWorkflowFile(String owner, String repo, String token, String workflowContent) throws IOException {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/contents/.github/workflows/android-build.yml";
        String encodedContent = android.util.Base64.encodeToString(workflowContent.getBytes(), android.util.Base64.NO_WRAP);
        
        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("message", "Add Android CI/CD workflow via APK Builder Pro");
            requestBody.put("content", encodedContent);
            requestBody.put("branch", "main");
        } catch (Exception e) {
            throw new IOException("Error creating request body: " + e.getMessage());
        }

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "token " + token)
                .header("Accept", "application/vnd.github.v3+json")
                .header("Content-Type", "application/json")
                .put(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 201) {
                String responseBody = response.body().string();
                JSONObject jsonResponse = new JSONObject(responseBody);
                return new WorkflowResponse(jsonResponse);
            } else {
                String errorBody = response.body().string();
                throw new IOException("Failed to create workflow: " + response.code() + " - " + errorBody);
            }
        } catch (Exception e) {
            throw new IOException("Error creating workflow: " + e.getMessage());
        }
    }

    public WorkflowResponse triggerWorkflow(String owner, String repo, String token) throws IOException {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/actions/workflows/android-build.yml/dispatches";
        
        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("ref", "main");
        } catch (Exception e) {
            throw new IOException("Error creating request body: " + e.getMessage());
        }

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "token " + token)
                .header("Accept", "application/vnd.github.v3+json")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 204) {
                // Successfully triggered, now get the workflow run info
                return getLatestWorkflowRun(owner, repo, token);
            } else {
                String errorBody = response.body().string();
                throw new IOException("Failed to trigger workflow: " + response.code() + " - " + errorBody);
            }
        }
    }

    public boolean workflowExists(String owner, String repo, String token) throws IOException {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/contents/.github/workflows/android-build.yml";
        
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "token " + token)
                .header("Accept", "application/vnd.github.v3+json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            return response.code() == 200;
        }
    }

    public WorkflowResponse getWorkflowStatus(String owner, String repo, String token) throws IOException {
        return getLatestWorkflowRun(owner, repo, token);
    }

    private WorkflowResponse getLatestWorkflowRun(String owner, String repo, String token) throws IOException {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/actions/runs?per_page=1";
        
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "token " + token)
                .header("Accept", "application/vnd.github.v3+json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 200) {
                String responseBody = response.body().string();
                JSONObject jsonResponse = new JSONObject(responseBody);
                return new WorkflowResponse(jsonResponse);
            } else {
                String errorBody = response.body().string();
                throw new IOException("Failed to get workflow status: " + response.code() + " - " + errorBody);
            }
        } catch (Exception e) {
            throw new IOException("Error getting workflow status: " + e.getMessage());
        }
    }

    public WorkflowResponse getWorkflowRunById(String owner, String repo, String token, String runId) throws IOException {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/actions/runs/" + runId;
        
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "token " + token)
                .header("Accept", "application/vnd.github.v3+json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 200) {
                String responseBody = response.body().string();
                JSONObject jsonResponse = new JSONObject(responseBody);
                return new WorkflowResponse(jsonResponse);
            } else {
                String errorBody = response.body().string();
                throw new IOException("Failed to get workflow run: " + response.code() + " - " + errorBody);
            }
        } catch (Exception e) {
            throw new IOException("Error getting workflow run: " + e.getMessage());
        }
    }
    
    public boolean verifyRepositoryAccess(String owner, String repo, String token) throws IOException {
    String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo;
    
    Request request = new Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .build();

    try (Response response = client.newCall(request).execute()) {
        return response.code() == 200; // 200 means public repo exists
    }
}

    public WorkflowResponse cancelWorkflowRun(String owner, String repo, String token, String runId) throws IOException {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/actions/runs/" + runId + "/cancel";
        
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "token " + token)
                .header("Accept", "application/vnd.github.v3+json")
                .header("Content-Type", "application/json")
                .post(RequestBody.create("{}", MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 202) {
                return new WorkflowResponse(true, "Workflow cancellation requested", null);
            } else {
                String errorBody = response.body().string();
                throw new IOException("Failed to cancel workflow: " + response.code() + " - " + errorBody);
            }
        }
    }
}