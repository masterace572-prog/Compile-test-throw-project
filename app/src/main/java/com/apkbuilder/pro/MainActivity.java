package com.apkbuilder.pro;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.apkbuilder.pro.models.BuildRequest;
import com.apkbuilder.pro.models.BuildStage;
import com.apkbuilder.pro.models.WorkflowResponse;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "APKBuilderPro";

    // UI Components (Updated to Material Design)
    private TextInputEditText githubTokenInput, botTokenInput, userIdInput;
    private AutoCompleteTextView repoSpinner, buildTypeSpinner;
    private MaterialButton buildBtn, testConnectionBtn, fetchReposBtn;
    private TextView statusText;
    private LinearProgressIndicator linearProgressBar;
    
    // Services and Data
    private GitHubService gitHubService;
    private TelegramService telegramService = new TelegramService();
    private BuildRequest buildRequest = new BuildRequest();
    
    // Concurrency and State
    private ScheduledExecutorService statusScheduler;
    private Handler mainHandler;
    private BuildStage currentStage = BuildStage.IDLE;
    private String lastWorkflowRunId = "";
    private List<String> availableRepos = new ArrayList<>();
    
    // Standard Android Build Types
    private final List<String> buildTypes = Arrays.asList("release", "debug", "staging");


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        mainHandler = new Handler(Looper.getMainLooper());

        // 1. Initialize UI components
        initializeUI();

        // 2. Setup Listeners
        setupListeners();
        
        // 3. Setup Dropdowns
        setupDropdowns();
        
        updateStage(BuildStage.IDLE, "Welcome! Enter your GitHub token and press 'Fetch Repositories'.");
    }

    private void initializeUI() {
        // Material Input Layouts
        githubTokenInput = findViewById(R.id.githubTokenInput);
        botTokenInput = findViewById(R.id.botTokenInput);
        userIdInput = findViewById(R.id.userIdInput);

        // AutoCompleteTextViews (as Exposed Dropdown Menus)
        repoSpinner = findViewById(R.id.repoSpinner);
        buildTypeSpinner = findViewById(R.id.buildTypeSpinner);

        // Material Buttons
        buildBtn = findViewById(R.id.buildBtn);
        testConnectionBtn = findViewById(R.id.testConnectionBtn);
        fetchReposBtn = findViewById(R.id.fetchReposBtn);

        // Status
        statusText = findViewById(R.id.statusText);
        linearProgressBar = findViewById(R.id.linearProgressBar);
    }
    
    private void setupDropdowns() {
        // Build Type Dropdown
        ArrayAdapter<String> buildTypeAdapter = new ArrayAdapter<>(
            this, 
            com.google.android.material.R.layout.support_simple_spinner_dropdown_item, // Standard Material Dropdown Item
            buildTypes
        );
        buildTypeSpinner.setAdapter(buildTypeAdapter);
        buildTypeSpinner.setText(buildTypes.get(0), false); // Set default to 'release'
        buildRequest.setBuildType(buildTypes.get(0));
    }
    
    private void setupListeners() {
        // Text Watchers to enable/disable buttons
        TextWatcher tokenWatcher = new SimpleTextWatcher(() -> {
            boolean hasGithubToken = !githubTokenInput.getText().toString().trim().isEmpty();
            boolean hasTelegramDetails = !botTokenInput.getText().toString().trim().isEmpty() && 
                                         !userIdInput.getText().toString().trim().isEmpty();
            
            fetchReposBtn.setEnabled(hasGithubToken);
            testConnectionBtn.setEnabled(hasTelegramDetails);
            
            // Re-check main build button status
            checkBuildButtonState(); 
        });

        githubTokenInput.addTextChangedListener(tokenWatcher);
        botTokenInput.addTextChangedListener(tokenWatcher);
        userIdInput.addTextChangedListener(tokenWatcher);

        // Dropdown selection listeners
        repoSpinner.setOnItemClickListener((parent, view, position, id) -> {
            String selectedRepo = (String) parent.getItemAtPosition(position);
            parseRepoUrl(selectedRepo);
            checkBuildButtonState();
        });
        
        buildTypeSpinner.setOnItemClickListener((parent, view, position, id) -> {
            String selectedType = (String) parent.getItemAtPosition(position);
            buildRequest.setBuildType(selectedType);
            checkBuildButtonState();
        });

        // Button Click Listeners
        fetchReposBtn.setOnClickListener(v -> fetchRepositories());
        testConnectionBtn.setOnClickListener(v -> testTelegramConnection());
        buildBtn.setOnClickListener(v -> startBuildProcess());
    }
    
    /**
     * Helper to enable the main build button only when all required fields are set.
     */
    private void checkBuildButtonState() {
        boolean isRepoSelected = buildRequest.getRepoName() != null && !buildRequest.getRepoName().isEmpty();
        boolean hasGithubToken = !githubTokenInput.getText().toString().trim().isEmpty();
        boolean isBuildTypeSelected = buildRequest.getBuildType() != null && !buildRequest.getBuildType().isEmpty();
        
        buildBtn.setEnabled(isRepoSelected && hasGithubToken && isBuildTypeSelected && currentStage == BuildStage.IDLE);
    }

    /**
     * Parses the selected repository string (owner/repo) into the BuildRequest object.
     */
    private void parseRepoUrl(String fullRepoName) {
        if (fullRepoName != null && fullRepoName.contains("/")) {
            String[] parts = fullRepoName.split("/");
            buildRequest.setRepoOwner(parts[0]);
            buildRequest.setRepoName(parts[1]);
            buildRequest.setRepoUrl("https://github.com/" + fullRepoName);
        } else {
            buildRequest.setRepoOwner(null);
            buildRequest.setRepoName(null);
            buildRequest.setRepoUrl(null);
        }
    }

    /**
     * Updates the UI to reflect the current BuildStage.
     */
    private void updateStage(BuildStage newStage, String customMessage) {
        this.currentStage = newStage;
        showProgressBar(newStage.getStageId() > 0 && newStage.getStageId() < BuildStage.COMPLETED.getStageId());
        
        String message = (customMessage != null) ? customMessage : newStage.getMessage();
        statusText.setText(message);
        
        Log.d(TAG, "Stage updated: " + newStage.name() + " - " + message);
        checkBuildButtonState(); // Re-check button state on stage change
    }
    
    // =========================================================================
    // API & Build Logic
    // =========================================================================

    private void fetchRepositories() {
        closeKeyboard();
        String token = githubTokenInput.getText().toString().trim();
        if (token.isEmpty()) {
            updateStage(BuildStage.IDLE, "Please enter a GitHub token first.");
            return;
        }

        updateStage(BuildStage.FETCHING_REPOS, BuildStage.FETCHING_REPOS.getMessage());
        buildRequest.setGithubToken(token);
        
        // Initialize GitHub service with the token
        gitHubService = new GitHubService(token);

        // Using a new Thread is a simple way to offload network, but ExecutorService is better
        new Thread(() -> {
            try {
                List<String> repos = gitHubService.getRepositories();
                availableRepos = repos;
                
                mainHandler.post(() -> {
                    // Update Repo Dropdown
                    ArrayAdapter<String> repoAdapter = new ArrayAdapter<>(
                        MainActivity.this, 
                        com.google.android.material.R.layout.support_simple_spinner_dropdown_item, 
                        availableRepos
                    );
                    repoSpinner.setAdapter(repoAdapter);

                    if (repos.isEmpty()) {
                        updateStage(BuildStage.IDLE, "❌ No repositories found. Check your token scope or try again.");
                    } else {
                        updateStage(BuildStage.IDLE, "✅ Repositories fetched successfully. Now select one and configure Telegram.");
                    }
                });
            } catch (IOException e) {
                Log.e(TAG, "Error fetching repos", e);
                mainHandler.post(() -> {
                    updateStage(BuildStage.IDLE, "❌ Failed to fetch repositories: " + e.getMessage());
                });
            }
        }).start();
    }
    
    private void testTelegramConnection() {
        closeKeyboard();
        String botToken = botTokenInput.getText().toString().trim();
        String userId = userIdInput.getText().toString().trim();

        if (botToken.isEmpty() || userId.isEmpty()) {
            Toast.makeText(this, "Please enter both Telegram Bot Token and User ID.", Toast.LENGTH_SHORT).show();
            return;
        }

        updateStage(BuildStage.TESTING_TELEGRAM, BuildStage.TESTING_TELEGRAM.getMessage());
        buildRequest.setBotToken(botToken);
        buildRequest.setUserId(userId);

        new Thread(() -> {
            try {
                boolean success = telegramService.testConnection(botToken, userId);
                
                mainHandler.post(() -> {
                    if (success) {
                        Toast.makeText(MainActivity.this, "Telegram test successful! ✅", Toast.LENGTH_LONG).show();
                        updateStage(BuildStage.IDLE, "✅ Telegram connection verified. Ready to start the build!");
                    } else {
                        updateStage(BuildStage.IDLE, "❌ Telegram test failed. Check your token, user ID, or bot's privacy settings.");
                    }
                });
            } catch (IOException e) {
                Log.e(TAG, "Error testing Telegram", e);
                mainHandler.post(() -> {
                    updateStage(BuildStage.IDLE, "❌ Telegram API Error: " + e.getMessage());
                });
            }
        }).start();
    }

    private void startBuildProcess() {
        closeKeyboard();
        // Final checks
        String repoName = buildRequest.getRepoName();
        String repoOwner = buildRequest.getRepoOwner();
        String buildType = buildRequest.getBuildType();
        String botToken = botTokenInput.getText().toString().trim();
        String userId = userIdInput.getText().toString().trim();
        String githubToken = githubTokenInput.getText().toString().trim();
        
        if (repoName == null || repoOwner == null || githubToken.isEmpty() || buildType.isEmpty() || botToken.isEmpty() || userId.isEmpty()) {
             Toast.makeText(this, "Please complete all fields and select a repository.", Toast.LENGTH_LONG).show();
             return;
        }
        
        // Finalize BuildRequest
        buildRequest.setBotToken(botToken);
        buildRequest.setUserId(userId);
        buildRequest.setGithubToken(githubToken); // Important for the GitHubService initialization

        // Reset state for a new build
        lastWorkflowRunId = "";
        
        // Start the sequential process
        new Thread(this::runBuildSequence).start();
    }
    
    private void runBuildSequence() {
        try {
            // 1. VERIFYING_ACCESS
            updateStage(BuildStage.VERIFYING_ACCESS, null);
            if (!gitHubService.verifyRepositoryAccess(buildRequest.getRepoOwner(), buildRequest.getRepoName())) {
                throw new IOException("Repository access failed. Check token permissions (repo scope).");
            }
            
            // 2. SETUP_WORKFLOW
            updateStage(BuildStage.SETUP_WORKFLOW, null);
            String workflowContent = generateWorkflowContent(buildRequest.getBuildType(), buildRequest.getBotToken(), buildRequest.getUserId());
            gitHubService.createWorkflowFile(buildRequest.getRepoOwner(), buildRequest.getRepoName(), workflowContent);
            
            // 3. TRIGGER_BUILD
            updateStage(BuildStage.TRIGGER_BUILD, null);
            gitHubService.dispatchWorkflow(buildRequest.getRepoOwner(), buildRequest.getRepoName(), buildRequest.getBuildType());

            // 4. POLLING_STATUS
            mainHandler.post(() -> {
                updateStage(BuildStage.POLLING_STATUS, "🚀 Build triggered successfully. Monitoring status...");
                startStatusPolling();
            });
            
        } catch (Exception e) {
            Log.e(TAG, "Build Sequence Failed", e);
            mainHandler.post(() -> {
                updateStage(BuildStage.FAILED, "❌ BUILD FAILED: " + e.getMessage());
                if (statusScheduler != null) statusScheduler.shutdownNow();
            });
        }
    }
    
    private void startStatusPolling() {
        if (statusScheduler != null && !statusScheduler.isShutdown()) {
            statusScheduler.shutdownNow();
        }
        
        // Use a ScheduledExecutorService for periodic tasks (polling)
        statusScheduler = Executors.newSingleThreadScheduledExecutor();
        statusScheduler.scheduleAtFixedRate(this::pollStatus, 5, 10, TimeUnit.SECONDS); // Start after 5s, repeat every 10s
    }

    private void pollStatus() {
        // Run network call on a worker thread
        try {
            WorkflowResponse response = gitHubService.getLatestWorkflowStatus(
                buildRequest.getRepoOwner(), 
                buildRequest.getRepoName()
            );

            mainHandler.post(() -> {
                statusText.setText(response.getMessage()); // Update status text with detailed response
                
                if (response.isActive()) {
                    // Telegram update only on the first run ID detection (optional)
                    if (lastWorkflowRunId.isEmpty() && response.getRunId() != null) {
                        lastWorkflowRunId = response.getRunId();
                        sendTelegramNotification(response, BuildStage.POLLING_STATUS.getTelegramStatus());
                    }
                    // Keep polling
                } else if (response.isSuccessful() || response.getConclusion() != null) {
                    // Final status reached (Success/Failure/Cancelled)
                    statusScheduler.shutdownNow();
                    updateStage(BuildStage.COMPLETED, response.getMessage());
                    sendTelegramNotification(response, response.isSuccessful() ? BuildStage.COMPLETED.getTelegramStatus() : BuildStage.FAILED.getTelegramStatus());
                } else {
                    // Unknown or no run found yet, keep waiting
                    statusText.setText(currentStage.getMessage() + " (Waiting for GitHub to register run...)");
                }
            });
        } catch (IOException e) {
            Log.e(TAG, "Polling Error", e);
            mainHandler.post(() -> {
                statusScheduler.shutdownNow();
                updateStage(BuildStage.FAILED, "❌ Polling Error: " + e.getMessage());
            });
        }
    }
    
    private void sendTelegramNotification(WorkflowResponse response, String statusTitle) {
        String message = "<b>" + statusTitle + "</b>\n\n" + response.formatTelegramMessage(
            buildRequest.getRepoOwner() + "/" + buildRequest.getRepoName()
        );
        
        new Thread(() -> {
            try {
                telegramService.sendMessage(buildRequest.getBotToken(), buildRequest.getUserId(), message);
            } catch (IOException e) {
                Log.e(TAG, "Failed to send Telegram message", e);
                // Optionally show a toast for failed Telegram send
            }
        }).start();
    }


    // =========================================================================
    // UI Helpers
    // =========================================================================
    
    private void showProgressBar(boolean show) {
        linearProgressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }
    
    private void closeKeyboard() {
        View view = this.getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }
    
    // Simple utility class for cleaner TextWatcher implementation
    private static class SimpleTextWatcher implements TextWatcher {
        private final Runnable callback;
        public SimpleTextWatcher(Runnable callback) { this.callback = callback; }
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override
        public void afterTextChanged(Editable s) { callback.run(); }
    }
    
    // Placeholder method for generating workflow content. This should be in a separate utility.
    private String generateWorkflowContent(String buildType, String botToken, String userId) {
        // This is a minimal example. You will need to customize this YAML for your project.
        return "name: Android CI/CD Build\n" +
               "on:\n" +
               "  repository_dispatch:\n" +
               "    types: [run_android_build]\n" +
               "    inputs:\n" +
               "      build_type:\n" +
               "        description: 'Build variant (e.g., release, debug)'\n" +
               "        required: true\n" +
               "        default: 'release'\n" +
               "\n" +
               "jobs:\n" +
               "  build:\n" +
               "    runs-on: ubuntu-latest\n" +
               "    steps:\n" +
               "      - name: Checkout Code\n" +
               "        uses: actions/checkout@v4\n" +
               "      - name: Set up JDK 17\n" +
               "        uses: actions/setup-java@v4\n" +
               "        with:\n" +
               "          distribution: 'temurin'\n" +
               "          java-version: '17'\n" +
               "      - name: Grant execute permission for gradlew\n" +
               "        run: chmod +x ./gradlew\n" +
               "      - name: Build with Gradle\n" +
               "        run: ./gradlew assemble${{ github.event.client_payload.build_type }}\n" +
               "      - name: Upload APK artifact\n" +
               "        uses: actions/upload-artifact@v4\n" +
               "        with:\n" +
               "          name: ${{ github.event.client_payload.build_type }}-apk\n" +
               "          path: app/build/outputs/apk/${{ github.event.client_payload.build_type }}/*.apk\n" +
               "      - name: Find Latest APK\n" +
               "        id: find_apk\n" +
               "        uses: actions/find-files@v1\n" +
               "        with:\n" +
               "          pattern: 'app/build/outputs/apk/${{ github.event.client_payload.build_type }}/*.apk'\n" +
               "          path: 'app/build/outputs/apk/${{ github.event.client_payload.build_type }}'\n" +
               "      - name: Send APK to Telegram\n" +
               "        if: success() && steps.find_apk.outputs.files\n" +
               "        run: |\n" +
               "          # Note: This step is usually complex and requires a custom Telegram Action \n" +
               "          # or a script to upload the file, which cannot be done with a simple 'sendMessage'\n" +
               "          # For this example, we just send a confirmation message.\n" +
               "          \n" +
               "          # Simplified Telegram Notification (actual file upload is complex in YAML)\n" +
               "          curl -s -X POST https://api.telegram.org/bot" + botToken + "/sendMessage \\\n" +
               "          -d chat_id=" + userId + " \\\n" +
               "          -d parse_mode=HTML \\\n" +
               "          -d text=\"<b>✅ Build Complete!</b>%0A%0AThe <code>${{ github.event.client_payload.build_type }}</code> build for <code>${{ github.repository }}</code> is ready. %0A%0A<a href='${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}'>🔗 View Artifacts</a>\"\n" +
               "";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Ensure scheduler is stopped to prevent memory leaks and crashes
        if (statusScheduler != null && !statusScheduler.isShutdown()) {
            statusScheduler.shutdownNow();
        }
    }
}
