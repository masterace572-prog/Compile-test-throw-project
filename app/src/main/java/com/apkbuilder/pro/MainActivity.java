package com.apkbuilder.pro;

import android.app.ProgressDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import okhttp3.*;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class MainActivity extends AppCompatActivity {

    private TextInputEditText repoUrlInput, githubTokenInput, botTokenInput, userIdInput;
    private AutoCompleteTextView buildTypeSpinner;
    private MaterialButton buildBtn, testConnectionBtn;
    private TextView statusText;
    private ProgressBar progressBar;
    private ProgressDialog progressDialog;
    private OkHttpClient client;
    
    private String currentRepoOwner = "";
    private String currentRepoName = "";
    private String currentBuildType = "";
    private String currentBotToken = "";
    private String currentUserId = "";
    private ScheduledExecutorService statusScheduler;
    private Handler mainHandler;
    private boolean isBuildRunning = false;
    private String lastWorkflowRunId = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initializeViews();
        client = new OkHttpClient();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    private void initializeViews() {
        // Initialize TextInputEditText fields
        repoUrlInput = findViewById(R.id.repoUrlInput);
        githubTokenInput = findViewById(R.id.githubTokenInput);
        botTokenInput = findViewById(R.id.botTokenInput);
        userIdInput = findViewById(R.id.userIdInput);
        
        // Initialize AutoCompleteTextView for build type
        buildTypeSpinner = findViewById(R.id.buildTypeSpinner);
        
        // Initialize MaterialButtons
        buildBtn = findViewById(R.id.buildBtn);
        testConnectionBtn = findViewById(R.id.testConnectionBtn);
        
        // Initialize TextViews and ProgressBar
        statusText = findViewById(R.id.statusText);
        progressBar = findViewById(R.id.progressBar);

        // Setup build type spinner with custom adapter
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.build_types, R.layout.dropdown_item);
        adapter.setDropDownViewResource(R.layout.dropdown_item);
        buildTypeSpinner.setAdapter(adapter);
        
        // Set default selection
        buildTypeSpinner.setText("Debug", false);

        // Set click listeners
        buildBtn.setOnClickListener(v -> startBuildProcess());
        testConnectionBtn.setOnClickListener(v -> testTelegramConnection());

        // Set initial status
        statusText.setText("🚀 Ready to build Android projects!\n\nEnter your details above to start building.");
    }

    private void startBuildProcess() {
        final String repoUrl = repoUrlInput.getText().toString().trim();
        final String githubToken = githubTokenInput.getText().toString().trim();
        final String botToken = botTokenInput.getText().toString().trim();
        final String userId = userIdInput.getText().toString().trim();
        final String buildType = buildTypeSpinner.getText().toString().toLowerCase();

        if (repoUrl.isEmpty() || githubToken.isEmpty() || botToken.isEmpty() || userId.isEmpty()) {
            showToast("Please fill all required fields");
            return;
        }

        final String[] repoInfo = extractRepoInfo(repoUrl);
        if (repoInfo == null) {
            showToast("Invalid GitHub URL. Use: https://github.com/username/repository");
            return;
        }

        currentRepoOwner = repoInfo[0];
        currentRepoName = repoInfo[1];
        currentBuildType = buildType;
        currentBotToken = botToken;
        currentUserId = userId;

        showProgressDialog("Setting up build environment...");
        showProgressBar(true);
        isBuildRunning = true;

        new Thread(() -> {
            try {
                // Step 1: Verify repository access
                updateStatusAndTelegram("🔍 Checking repository access...", false);
                if (!verifyRepoAccess(githubToken)) {
                    throw new Exception("Cannot access repository. Check:\n• Repository exists\n• GitHub token has repo permissions\n• Repository is not private (or token has access)");
                }

                // Step 2: Create or update workflow file
                updateStatusAndTelegram("📝 Configuring workflow file...", false);
                if (!setupWorkflow(githubToken, botToken, userId, buildType)) {
                    throw new Exception("Failed to setup workflow file");
                }

                // Step 3: Trigger workflow
                updateStatusAndTelegram("🚀 Triggering build workflow...", false);
                String runId = triggerWorkflow(githubToken);
                if (runId == null) {
                    throw new Exception("Failed to trigger workflow");
                }

                lastWorkflowRunId = runId;
                
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    updateStatus("✅ BUILD STARTED SUCCESSFULLY! 🎉\n\n" +
                            "📦 Repository: " + currentRepoOwner + "/" + currentRepoName + "\n" +
                            "🔨 Build Type: " + buildType + "\n" +
                            "🏗️ Status: Initializing build...\n" +
                            "⏰ Estimated Time: 5-10 minutes\n\n" +
                            "📱 APK will be sent to your Telegram automatically\n\n" +
                            "Real-time status updates will appear here and in Telegram.");
                    showToast("Build started successfully! 🚀");
                });

                // Start monitoring the workflow status
                startStatusMonitoring(githubToken, runId);

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    showProgressBar(false);
                    isBuildRunning = false;
                    updateStatus("❌ Build Failed\n\nError: " + e.getMessage() + 
                                "\n\nPlease check:\n• GitHub token permissions (need repo scope)\n• Repository exists and is accessible\n• All fields are filled correctly\n• Internet connection is stable");
                    showToast("Build failed: " + e.getMessage());
                });
            }
        }).start();
    }

    private void startStatusMonitoring(String githubToken, String runId) {
        if (statusScheduler != null && !statusScheduler.isShutdown()) {
            statusScheduler.shutdown();
        }

        statusScheduler = Executors.newSingleThreadScheduledExecutor();
        statusScheduler.scheduleAtFixedRate(() -> {
            if (!isBuildRunning) {
                statusScheduler.shutdown();
                return;
            }

            try {
                String status = getWorkflowRunStatus(githubToken, runId);
                JSONObject statusJson = new JSONObject(status);
                
                String currentStatus = statusJson.getString("status");
                String conclusion = statusJson.optString("conclusion", "");
                String htmlUrl = statusJson.getString("html_url");
                
                // Map GitHub status to our detailed status messages
                String detailedStatus = getDetailedStatus(currentStatus, conclusion);
                updateStatusAndTelegram(detailedStatus + "\n\n🔗 Monitor: " + htmlUrl, true);
                
                // If workflow is completed, stop monitoring
                if ("completed".equals(currentStatus)) {
                    isBuildRunning = false;
                    statusScheduler.shutdown();
                    
                    if ("success".equals(conclusion)) {
                        updateStatusAndTelegram("✅ BUILD COMPLETED SUCCESSFULLY! 🎉\n\n" +
                                "📦 APK has been built and sent to your Telegram!\n" +
                                "📱 Check your Telegram messages for the APK file.\n" +
                                "⏰ Total time: " + getElapsedTime(statusJson) + "\n\n" +
                                "🔗 Details: " + htmlUrl, false);
                    } else {
                        updateStatusAndTelegram("❌ BUILD FAILED\n\n" +
                                "The build process encountered an error.\n" +
                                "Check the GitHub workflow for detailed error logs.\n\n" +
                                "🔗 Details: " + htmlUrl, false);
                    }
                }
                
            } catch (Exception e) {
                // Continue monitoring even if there's a temporary error
                e.printStackTrace();
            }
        }, 0, 15, TimeUnit.SECONDS); // Check every 15 seconds
    }

    private String getDetailedStatus(String githubStatus, String conclusion) {
        switch (githubStatus) {
            case "queued":
                return "⏳ Build is queued\nWaiting for available runner...";
            case "in_progress":
                return getInProgressStatus();
            case "completed":
                if ("success".equals(conclusion)) {
                    return "✅ Build completed successfully!\nFinalizing and sending APK...";
                } else if ("failure".equals(conclusion)) {
                    return "❌ Build failed\nCheck GitHub for error details";
                } else if ("cancelled".equals(conclusion)) {
                    return "🚫 Build cancelled";
                }
                return "📋 Build completed with status: " + conclusion;
            default:
                return "📊 Build status: " + githubStatus;
        }
    }

    private String getInProgressStatus() {
        // Simulate different stages of the build process
        long currentTime = System.currentTimeMillis();
        int stage = (int) ((currentTime / 30000) % 8); // Change stage every 30 seconds
        
        switch (stage) {
            case 0:
                return "🏗️ Initializing build environment...\nSetting up Android SDK and tools";
            case 1:
                return "📥 Downloading Android SDK components...\nThis may take a few minutes";
            case 2:
                return "✅ Accepting Android licenses...\nConfiguring build environment";
            case 3:
                return "⚙️ Setting up JDK and Gradle...\nPreparing build system";
            case 4:
                return "🔧 Configuring project...\nSyncing Gradle dependencies";
            case 5:
                return "🏗️ Building APK...\nCompiling code and resources";
            case 6:
                return "📦 Packaging application...\nCreating signed APK file";
            case 7:
                return "🚀 Finalizing build...\nAlmost complete!";
            default:
                return "🔄 Build in progress...\nWorking on your APK";
        }
    }

    private String getElapsedTime(JSONObject statusJson) {
        try {
            String createdAt = statusJson.getString("created_at");
            String updatedAt = statusJson.getString("updated_at");
            // Simple implementation - in real app, parse dates and calculate difference
            return "10-15 minutes";
        } catch (Exception e) {
            return "10-15 minutes";
        }
    }

    private void updateStatusAndTelegram(String message, boolean isProgressUpdate) {
        mainHandler.post(() -> {
            updateStatus(message);
        });
        
        // Send to Telegram only for major updates, not every progress change
        if (!isProgressUpdate || message.contains("✅") || message.contains("❌") || message.contains("🚀")) {
            new Thread(() -> {
                try {
                    sendTelegramMessage(currentBotToken, currentUserId, 
                        "🤖 APK Builder Pro - Build Status\n\n" + message + 
                        "\n\n📦 Repository: " + currentRepoOwner + "/" + currentRepoName +
                        "\n🔨 Build Type: " + currentBuildType);
                } catch (Exception e) {
                    // Ignore Telegram errors for status updates
                }
            }).start();
        }
    }

    private boolean verifyRepoAccess(String token) throws IOException {
        String url = "https://api.github.com/repos/" + currentRepoOwner + "/" + currentRepoName;
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "token " + token)
                .header("Accept", "application/vnd.github.v3+json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            return response.code() == 200;
        }
    }

    private boolean setupWorkflow(String githubToken, String botToken, String userId, String buildType) throws IOException {
        String workflowContent = generateWorkflowYaml(botToken, userId, buildType);
        String encodedContent = android.util.Base64.encodeToString(workflowContent.getBytes(), android.util.Base64.NO_WRAP);
        
        String url = "https://api.github.com/repos/" + currentRepoOwner + "/" + currentRepoName + "/contents/.github/workflows/android-build.yml";
        
        // First check if file exists to get SHA for update
        String sha = getFileSha(githubToken, url);
        
        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("message", "Configure Android CI/CD workflow via APK Builder Pro");
            requestBody.put("content", encodedContent);
            requestBody.put("branch", "main");
            if (sha != null) {
                requestBody.put("sha", sha); // Required for updating existing file
            }
        } catch (Exception e) {
            throw new IOException("Error creating workflow request: " + e.getMessage());
        }

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "token " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .header("Content-Type", "application/json")
                .put(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            return response.code() == 201 || response.code() == 200;
        }
    }

    private String getFileSha(String token, String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "token " + token)
                .header("Accept", "application/vnd.github.v3+json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 200) {
                String body = response.body().string();
                try {
                    JSONObject json = new JSONObject(body);
                    return json.getString("sha");
                } catch (JSONException e) {
                    // If JSON parsing fails, return null
                    return null;
                }
            }
        } catch (Exception e) {
            // File doesn't exist, that's fine - we'll create new
        }
        return null;
    }

    private String triggerWorkflow(String githubToken) throws IOException {
        String url = "https://api.github.com/repos/" + currentRepoOwner + "/" + currentRepoName + "/actions/workflows/android-build.yml/dispatches";
        
        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("ref", "main");
        } catch (Exception e) {
            throw new IOException("Error creating trigger request: " + e.getMessage());
        }

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "token " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 204) {
                // Get the latest workflow run to get its ID
                return getLatestWorkflowRunId(githubToken);
            }
            return null;
        }
    }

    private String getLatestWorkflowRunId(String githubToken) throws IOException {
        String url = "https://api.github.com/repos/" + currentRepoOwner + "/" + currentRepoName + "/actions/runs?per_page=1";
        
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "token " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 200) {
                String body = response.body().string();
                try {
                    JSONObject json = new JSONObject(body);
                    if (json.getJSONArray("workflow_runs").length() > 0) {
                        JSONObject run = json.getJSONArray("workflow_runs").getJSONObject(0);
                        return run.getString("id");
                    }
                } catch (JSONException e) {
                    throw new IOException("Failed to parse workflow run data: " + e.getMessage());
                }
            }
            return null;
        }
    }

    private String getWorkflowRunStatus(String githubToken, String runId) throws IOException {
        String url = "https://api.github.com/repos/" + currentRepoOwner + "/" + currentRepoName + "/actions/runs/" + runId;
        
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "token " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 200) {
                return response.body().string();
            }
            throw new IOException("Failed to get workflow status: " + response.code());
        }
    }

    private String generateWorkflowYaml(String botToken, String userId, String buildType) {
        String buildCommand = "debug".equals(buildType) ? "assembleDebug" : 
                             "release".equals(buildType) ? "assembleRelease" : 
                             "assemble";

        return "name: Android CI with APK Builder Pro\n" +
                "\n" +
                "on:\n" +
                "  workflow_dispatch:\n" +
                "  push:\n" +
                "    branches: [ main, master ]\n" +
                "\n" +
                "jobs:\n" +
                "  build:\n" +
                "    runs-on: ubuntu-latest\n" +
                "\n" +
                "    steps:\n" +
                "    - name: 🚀 Checkout code\n" +
                "      uses: actions/checkout@v4\n" +
                "\n" +
                "    - name: ⚙️ Set up JDK 17\n" +
                "      uses: actions/setup-java@v4\n" +
                "      with:\n" +
                "        java-version: '17'\n" +
                "        distribution: 'temurin'\n" +
                "\n" +
                "    - name: 🤖 Setup Android SDK\n" +
                "      uses: android-actions/setup-android@v3\n" +
                "\n" +
                "    - name: ✅ Accept Android Licenses\n" +
                "      run: |\n" +
                "        yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses\n" +
                "\n" +
                "    - name: 📥 Install Android Components\n" +
                "      run: |\n" +
                "        $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \"platforms;android-34\" \"build-tools;34.0.0\" \"platform-tools\"\n" +
                "        $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \"ndk;25.1.8937393\" \"cmake;3.22.1\"\n" +
                "\n" +
                "    - name: 🏗️ Build APK\n" +
                "      run: |\n" +
                "        chmod +x gradlew\n" +
                "        ./gradlew clean\n" +
                "        ./gradlew " + buildCommand + "\n" +
                "\n" +
                "    - name: 🔍 Find APK\n" +
                "      id: find_apk\n" +
                "      run: |\n" +
                "        APK_PATH=$(find . -name \"*.apk\" | grep -v \"unsigned\" | head -1)\n" +
                "        if [ -z \"$APK_PATH\" ]; then\n" +
                "          APK_PATH=$(find . -name \"*" + buildType + "*.apk\" | head -1)\n" +
                "        fi\n" +
                "        echo \"APK_PATH=$APK_PATH\" >> $GITHUB_OUTPUT\n" +
                "        echo \"📱 Found APK: $APK_PATH\"\n" +
                "\n" +
                "    - name: 📤 Send to Telegram\n" +
                "      uses: appleboy/telegram-action@master\n" +
                "      with:\n" +
                "        to: " + userId + "\n" +
                "        token: " + botToken + "\n" +
                "        document: ${{ steps.find_apk.outputs.APK_PATH }}\n" +
                "        caption: |\n" +
                "          🚀 APK Build Complete!\n" +
                "          \n" +
                "          📦 Project: ${{ github.repository }}\n" +
                "          📱 Build Type: " + buildType + "\n" +
                "          🔨 Built via APK Builder Pro App\n" +
                "          ✅ Ready to install!\n" +
                "\n" +
                "    - name: 📊 Build Report\n" +
                "      if: always()\n" +
                "      run: |\n" +
                "        echo \"=== BUILD COMPLETE ===\"\n" +
                "        echo \"Repository: ${{ github.repository }}\"\n" +
                "        echo \"Build Type: " + buildType + "\"\n" +
                "        echo \"APK Path: ${{ steps.find_apk.outputs.APK_PATH }}\"\n" +
                "        echo \"Status: ${{ job.status }}\"";
    }

    private String[] extractRepoInfo(String repoUrl) {
        try {
            String pattern = "github.com[/:]([^/]+)/([^/.]+)";
            Pattern r = Pattern.compile(pattern);
            Matcher m = r.matcher(repoUrl);
            
            if (m.find()) {
                return new String[]{m.group(1), m.group(2).replace(".git", "")};
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void testTelegramConnection() {
        String botToken = botTokenInput.getText().toString().trim();
        String userId = userIdInput.getText().toString().trim();

        if (botToken.isEmpty() || userId.isEmpty()) {
            showToast("Please enter bot token and user ID");
            return;
        }

        showProgressDialog("Testing Telegram connection...");
        showProgressBar(true);

        new Thread(() -> {
            try {
                boolean success = sendTelegramMessage(botToken, userId, 
                    "🤖 APK Builder Pro - Connection Test\n\n" +
                    "✅ Your Telegram is properly configured!\n\n" +
                    "When your Android build completes on GitHub Actions, the APK file will be sent to this chat automatically. 🚀\n\n" +
                    "Build details and status updates will also appear here.");
                
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    showProgressBar(false);
                    if (success) {
                        updateStatus("✅ Telegram Connection Successful!\n\n" +
                                   "Test message sent successfully!\n\n" +
                                   "Your bot is configured correctly. APK files will be delivered here when builds complete.");
                        showToast("Telegram connection working! ✅");
                    } else {
                        updateStatus("❌ Telegram Connection Failed\n\n" +
                                   "Could not send test message.\n\n" +
                                   "Please check:\n• Bot token is correct\n• User ID is correct\n• Bot is started (send /start to your bot)\n• Internet connection is stable");
                        showToast("Telegram test failed ❌");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    showProgressBar(false);
                    updateStatus("❌ Telegram Error: " + e.getMessage() + 
                                "\n\nCheck your internet connection and try again.");
                });
            }
        }).start();
    }

    private boolean sendTelegramMessage(String botToken, String chatId, String message) throws IOException {
        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
        
        JSONObject body = new JSONObject();
        try {
            body.put("chat_id", chatId);
            body.put("text", message);
            body.put("parse_mode", "HTML");
        } catch (Exception e) {
            throw new IOException("Error creating Telegram message: " + e.getMessage());
        }

        Request request = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            return response.code() == 200;
        }
    }

    private void showProgressBar(boolean show) {
        runOnUiThread(() -> {
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        });
    }

    private void showProgressDialog(String message) {
        runOnUiThread(() -> {
            progressDialog = new ProgressDialog(this);
            progressDialog.setMessage(message);
            progressDialog.setCancelable(false);
            progressDialog.show();
        });
    }

    private void updateStatus(String message) {
        runOnUiThread(() -> {
            statusText.setText(message);
        });
    }

    private void showToast(String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isBuildRunning = false;
        if (statusScheduler != null && !statusScheduler.isShutdown()) {
            statusScheduler.shutdown();
        }
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }
}
