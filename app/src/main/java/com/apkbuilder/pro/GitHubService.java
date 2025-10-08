package com.apkbuilder.pro;

import okhttp3.*;
import org.json.JSONObject;
import org.json.JSONArray;
import com.apkbuilder.pro.models.WorkflowResponse;
import java.io.IOException;

import java.util.List;
import java.util.ArrayList;


public class GitHubService {
    private static final String GITHUB_API_BASE = "https://api.github.com";
    private OkHttpClient client;
    private String githubToken;

    // Use dependency injection to set the token
    public GitHubService(String githubToken) {
        this.githubToken = githubToken;
        // Centralize authorization header via Interceptor
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

    /**
     * Creates or updates the CI/CD workflow file in the repository.
     * @param owner The repository owner.
     * @param repo The repository name.
     * @param workflowContent The content of the YAML workflow file.
     */
    public WorkflowResponse createWorkflowFile(String owner, String repo, String workflowContent) throws IOException {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/contents/.github/workflows/android-build.yml";
        // Use android.util.Base64 only if in Android context. For pure Java, use java.util.Base64
        String encodedContent = android.util.Base64.encodeToString(workflowContent.getBytes(), android.util.Base64.NO_WRAP);
        
        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("message", "Add Android CI/CD workflow via APK Builder Pro");
            requestBody.put("content", encodedContent);
            requestBody.put("branch", "main"); // Assuming 'main' branch
        } catch (Exception e) {
            throw new IOException("Error creating request body: " + e.getMessage());
        }

        Request request = new Request.Builder()
                .url(url)
                // Authorization and Accept headers are handled by the Interceptor
                .header("Content-Type", "application/json")
                .put(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 201 || response.code() == 200) { // 201 Created, 200 OK (updated)
                return new WorkflowResponse(true, "CI/CD workflow file created/updated successfully.", null);
            } else {
                String errorBody = response.body() != null ? response.body().string() : "No body";
                throw new IOException("Failed to create workflow file: " + response.code() + " - " + errorBody);
            }
        }
    }

    /**
     * Triggers a repository dispatch event to start the GitHub Action.
     */
    public WorkflowResponse dispatchWorkflow(String owner, String repo, String buildType) throws IOException {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/dispatches";
        
        JSONObject requestBody = new JSONObject();
        JSONObject clientPayload = new JSONObject();
        try {
            // The event type must match what the workflow YAML expects
            requestBody.put("event_type", "run_android_build"); 
            clientPayload.put("build_type", buildType); // Pass the build type (e.g., release)
            requestBody.put("client_payload", clientPayload);
        } catch (Exception e) {
            throw new IOException("Error creating request body for dispatch: " + e.getMessage());
        }

        Request request = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 204) { // 204 No Content for successful dispatch
                return new WorkflowResponse(true, "Build dispatch successful. Polling status...", null);
            } else {
                String errorBody = response.body() != null ? response.body().string() : "No body";
                throw new IOException("Failed to dispatch workflow: " + response.code() + " - " + errorBody);
            }
        }
    }

    /**
     * Fetches the latest workflow run status for a given repository.
     */
    public WorkflowResponse getLatestWorkflowStatus(String owner, String repo) throws IOException {
        // Filter by the main branch and order by created date
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/actions/runs?branch=main&per_page=1&status=in_progress,queued,completed";
        
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 200) {
                String body = response.body().string();
                try {
                    return new WorkflowResponse(new JSONObject(body));
                } catch (Exception e) {
                    throw new IOException("Failed to parse workflow status response: " + e.getMessage());
                }
            } else {
                throw new IOException("Failed to fetch workflow status: " + response.code());
            }
        }
    }

    /**
     * Fetches the list of accessible repositories for the authenticated user.
     */
    public List<String> getRepositories() throws IOException {
        String url = GITHUB_API_BASE + "/user/repos?per_page=100"; // Fetch up to 100 private repos
        List<String> repoList = new ArrayList<>();
        
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 200) {
                String body = response.body().string();
                try {
                    JSONArray repos = new JSONArray(body);
                    for (int i = 0; i < repos.length(); i++) {
                        JSONObject repo = repos.getJSONObject(i);
                        String fullName = repo.getString("full_name"); // e.g., owner/repo_name
                        repoList.add(fullName);
                    }
                    return repoList;
                } catch (Exception e) {
                    throw new IOException("Failed to parse repository list: " + e.getMessage());
                }
            } else {
                String errorBody = response.body() != null ? response.body().string() : "No body";
                throw new IOException("Failed to fetch repositories: " + response.code() + " - " + errorBody);
            }
        }
    }

    /**
     * Checks if a repository is accessible. Does not require token if it's a public repo,
     * but the Interceptor will automatically add the token for private repo verification.
     */
    public boolean verifyRepositoryAccess(String owner, String repo) throws IOException {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo;
        
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            // A 200 OK means the repo exists and is accessible with the token (if private) or is public.
            return response.code() == 200; 
        }
    }

    /**
     * Cancels a running workflow run.
     */
    public WorkflowResponse cancelWorkflowRun(String owner, String repo, String runId) throws IOException {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/actions/runs/" + runId + "/cancel";
        
        Request request = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(RequestBody.create("{}", MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 202) {
                return new WorkflowResponse(true, "Workflow cancellation requested", null);
            } else {
                String errorBody = response.body() != null ? response.body().string() : "No body";
                throw new IOException("Failed to cancel workflow: " + response.code() + " - " + errorBody);
            }
        }
    }
}
