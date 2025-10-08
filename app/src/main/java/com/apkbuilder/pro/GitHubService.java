package com.apkbuilder.pro;

import okhttp3.*;
import org.json.JSONObject;
import org.json.JSONArray;
import com.apkbuilder.pro.models.WorkflowResponse;
import java.io.IOException;
// FIX: Add missing collection imports
import java.util.List; 
import java.util.ArrayList; 

public class GitHubService {
    private static final String GITHUB_API_BASE = "https://api.github.com";
    private OkHttpClient client;

    public GitHubService() {
        this.client = new OkHttpClient();
    }

    /**
     * Creates or updates the Android CI/CD workflow file in the repository.
     */
    public WorkflowResponse createWorkflowFile(String owner, String repo, String token, String workflowContent) throws IOException {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/contents/.github/workflows/android-build.yml";
        // NOTE: Ensure 'android.util.Base64' is available in your Android environment
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
            if (response.code() == 201 || response.code() == 200) { // 201 Created, 200 Updated
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

    /**
     * Triggers the workflow using a repository_dispatch event with the build type payload.
     */
    public WorkflowResponse dispatchWorkflow(String owner, String repo, String token, String buildType) throws IOException {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/dispatches";
        
        JSONObject requestBody = new JSONObject();
        JSONObject clientPayload = new JSONObject();
        try {
            // Must match the event_type expected by the YAML workflow
            requestBody.put("event_type", "run_android_build"); 
            clientPayload.put("build_type", buildType); // Pass the build type
            requestBody.put("client_payload", clientPayload);
        } catch (Exception e) {
            throw new IOException("Error creating request body for dispatch: " + e.getMessage());
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
                // Return success immediately, polling starts in MainActivity
                return new WorkflowResponse(true, "Build dispatch successful. Polling status...", null);
            } else {
                String errorBody = response.body().string();
                throw new IOException("Failed to trigger workflow: " + response.code() + " - " + errorBody);
            }
        }
    }
    
    /**
     * Fetches a list of accessible repositories for the authenticated user.
     */
    public List<String> getRepositories(String token) throws IOException {
        List<String> repoList = new ArrayList<>();
        // Fetch all repos where the user is an owner or collaborator
        String url = GITHUB_API_BASE + "/user/repos?per_page=100&affiliation=owner,collaborator"; 
        
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "token " + token)
                .header("Accept", "application/vnd.github.v3+json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 200) {
                String body = response.body().string();
                JSONArray repos = new JSONArray(body);
                for (int i = 0; i < repos.length(); i++) {
                    JSONObject repo = repos.getJSONObject(i);
                    // Add full_name (e.g., owner/repo_name)
                    repoList.add(repo.getString("full_name")); 
                }
                return repoList;
            } else {
                String errorBody = response.body().string();
                throw new IOException("Failed to fetch repositories: " + response.code() + " - " + errorBody);
            }
        } catch (org.json.JSONException e) {
             throw new IOException("Error parsing repository list: " + e.getMessage());
        }
    }

    /**
     * Gets the latest workflow run status for the main branch.
     */
    public WorkflowResponse getLatestWorkflowStatus(String owner, String repo, String token) throws IOException {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/actions/runs?branch=main&per_page=1";
        
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "token " + token)
                .header("Accept", "application/vnd.github.v3+json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 200) {
                String responseBody = response.body().string();
                JSONObject jsonResponse = new JSONObject(responseBody);
                // WorkflowResponse constructor handles parsing the runs list
                return new WorkflowResponse(jsonResponse); 
            } else {
                String errorBody = response.body().string();
                throw new IOException("Failed to get workflow status: " + response.code() + " - " + errorBody);
            }
        } catch (Exception e) {
            throw new IOException("Error getting workflow status: " + e.getMessage());
        }
    }
    
    /**
     * Verifies that the given repository exists and is accessible.
     */
    public boolean verifyRepositoryAccess(String owner, String repo, String token) throws IOException {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo;
        
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "token " + token)
                .header("Accept", "application/vnd.github.v3+json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            // A 200 OK means the repo exists and is accessible with the token
            return response.code() == 200; 
        }
    }

    // Keeping existing methods for completeness, though some are duplicates of the new logic:
    
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
