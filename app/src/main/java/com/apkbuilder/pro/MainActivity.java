package com.apkbuilder.pro;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.apkbuilder.pro.models.BuildStage;
import com.apkbuilder.pro.models.WorkflowResponse;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "APKBuilderPro";

    // UI Components (Using Material Design components)
    private TextInputEditText githubTokenInput, botTokenInput, userIdInput;
    private AutoCompleteTextView repoSpinner, buildTypeSpinner;
    private MaterialButton buildBtn, testConnectionBtn, fetchReposBtn;
    private TextView statusText;
    private LinearProgressIndicator linearProgressBar;
    
    // Services and Data
    private GitHubService gitHubService = new GitHubService(); // FIX: Use parameterless constructor
    private TelegramService telegramService = new TelegramService(); 
    
    // State
    private BuildStage currentStage = BuildStage.IDLE; 
    private String currentRepoOwner = "";
    private String currentRepoName = "";
    private String currentBuildType = "release"; 
    private String currentBotToken = "";
    private String currentUserId = "";
    private String lastWorkflowRunId = "";
    private String lastMessageId = "";
    private List<String> availableRepos = new ArrayList<>();

    // Concurrency
    private ScheduledExecutorService statusScheduler;
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        mainHandler = new Handler(Looper.getMainLooper());
        
        initializeViews();
        setupListeners();
        setupDropdowns();
        
        updateStage(BuildStage.IDLE, "🚀 Ready to build Android projects!\n\nEnter your GitHub token to begin.");
    }

    private void initializeViews() {
        githubTokenInput = findViewById(R.id.githubTokenInput);
        botTokenInput = findViewById(R.id.botTokenInput);
        userIdInput = findViewById(R.id.userIdInput);
        repoSpinner = findViewById(R.id.repoSpinner);
        buildTypeSpinner = findViewById(R.id.buildTypeSpinner);
        buildBtn = findViewById(R.id.buildBtn);
        testConnectionBtn = findViewById(R.id.testConnectionBtn);
        fetchReposBtn = findViewById(R.id.fetchReposBtn);
        statusText = findViewById(R.id.statusText);
        linearProgressBar = findViewById(R.id.linearProgressBar); 
    }
    
    private void setupDropdowns() {
        // Setup build type dropdown (R.array.build_types must exist)
        ArrayAdapter<CharSequence> buildAdapter = ArrayAdapter.createFromResource(this,
                R.array.build_types, android.R.layout.simple_spinner_dropdown_item);
        buildTypeSpinner.setAdapter(buildAdapter);
        buildTypeSpinner.setText(currentBuildType, false);
        
        buildTypeSpinner.setOnItemClickListener((parent, view, position, id) -> {
            currentBuildType = (String) parent.getItemAtPosition(position);
            checkBuildButtonState();
        });

        // Setup repo spinner with empty adapter initially
        ArrayAdapter<String> repoAdapter = new ArrayAdapter<>(this, 
                android.R.layout.simple_spinner_dropdown_item, new ArrayList<String>());
        repoSpinner.setAdapter(repoAdapter);
        
        repoSpinner.setOnItemClickListener((parent, view, position, id) -> {
            String fullRepoName = (String) parent.getItemAtPosition(position);
            parseRepoUrl(fullRepoName);
            checkBuildButtonState();
        });
    }
    
    private void setupListeners() {
        githubTokenInput.addTextChangedListener(new SimpleTextWatcher(() -> {
            boolean hasGithubToken = !githubTokenInput.getText().toString().trim().isEmpty();
            fetchReposBtn.setEnabled(hasGithubToken);
            checkBuildButtonState();
        }));
        
        botTokenInput.addTextChangedListener(new SimpleTextWatcher(this::checkBuildButtonState));
        userIdInput.addTextChangedListener(new SimpleTextWatcher(this::checkBuildButtonState));

        testConnectionBtn.setOnClickListener(v -> testTelegramConnection());
        buildBtn.setOnClickListener(v -> startBuildProcess());
        fetchReposBtn.setOnClickListener(v -> fetchRepositories());
        
        checkBuildButtonState(); // Initial check
    }
    
    private void checkBuildButtonState() {
        boolean isRepoSelected = !currentRepoName.isEmpty();
        boolean hasGithubToken = !githubTokenInput.getText().toString().trim().isEmpty();
        boolean hasBotToken = !botTokenInput.getText().toString().trim().isEmpty();
        boolean hasUserId = !userIdInput.getText().toString().trim().isEmpty();
        
        boolean hasTelegramDetails = hasBotToken && hasUserId;
        
        testConnectionBtn.setEnabled(hasTelegramDetails);

        buildBtn.setEnabled(currentStage == BuildStage.IDLE && isRepoSelected && hasGithubToken && hasTelegramDetails);
    }

    private void parseRepoUrl(String fullRepoName) {
        if (fullRepoName != null && fullRepoName.contains("/")) {
            String[] parts = fullRepoName.split("/");
            currentRepoOwner = parts[0];
            currentRepoName = parts[1];
        } else {
            currentRepoOwner = "";
            currentRepoName = "";
        }
    }

    private void updateStage(BuildStage newStage, String customMessage) {
        this.currentStage = newStage;
        
        boolean showProgress = newStage.getStageId() > BuildStage.IDLE.getStageId() && 
                               newStage.getStageId() < BuildStage.COMPLETED.getStageId();
        
        linearProgressBar.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        linearProgressBar.setIndeterminate(true); 

        String message = (customMessage != null) ? customMessage : newStage.getMessage();
        statusText.setText(message);
        
        Log.d(TAG, "Stage updated: " + newStage.name() + " - " + message);
        checkBuildButtonState();
    }
    
    // =========================================================================
    // API & Build Logic 
    // =========================================================================

    private void fetchRepositories() {
        final String token = githubTokenInput.getText().toString().trim();
        if (token.isEmpty()) return;

        updateStage(BuildStage.FETCHING_REPOS, BuildStage.FETCHING_REPOS.getMessage());
        
        new Thread(() -> {
            try {
                // FIX: Pass token to getRepositories
                List<String> repos = gitHubService.getRepositories(token);
                availableRepos = repos;
                
                mainHandler.post(() -> {
                    ArrayAdapter<String> repoAdapter = new ArrayAdapter<>(
                        MainActivity.this, 
                        android.R.layout.simple_spinner_dropdown_item, 
                        availableRepos
                    );
                    repoSpinner.setAdapter(repoAdapter);
                    
                    if (repos.isEmpty()) {
                        updateStage(BuildStage.IDLE, "❌ No accessible repositories found. Check your token scope or try again.");
                    } else {
                        updateStage(BuildStage.IDLE, "✅ Repositories fetched successfully. Now select one and fill in Telegram details.");
                    }
                    Toast.makeText(MainActivity.this, "Found " + repos.size() + " repositories.", Toast.LENGTH_SHORT).show();
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
        final String botToken = botTokenInput.getText().toString().trim();
        final String userId = userIdInput.getText().toString().trim();
        if (botToken.isEmpty() || userId.isEmpty()) return;

        updateStage(BuildStage.TESTING_TELEGRAM, BuildStage.TESTING_TELEGRAM.getMessage());

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
        if (currentRepoName.isEmpty() || currentRepoOwner.isEmpty()) {
             Toast.makeText(this, "Please select a repository.", Toast.LENGTH_LONG).show();
             return;
        }
        
        // FIX: The GitHubService constructor is parameterless, so we don't need this line.
        // gitHubService = new GitHubService(githubToken); 
        
        final String githubToken = githubTokenInput.getText().toString().trim();
        currentBotToken = botTokenInput.getText().toString().trim();
        currentUserId = userIdInput.getText().toString().trim();
        
        lastWorkflowRunId = "";
        lastMessageId = "";
        
        new Thread(this::runBuildSequence).start();
    }
    
    private void runBuildSequence() {
        // Retrieve the token inside the thread for safety
        final String githubToken = githubTokenInput.getText().toString().trim(); 

        try {
            // 1. VERIFYING_ACCESS
            mainHandler.post(() -> updateStage(BuildStage.VERIFYING_ACCESS, BuildStage.VERIFYING_ACCESS.getMessage()));
            // FIX: Pass token to verifyRepositoryAccess
            if (!gitHubService.verifyRepositoryAccess(currentRepoOwner, currentRepoName, githubToken)) {
                throw new Exception("Repository access failed. Check token permissions (repo scope).");
            }
            
            // 2. SETUP_WORKFLOW
            mainHandler.post(() -> updateStage(BuildStage.SETUP_WORKFLOW, BuildStage.SETUP_WORKFLOW.getMessage()));
            String workflowContent = generateWorkflowYaml(currentBotToken, currentUserId, currentBuildType);
            // FIX: Pass token to createWorkflowFile
            gitHubService.createWorkflowFile(currentRepoOwner, currentRepoName, githubToken, workflowContent);
            
            // 3. TRIGGER_BUILD
            mainHandler.post(() -> updateStage(BuildStage.TRIGGER_BUILD, BuildStage.TRIGGER_BUILD.getMessage()));
            // FIX: Pass token and currentBuildType to dispatchWorkflow
            gitHubService.dispatchWorkflow(currentRepoOwner, currentRepoName, githubToken, currentBuildType);

            // Send initial Telegram message after successful trigger
            sendInitialTelegramMessage();

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
                updateTelegramMessage(BuildStage.FAILED.getTelegramStatus(), "Build failed with error: " + e.getMessage());
            });
        }
    }
    
    private void startStatusPolling() {
        if (statusScheduler != null && !statusScheduler.isShutdown()) {
            statusScheduler.shutdownNow();
        }
        
        statusScheduler = Executors.newSingleThreadScheduledExecutor();
        statusScheduler.scheduleAtFixedRate(this::pollStatus, 5, 10, TimeUnit.SECONDS); 
    }

    private void pollStatus() {
        final String githubToken = githubTokenInput.getText().toString().trim();

        try {
            // FIX: Pass token to getLatestWorkflowStatus
            WorkflowResponse response = gitHubService.getLatestWorkflowStatus(
                currentRepoOwner, 
                currentRepoName,
                githubToken
            );

            mainHandler.post(() -> {
                statusText.setText(response.getMessage()); 
                
                if (response.isActive()) {
                    if (lastWorkflowRunId.isEmpty() && response.getRunId() != null) {
                        lastWorkflowRunId = response.getRunId();
                    }
                    updateTelegramMessage(BuildStage.POLLING_STATUS.getTelegramStatus(), response.formatTelegramMessage(currentRepoOwner + "/" + currentRepoName));
                    
                } else if (response.getConclusion() != null) {
                    statusScheduler.shutdownNow();
                    
                    if (response.isSuccessful()) {
                        updateStage(BuildStage.COMPLETED, response.getMessage());
                    } else {
                        updateStage(BuildStage.FAILED, response.getMessage());
                    }
                    
                    updateTelegramMessage(response.isSuccessful() ? BuildStage.COMPLETED.getTelegramStatus() : BuildStage.FAILED.getTelegramStatus(), 
                                          response.formatTelegramMessage(currentRepoOwner + "/" + currentRepoName));
                }
            }); 
            
        } catch (IOException e) {
            Log.e(TAG, "Polling Error", e);
            mainHandler.post(() -> {
                statusScheduler.shutdownNow();
                updateStage(BuildStage.FAILED, "❌ Polling Error: " + e.getMessage());
                updateTelegramMessage(BuildStage.FAILED.getTelegramStatus(), "Polling failed with error: " + e.getMessage());
            });
        }
    }

    // --- Telegram Logic (Runs on worker threads) ---

    private void sendInitialTelegramMessage() {
        new Thread(() -> {
            try {
                String message = "🚀 <b>APK Builder Pro - Build Started</b>\n\n" +
                               "🏗️ Setting up CI/CD workflow and triggering build...\n\n" +
                               "📦 Repository: " + currentRepoOwner + "/" + currentRepoName + "\n" +
                               "🔨 Build Type: " + currentBuildType;
                
                String messageId = telegramService.sendMessageWithId(currentBotToken, currentUserId, message);
                if (messageId != null) {
                    lastMessageId = messageId;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to send initial Telegram message", e);
            }
        }).start();
    }

    private void updateTelegramMessage(String statusTitle, String details) {
        if (lastMessageId.isEmpty()) {
            sendInitialTelegramMessage(); 
            return;
        }

        new Thread(() -> {
            try {
                String fullMessage = "<b>" + statusTitle + "</b>\n\n" + details;
                telegramService.editMessage(currentBotToken, currentUserId, lastMessageId, fullMessage);
            } catch (Exception e) {
                Log.e(TAG, "Failed to update Telegram message", e);
            }
        }).start();
    }
    
    // --- YAML Generation (Kept as is) ---
    private String generateWorkflowYaml(String botToken, String userId, String buildType) {
        String buildCommand = "debug".equals(buildType) ? "assembleDebug" : 
                             "release".equals(buildType) ? "assembleRelease" : 
                             "assemble";

        return "name: Android CI with APK Builder Pro\n" +
                "\n" +
                "on:\n" +
                "  repository_dispatch:\n" +
                "    types: [run_android_build]\n" +
                "    inputs:\n" +
                "      build_type:\n" +
                "        description: 'Build variant'\n" +
                "        required: true\n" +
                "        default: 'release'\n" +
                "\n" +
                "jobs:\n" +
                "  build:\n" +
                "    runs-on: ubuntu-latest\n" +
                "\n" +
                "    steps:\n" +
                "    - name: 🚀 Checkout code\n" +
                "      uses: actions/checkout@v4\n" +
                "\n" +
                "    - name: 🛠️ Set up Java\n" +
                "      uses: actions/setup-java@v4\n" +
                "      with:\n" +
                "        distribution: 'temurin'\n" +
                "        java-version: '17'\n" +
                "        cache: 'gradle'\n" +
                "\n" +
                "    - name: 🔑 Grant execute permission to gradlew\n" +
                "      run: chmod +x gradlew\n" +
                "\n" +
                "    - name: 🏗️ Build APK\n" +
                "      run: |\n" +
                "        ./gradlew clean\n" +
                "        ./gradlew " + buildCommand + "\n" +
                "\n" +
                "    - name: 🔍 Find APK\n" +
                "      id: find_apk\n" +
                "      run: |\n" +
                "        APK_PATH=$(find ./app/build/outputs/apk/ -name \"*." + buildType + ".apk\" | grep -v \"unsigned\" | head -1)\n" +
                "        if [ -z \"$APK_PATH\" ]; then\n" +
                "          APK_PATH=$(find . -name \"*.apk\" | grep -v \"unsigned\" | head -1)\n" +
                "        fi\n" +
                "        echo \"APK_PATH=$APK_PATH\" >> $GITHUB_OUTPUT\n" +
                "        echo \"📱 Found APK: $APK_PATH\"\n" +
                "\n" +
                "    - name: 📤 Send to Telegram\n" +
                "      uses: appleboy/telegram-action@master\n" +
                "      if: always()\n" + 
                "      with:\n" +
                "        to: " + userId + "\n" +
                "        token: " + botToken + "\n" +
                "        document: ${{ steps.find_apk.outputs.APK_PATH }}\n" +
                "        caption: |\n" +
                "          🚀 APK Build Complete!\n" +
                "          \n" +
                "          📦 Project: ${{ github.repository }}\n" +
                "          📱 Build Type: " + buildType + "\n" +
                "          🔨 Status: ${{ job.status }}\n" +
                "          ✅ Ready to install!\n" +
                "\n" +
                "    - name: 📊 Final Report\n" +
                "      if: always()\n" +
                "      run: echo \"Final Job Status: ${{ job.status }}\"";
    }

    private static class SimpleTextWatcher implements android.text.TextWatcher {
        private final Runnable callback;
        public SimpleTextWatcher(Runnable callback) { this.callback = callback; }
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override
        public void afterTextChanged(android.text.Editable s) { callback.run(); }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (statusScheduler != null && !statusScheduler.isShutdown()) {
            statusScheduler.shutdownNow();
        }
    }
}
