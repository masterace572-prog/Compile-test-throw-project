package com.apkbuilder.pro.models;

/**
 * Defines the stages of the build process.
 * This replaces the error-prone 'int currentStage' logic with a safe Enum.
 */
public enum BuildStage {
    IDLE(0, "🚀 Ready to build projects!"),
    CHECKING_TOKEN(1, "🔑 Verifying GitHub token..."),
    FETCHING_REPOS(2, "📥 Fetching repository list..."),
    VERIFYING_ACCESS(3, "🔍 Checking repository access..."),
    SETUP_WORKFLOW(4, "📝 Creating CI/CD workflow file..."),
    TESTING_TELEGRAM(5, "💬 Testing Telegram connection..."),
    TRIGGER_BUILD(6, "🚀 Triggering GitHub Actions workflow..."),
    POLLING_STATUS(7, "⏳ Build started. Polling status..."),
    COMPLETED(8, "✅ Build process finished."),
    FAILED(9, "❌ Build process failed.");

    private final int stageId;
    private final String message;

    BuildStage(int stageId, String message) {
        this.stageId = stageId;
        this.message = message;
    }

    public int getStageId() {
        return stageId;
    }

    public String getMessage() {
        return message;
    }

    /**
     * Gets the next stage in the sequence.
     * @return The next BuildStage, or the current stage if it's a terminal state.
     */
    public BuildStage next() {
        if (this.ordinal() < BuildStage.POLLING_STATUS.ordinal()) {
            return BuildStage.values()[this.ordinal() + 1];
        }
        // Terminal stages (Polling, Completed, Failed) loop to themselves or IDLE (handled in main logic)
        return this;
    }

    /**
     * Returns the Telegram status message associated with the stage.
     */
    public String getTelegramStatus() {
        switch (this) {
            case VERIFYING_ACCESS:
            case SETUP_WORKFLOW:
                return "🔧 Build Setup";
            case TRIGGER_BUILD:
            case POLLING_STATUS:
                return "⚙️ Build In Progress";
            case COMPLETED:
                return "✅ Build Finished";
            case FAILED:
                return "❌ Build Failed";
            default:
                return "ℹ️ Status Update";
        }
    }
}
