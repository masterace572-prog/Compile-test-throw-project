package com.apkbuilder.pro;

import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

public class MainActivity extends AppCompatActivity {

    private TextInputEditText repoUrlInput, githubTokenInput, botTokenInput, userIdInput;
    private AutoCompleteTextView buildTypeSpinner;
    private MaterialButton buildBtn, testConnectionBtn;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initializeViews();
        
        // Simple test
        buildBtn.setOnClickListener(v -> {
            statusText.setText("Build button clicked! App is working.");
        });
        
        testConnectionBtn.setOnClickListener(v -> {
            statusText.setText("Test connection clicked!");
        });
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
        buildTypeSpinner.setText("Debug", false);

        statusText.setText("App loaded successfully! Ready to build.");
    }
}
