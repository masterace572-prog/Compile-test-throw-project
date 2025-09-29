package com.apkbuilder.pro;

import android.app.ProgressDialog;
import android.os.Bundle;
import android.widget.ProgressBar;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import okhttp3.*;
import org.json.JSONObject;
import java.io.IOException;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class MainActivity extends AppCompatActivity {

    private TextInputEditText repoUrlInput, githubTokenInput, botTokenInput, userIdInput;
    private AutoCompleteTextView buildTypeSpinner;
    private MaterialButton buildBtn, checkStatusBtn, testConnectionBtn;
    private TextView statusText;
    private ProgressBar progressBar;
    private ProgressDialog progressDialog;
    private OkHttpClient client;
    
    private String currentRepoOwner = "";
    private String currentRepoName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initializeViews();
        client = new OkHttpClient();
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
        checkStatusBtn = findViewById(R.id.checkStatusBtn);
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
        checkStatusBtn.setOnClickListener(v -> checkBuildStatus());
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

        showProgressDialog("Setting up build environment...");
        showProgressBar(true);

        new Thread(() -> {
            try {
                // Step 1: Verify repository access
                runOnUiThread(() -> updateStatus("🔍 Checking repository access..."));
                if (!verifyRepoAccess(githubToken)) {
                    throw new Exception("Cannot access repository. Check:\n• Repository exists\n• GitHub token has repo permissions\n• Repository is not private (or token has access)");
                }

                // Step 2: Create or update workflow file
                runOnUiThread(() -> updateStatus("📝 Configuring workflow..."));
                if (!setupWorkflow(githubToken, botToken, userId, buildType)) {
                    throw new Exception("Failed to setup workflow file");
                }

                // Step 3: Trigger workflow
                runOnUiThread(() -> updateStatus("🚀 Triggering build..."));
                if (!triggerWorkflow(githubToken)) {
                    throw new Exception("Failed to trigger workflow");
                }

                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    showProgressBar(false);
                    updateStatus("✅ BUILD STARTED SUCCESSFULLY! 🎉\n\n" +
                            "📦 Repository: " + currentRepoOwner + "/" + currentRepoName + "\n" +
                            "🔨 Build Type: " + buildType + "\n" +
                            "🏗️ Status: Building on GitHub Actions\n" +
                            "⏰ Estimated Time: 5-10 minutes\n\n" +
                            "📱 APK will be sent to your Telegram automatically\n\n" +
                            "🔍 Monitor Progress:\nhttps://github.com/" + currentRepoOwner + "/" + currentRepoName + "/actions\n\n" +
                            "You can check status anytime using the Status button below.");
                    showToast("Build started successfully! 🚀");
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    showProgressBar(false);
                    updateStatus("❌ Build Failed\n\nError: " + e.getMessage() + 
                                "\n\nPlease check:\n• GitHub token permissions (need repo scope)\n• Repository exists and is accessible\n• All fields are filled correctly\n• Internet connection is stable");
                    showToast("Build failed: " + e.getMessage());
                });
            }
        }).start();
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
                JSONObject json = new JSONObject(body);
                return json.getString("sha");
            }
        } catch (Exception e) {
            // File doesn't exist, that's fine - we'll create new
        }
        return null;
    }

    private boolean triggerWorkflow(String githubToken) throws IOException {
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
            return response.code() == 204;
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

    private void checkBuildStatus() {
        if (currentRepoOwner.isEmpty() || currentRepoName.isEmpty()) {
            showToast("Please start a build first to set repository");
            return;
        }

        String githubToken = githubTokenInput.getText().toString().trim();
        if (githubToken.isEmpty()) {
            showToast("Please enter GitHub token");
            return;
        }

        showProgressDialog("Checking build status...");
        showProgressBar(true);

        new Thread(() -> {
            try {
                String status = getWorkflowStatus(githubToken);
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    showProgressBar(false);
                    updateStatus("📊 Latest Build Status:\n\n" + status);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    showProgressBar(false);
                    updateStatus("❌ Error checking status: " + e.getMessage() + 
                                "\n\nMake sure:\n• Build was started previously\n• GitHub token is valid\n• Repository exists");
                });
            }
        }).start();
    }

    private String getWorkflowStatus(String githubToken) throws IOException {
        String url = "https://api.github.com/repos/" + currentRepoOwner + "/" + currentRepoName + "/actions/runs?per_page=1";
        
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "token " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 200) {
                String body = response.body().string();
                JSONObject json = new JSONObject(body);
                if (json.getJSONArray("workflow_runs").length() > 0) {
                    JSONObject run = json.getJSONArray("workflow_runs").getJSONObject(0);
                    String status = run.getString("status").toUpperCase();
                    String conclusion = run.optString("conclusion", "PENDING");
                    String workflowName = run.getJSONObject("workflow").getString("name");
                    String htmlUrl = run.getString("html_url");
                    String createdAt = run.getString("created_at");
                    
                    StringBuilder statusBuilder = new StringBuilder();
                    statusBuilder.append("🏷️ Workflow: ").append(workflowName).append("\n");
                    statusBuilder.append("📈 Status: ").append(status).append("\n");
                    statusBuilder.append("✅ Conclusion: ").append(conclusion).append("\n");
                    statusBuilder.append("🕐 Started: ").append(formatDate(createdAt)).append("\n");
                    statusBuilder.append("🔗 Details: ").append(htmlUrl).append("\n\n");
                    
                    // Add helpful message based on status
                    if ("COMPLETED".equals(status) && "SUCCESS".equals(conclusion)) {
                        statusBuilder.append("🎉 Build completed successfully!\n");
                        statusBuilder.append("APK should be in your Telegram messages.");
                    } else if ("COMPLETED".equals(status) && "FAILURE".equals(conclusion)) {
                        statusBuilder.append("❌ Build failed.\n");
                        statusBuilder.append("Check the GitHub link for error details.");
                    } else if ("IN_PROGRESS".equals(status)) {
                        statusBuilder.append("🔄 Build is currently running...\n");
                        statusBuilder.append("This usually takes 5-10 minutes.");
                    } else if ("QUEUED".equals(status)) {
                        statusBuilder.append("⏳ Build is queued and will start soon...");
                    }
                    
                    return statusBuilder.toString();
                }
            }
            return "No recent workflow runs found.\nStart a new build using the 'Start Build' button.";
        } catch (Exception e) {
            return "Error retrieving status: " + e.getMessage();
        }
    }

    private String formatDate(String isoDate) {
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
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }
}
