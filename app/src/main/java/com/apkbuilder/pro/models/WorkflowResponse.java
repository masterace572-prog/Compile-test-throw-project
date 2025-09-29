package com.apkbuilder.pro.models;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

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

    // Constructor for workflow status
    public WorkflowResponse(JSONObject jsonResponse) throws JSONException {
        if (jsonResponse.has("workflow_runs")) {
            // This is a workflow runs list response
            JSONArray runs = jsonResponse.getJSONArray("workflow_runs");
            if (runs.length() > 0) {
                JSONObject latestRun = runs.getJSONObject(0);
                parseWorkflowRun(latestRun);
            } else {
                this.success = false;
                this.message = "No workflow runs found";
            }
        } else if (jsonResponse.has("id")) {
            // This is a single workflow run response
            parseWorkflowRun(jsonResponse);
        } else if (jsonResponse.has("content")) {
            // This is a file creation response
            this.success = true;
            this.message = "Workflow file created successfully";
            this.workflowUrl = jsonResponse.getJSONObject("content").getString("html_url");
        }
    }

    private void parseWorkflowRun(JSONObject workflowRun) throws JSONException {
        this.success = true;
        this.runId = workflowRun.getString("id");
        this.status = workflowRun.getString("status");
        this.conclusion = workflowRun.optString("conclusion", "pending");
        this.htmlUrl = workflowRun.getString("html_url");
        this.createdAt = workflowRun.getString("created_at");
        this.updatedAt = workflowRun.getString("updated_at");
        this.workflowName = workflowRun.getJSONObject("workflow").getString("name");
        this.headBranch = workflowRun.getString("head_branch");
        this.event = workflowRun.getString("event");
        this.runNumber = workflowRun.getInt("run_number");
        
        // Set message based on status
        this.message = generateStatusMessage();
    }

    private String generateStatusMessage() {
        switch (status) {
            case "queued":
                return "Workflow is queued and waiting to start";
            case "in_progress":
                return "Workflow is currently running";
            case "completed":
                if ("success".equals(conclusion)) {
                    return "Workflow completed successfully! APK should be sent to Telegram shortly";
                } else if ("failure".equals(conclusion)) {
                    return "Workflow failed. Check GitHub for details";
                } else if ("cancelled".equals(conclusion)) {
                    return "Workflow was cancelled";
                }
                return "Workflow completed with status: " + conclusion;
            default:
                return "Workflow status: " + status;
        }
    }

    // Getters
    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
    public String getWorkflowUrl() { return workflowUrl; }
    public String getStatus() { return status; }
    public String getConclusion() { return conclusion; }
    public String getHtmlUrl() { return htmlUrl; }
    public String getRunId() { return runId; }
    public String getCreatedAt() { return createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public String getWorkflowName() { return workflowName; }
    public String getHeadBranch() { return headBranch; }
    public String getEvent() { return event; }
    public int getRunNumber() { return runNumber; }

    // Utility method to get formatted status for display
    public String getFormattedStatus() {
        StringBuilder sb = new StringBuilder();
        
        sb.append("📊 Workflow Status\n\n");
        sb.append("🏷️ Name: ").append(workflowName).append("\n");
        sb.append("🆔 Run #: ").append(runNumber).append("\n");
        sb.append("📈 Status: ").append(status.toUpperCase()).append("\n");
        
        if (conclusion != null && !conclusion.equals("null")) {
            sb.append("✅ Conclusion: ").append(conclusion.toUpperCase()).append("\n");
        }
        
        sb.append("🎯 Event: ").append(event).append("\n");
        sb.append("🌿 Branch: ").append(headBranch).append("\n");
        sb.append("🕐 Created: ").append(formatDate(createdAt)).append("\n");
        sb.append("🔄 Updated: ").append(formatDate(updatedAt)).append("\n\n");
        
        sb.append("💡 ").append(message).append("\n\n");
        sb.append("🔗 Details: ").append(htmlUrl);
        
        return sb.toString();
    }

    private String formatDate(String isoDate) {
        try {
            // Simple formatting: "2024-01-15T10:30:00Z" -> "Jan 15, 10:30"
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

    // Check if workflow is still active
    public boolean isActive() {
        return "queued".equals(status) || "in_progress".equals(status);
    }

    // Check if workflow completed successfully
    public boolean isSuccessful() {
        return "completed".equals(status) && "success".equals(conclusion);
    }

    // Check if workflow failed
    public boolean isFailed() {
        return "completed".equals(status) && "failure".equals(conclusion);
    }
}