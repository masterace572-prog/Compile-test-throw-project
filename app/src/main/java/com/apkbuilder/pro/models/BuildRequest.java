package com.apkbuilder.pro.models;

// Consider adding @NonNull annotations for improved safety, but keeping
// the original structure for compatibility.
public class BuildRequest {
    private String repoUrl;
    private String githubToken;
    private String botToken;
    private String userId;
    private String buildType;
    private String repoOwner;
    private String repoName;

    public BuildRequest() {}

    // Getters and Setters
    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }

    public String getGithubToken() { return githubToken; }
    public void setGithubToken(String githubToken) { this.githubToken = githubToken; }

    public String getBotToken() { return botToken; }
    public void setBotToken(String botToken) { this.botToken = botToken; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getBuildType() { return buildType; }
    public void setBuildType(String buildType) { this.buildType = buildType; }

    public String getRepoOwner() { return repoOwner; }
    public void setRepoOwner(String repoOwner) { this.repoOwner = repoOwner; }

    public String getRepoName() { return repoName; }
    public void setRepoName(String repoName) { this.repoName = repoName; }
}
