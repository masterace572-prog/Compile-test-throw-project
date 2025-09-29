package com.apkbuilder.pro;

import android.app.ProgressDialog;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import okhttp3.*;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.IOException;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class MainActivity extends AppCompatActivity {

    private EditText repoUrlInput, githubTokenInput, botTokenInput, userIdInput;
    private Spinner buildTypeSpinner;
    private Button buildBtn, testConnectionBtn;
    private TextView statusText;
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
        repoUrlInput = findViewById(R.id.repoUrlInput);
        githubTokenInput = findViewById(R.id.githubTokenInput);
        botTokenInput = findViewById(R.id.botTokenInput);
        userIdInput = findViewById(R.id.userIdInput);
        buildTypeSpinner = findViewById(R.id.buildTypeSpinner);
        buildBtn = findViewById(R.id.buildBtn);
        testConnectionBtn = findViewById(R.id.testConnectionBtn);
        statusText = findViewById(R.id.statusText);

        // Setup build type spinner
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.build_types, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        buildTypeSpinner.setAdapter(adapter);

        buildBtn.setOnClickListener(v -> startBuildProcess());
        testConnectionBtn.setOnClickListener(v -> testTelegramConnection());

        statusText.setText("🚀 Ready to build Android projects!\n\nEnter your details above to start building.");
    }

    private void startBuildProcess() {
        final String repoUrl = repoUrlInput.getText().toString().trim();
        final String githubToken = githubTokenInput.getText().toString().trim();
        final String botToken = botTokenInput.getText().toString().trim();
        final String userId = userIdInput.getText().toString().trim();
        final String buildType = buildTypeSpinner.getSelectedItem().toString().toLowerCase();

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

        new Thread(() -> {
            try {
                // Step 1: Verify repository access
                runOnUiThread(() -> updateStatus("🔍 Checking repository access..."));
                if (!verifyRepoAccess(githubToken)) {
                    throw new Exception("Cannot access repository. Check token permissions.");
                }

                // Step 2: Create or update workflow file
                runOnUiThread(() -> updateStatus("📝 Configuring workflow..."));
                if (!setupWorkflow(githubToken, botToken, userId, buildType)) {
                    throw new Exception("Failed to setup workflow");
                }

                // Step 3: Trigger workflow
                runOnUiThread(() -> updateStatus("🚀 Triggering build..."));
                if (!triggerWorkflow(githubToken)) {
                    throw new Exception("Failed to trigger workflow");
                }

                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    updateStatus("✅ BUILD STARTED! 🎉\n\n" +
                            "📦 Repository: " + currentRepoOwner + "/" + currentRepoName + "\n" +
                            "🔨 Build Type: " + buildType + "\n" +
                            "🏗️ Status: Building on GitHub\n" +
                            "⏰ ETA: 5-10 minutes\n\n" +
                            "📱 APK will be sent to your Telegram\n\n" +
                            "🔍 Monitor: https://github.com/" + currentRepoOwner + "/" + currentRepoName + "/actions");
                    showToast("Build started successfully!");
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    updateStatus("❌ Build Failed\n\nError: " + e.getMessage() + 
                                "\n\nCheck:\n• GitHub token permissions\n• Repository exists\n• All fields are correct");
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
        
        // First check if file exists
        String sha = getFileSha(githubToken, url);
        
        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("message", "Configure APK Builder workflow");
            requestBody.put("content", encodedContent);
            requestBody.put("branch", "main");
            if (sha != null) {
                requestBody.put("sha", sha);
            }
        } catch (JSONException e) {
            throw new IOException("Error creating request: " + e.getMessage());
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
                    return null;
                }
            }
        } catch (Exception e) {
            // File doesn't exist, that's fine
        }
        return null;
    }

    private boolean triggerWorkflow(String githubToken) throws IOException {
        String url = "https://api.github.com/repos/" + currentRepoOwner + "/" + currentRepoName + "/actions/workflows/android-build.yml/dispatches";
        
        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("ref", "main");
        } catch (JSONException e) {
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

        return "name: Android CI with APK Builder\n" +
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
                "          🔨 Built via APK Builder App\n" +
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
            showToast("Enter bot token and user ID");
            return;
        }

        showProgressDialog("Testing Telegram...");

        new Thread(() -> {
            try {
                boolean success = sendTelegramMessage(botToken, userId, "🔧 APK Builder Test\n\nYour Telegram is connected! APKs will be sent here. 🚀");
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    if (success) {
                        updateStatus("✅ Telegram Connected!\n\nTest message sent successfully!");
                        showToast("Telegram working!");
                    } else {
                        updateStatus("❌ Telegram Failed\n\nCheck bot token and user ID");
                        showToast("Telegram test failed");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    updateStatus("❌ Telegram Error: " + e.getMessage());
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
        } catch (JSONException e) {
            throw new IOException("Error creating message");
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
