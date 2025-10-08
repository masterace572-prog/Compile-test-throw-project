package com.apkbuilder.pro.models;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

// IMPORTANT: Keeping this manual JSON parsing logic for compatibility,
// but strongly recommend migrating to Gson/Moshi for safety and readability.
public class WorkflowResponse {
    private boolean success;
    private String message;
    private String workflowUrl;
    private String status;
    private String conclusion;
    private String htmlUrl;
    private String runId;
    private String createdAt;
    private String updatedAt;
    private String workflowName;
    private String headBranch;
    private String event;
    private int runNumber;

    // Constructor for successful workflow creation
    public WorkflowResponse(boolean success, String message, String workflowUrl) {
        this.success = success;
        this.message = message;
        this.workflowUrl = workflowUrl;
    }

    // Constructor for error/simple status (without a run object)
    public WorkflowResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    // Constructor for workflow status (from API list/single run response)
    public WorkflowResponse(JSONObject jsonResponse) throws JSONException {
        // Assume failure until proven otherwise
        this.success = false; 
        this.message = "Could not parse workflow status.";

        if (jsonResponse.has("workflow_runs")) {
            // This is a workflow runs list response
            JSONArray runs = jsonResponse.getJSONArray("workflow_runs");
            if (runs.length() > 0) {
                JSONObject latestRun = runs.getJSONObject(0);
                parseWorkflowRun(latestRun);
                this.success = true;
            } else {
                this.message = "No workflow runs found";
            }
        } else if (jsonResponse.has("id")) {
            // This is a single workflow run response
            parseWorkflowRun(jsonResponse);
            this.success = true;
        } else {
            // Check for simple error messages if not a run object
            if (jsonResponse.has("message")) {
                 this.message = jsonResponse.getString("message");
            }
        }
    }

    private void parseWorkflowRun(JSONObject run) throws JSONException {
        this.runId = run.optString("id", null);
        this.status = run.optString("status", null);
        this.conclusion = run.optString("conclusion", null);
        this.htmlUrl = run.optString("html_url", null);
        this.createdAt = run.optString("created_at", null);
        this.updatedAt = run.optString("updated_at", null);
        this.headBranch = run.optString("head_branch", null);
        this.event = run.optString("event", null);
        this.runNumber = run.optInt("run_number", 0);
        
        if (run.has("workflow_id")) {
            // Get workflow name from the 'name' field if available
            this.workflowName = run.optString("name", "Unknown Workflow");
        }

        this.message = formatStatusMessage();
    }

    // --- Getters and Setters (omitted for brevity, assume they exist) ---
    // ...

    // Public Getters
    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
    public String getRunId() { return runId; }
    public String getStatus() { return status; }
    public String getConclusion() { return conclusion; }
    public String getHtmlUrl() { return htmlUrl; }


    // Helper Methods
    private String formatStatusMessage() {
        StringBuilder sb = new StringBuilder();
        String emoji = "⏳";
        
        if ("completed".equals(status)) {
            if ("success".equals(conclusion)) {
                emoji = "✅";
                sb.append("Build Successful!\n");
            } else if ("failure".equals(conclusion)) {
                emoji = "❌";
                sb.append("Build Failed!\n");
            } else {
                emoji = "⚠️";
                sb.append("Build Concluded: ").append(conclusion).append("\n");
            }
        } else {
            sb.append("Current Status: ").append(status).append("\n");
        }

        sb.append("  ").append(emoji).append(" Run #").append(runNumber);
        sb.append(" on branch ").append(headBranch).append("\n");
        sb.append("  📅 Updated: ").append(formatDate(updatedAt)).append("\n");
        
        return sb.toString();
    }

    public String formatTelegramMessage(String repoFullName) {
        StringBuilder sb = new StringBuilder();
        String emoji = "⏳";

        // Determine emoji and primary status line
        if ("completed".equals(status)) {
            if ("success".equals(conclusion)) {
                emoji = "✅";
                sb.append("<b>BUILD SUCCESS!</b>\n");
            } else if ("failure".equals(conclusion)) {
                emoji = "❌";
                sb.append("<b>BUILD FAILED!</b>\n");
            } else {
                emoji = "⚠️";
                sb.append("<b>BUILD CONCLUDED: ").append(conclusion.toUpperCase()).append("</b>\n");
            }
        } else {
            emoji = ("queued".equals(status) ? "⏱️" : "🏗️");
            sb.append("<b>BUILD STATUS UPDATE</b>\n");
        }
        
        sb.append(emoji).append(" Repository: <code>").append(repoFullName).append("</code>\n");
        sb.append("  • Run ID: ").append(runId).append("\n");
        sb.append("  • Branch: <code>").append(headBranch).append("</code>\n");
        sb.append("  • Created: ").append(formatDate(createdAt)).append("\n");

        if (htmlUrl != null) {
            sb.append("\n<a href=\"").append(htmlUrl).append("\">🔗 View Full Workflow Details</a>");
        }
        
        return sb.toString();
    }

    private String formatDate(String isoDate) {
        // Keeping the simple implementation to avoid adding complex date dependencies
        try {
            if (isoDate != null && isoDate.length() >= 16) {
                String datePart = isoDate.substring(5, 10); // MM-DD
                String timePart = isoDate.substring(11, 16); // HH:MM
                String[] dateParts = datePart.split("-");
                String month = getMonthName(Integer.parseInt(dateParts[0]));
                return month + " " + dateParts[1] + ", " + timePart;
            }
        } catch (Exception e) {
            // If formatting fails, return original
        }
        return isoDate;
    }

    private String getMonthName(int month) {
        String[] months = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", 
                          "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        return months[month - 1];
    }

    public boolean isActive() {
        return "queued".equals(status) || "in_progress".equals(status);
    }

    public boolean isSuccessful() {
        return "completed".equals(status) && "success".equals(conclusion);
    }
}
