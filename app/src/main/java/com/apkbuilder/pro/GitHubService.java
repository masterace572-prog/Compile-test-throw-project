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
    private final String githubToken; // Stored token

    // Constructor now requires the token to configure the client
    public GitHubService(String githubToken) {
        this.githubToken = githubToken;
        // Centralize authorization header via Interceptor for cleaner method calls
        this.client = new OkHttpClient.Builder()
            .addInterceptor(chain -> {
                Request original = chain.request();
                Request request = original.newBuilder()
                    .header("Authorization", "token " + this.githubToken)
                    .header("Accept", "application/vnd.github.v3+json")
                    .method(original.method(), original.body())
                    .build();
                return chain.proceed(request);
            })
            .build();
    }

    // Token removed from arguments, as the client handles it
    public WorkflowResponse createWorkflowFile(String owner, String repo, String workflowContent) throws IOException {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/contents/.github/workflows/android-build.yml";
        // Use android.util.Base64 only if in Android context.
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
                // Token headers removed, handled by Interceptor
                .header("Content-Type", "application/json")
                .put(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 201 || response.code() == 200) { // 201 Created, 200 Updated
                String responseBody = response.body().string();
                JSONObject jsonResponse = new JSONObject(responseBody);
                // NOTE: WorkflowResponse constructor needs to handle this JSON structure
                return new WorkflowResponse(true, "Workflow setup successful", null); 
            } else {
                String errorBody = response.body().string();
                throw new IOException("Failed to create workflow: " + response.code() + " - " + errorBody);
            }
        } catch (Exception e) {
            throw new IOException("Error creating workflow: " + e.getMessage());
        }
    }

    // Renamed from triggerWorkflow to dispatchWorkflow and cleaned up
    public WorkflowResponse dispatchWorkflow(String owner, String repo, String buildType) throws IOException {
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
                // Token headers removed, handled by Interceptor
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 204) {
                return new WorkflowResponse(true, "Build dispatch successful. Polling status...", null);
            } else {
                String errorBody = response.body().string();
                throw new IOException("Failed to trigger workflow: " + response.code() + " - " + errorBody);
            }
        }
    }

    // Utility method to fetch all accessible repositories
    public List<String> getRepositories() throws IOException {
        List<String> repoList = new ArrayList<>();
        String url = GITHUB_API_BASE + "/user/repos?per_page=100";
        
        Request request = new Request.Builder()
                .url(url)
                // Token headers removed, handled by Interceptor
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 200) {
                String body = response.body().string();
                JSONArray repos = new JSONArray(body);
                for (int i = 0; i < repos.length(); i++) {
                    JSONObject repo = repos.getJSONObject(i);
                    repoList.add(repo.getString("full_name")); // e.g., owner/repo_name
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


    // Token removed from arguments, simplified for Interceptor
    public boolean verifyRepositoryAccess(String owner, String repo) throws IOException {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo;
        
        Request request = new Request.Builder()
                .url(url)
                // Token headers removed, handled by Interceptor
                .build();

        try (Response response = client.newCall(request).execute()) {
            // A 200 OK means the repo exists and is accessible with the token (if private) or is public.
            return response.code() == 200;
        }
    }
    
    // getLatestWorkflowStatus is the preferred method for polling
    public WorkflowResponse getLatestWorkflowStatus(String owner, String repo) throws IOException {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/actions/runs?branch=main&per_page=1";
        
        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 200) {
                String responseBody = response.body().string();
                JSONObject jsonResponse = new JSONObject(responseBody);
                return new WorkflowResponse(jsonResponse); // WorkflowResponse must be able to parse this
            } else {
                String errorBody = response.body().string();
                throw new IOException("Failed to get workflow status: " + response.code() + " - " + errorBody);
            }
        } catch (Exception e) {
            throw new IOException("Error getting workflow status: " + e.getMessage());
        }
    }
    
    // Keeping this for reference, but getLatestWorkflowStatus is generally better for polling
    public WorkflowResponse getWorkflowRunById(String owner, String repo, String runId) throws IOException {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/actions/runs/" + runId;
        
        Request request = new Request.Builder()
                .url(url)
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
    
    // Token removed from arguments
    public WorkflowResponse cancelWorkflowRun(String owner, String repo, String runId) throws IOException {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/actions/runs/" + runId + "/cancel";
        
        Request request = new Request.Builder()
                .url(url)
                // Token headers removed, handled by Interceptor
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
    
    // The previous methods workflowExists, getWorkflowStatus, and getLatestWorkflowRun 
    // are replaced/consolidated by the new methods above for better design.
}
