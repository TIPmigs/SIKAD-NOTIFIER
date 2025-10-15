package com.example.sikad_notifier;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

public class LoginActivity extends AppCompatActivity {

    private EditText emailEditText, passwordEditText;
    private Button loginButton;
    private ProgressBar progressBar;

    private FirebaseFirestore db;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        db = FirebaseFirestore.getInstance();
        prefs = getSharedPreferences("AdminSession", Context.MODE_PRIVATE);

        emailEditText = findViewById(R.id.emailEditText);
        passwordEditText = findViewById(R.id.passwordEditText);
        loginButton = findViewById(R.id.loginButton);
        progressBar = findViewById(R.id.progressBar);

        // ✅ Smooth auto-login if already logged in
        if (prefs.getBoolean("isLoggedIn", false)) {
            String adminName = prefs.getString("admin_name", "Admin");
            Toast.makeText(this, "Welcome back, " + adminName, Toast.LENGTH_SHORT).show();

            // Delay slightly for smoother transition
            new Handler().postDelayed(() -> {
                // Start foreground alert listener service
                Intent serviceIntent = new Intent(this, AlertListenerService.class);
                startForegroundService(serviceIntent);

                startMainActivity();
            }, 500); // 0.5 second delay
            return;
        }

        loginButton.setOnClickListener(v -> attemptLogin());
    }

    private void attemptLogin() {
        String email = emailEditText.getText().toString().trim();
        String password = passwordEditText.getText().toString().trim();

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        db.collection("admin_accounts")
                .whereEqualTo("email", email)
                .whereEqualTo("password", password)
                .get()
                .addOnCompleteListener(task -> {
                    progressBar.setVisibility(View.GONE);
                    if (task.isSuccessful() && !task.getResult().isEmpty()) {
                        for (QueryDocumentSnapshot doc : task.getResult()) {
                            String adminName = doc.getString("name");
                            Toast.makeText(this, "Welcome, " + adminName, Toast.LENGTH_SHORT).show();

                            // ✅ Save session locally
                            SharedPreferences.Editor editor = prefs.edit();
                            editor.putBoolean("isLoggedIn", true);
                            editor.putString("admin_name", adminName);
                            editor.putString("admin_email", email);
                            editor.apply();

                            // ✅ Start alert listener service
                            Intent serviceIntent = new Intent(this, AlertListenerService.class);
                            startForegroundService(serviceIntent);

                            // ✅ Proceed to main dashboard
                            startMainActivity();
                            finish();
                        }
                    } else {
                        Toast.makeText(this, "Invalid credentials", Toast.LENGTH_SHORT).show();
                    }
                })
                .addOnFailureListener(e -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void startMainActivity() {
        Intent intent = new Intent(LoginActivity.this, MainActivity.class);
        startActivity(intent);
        finish();
    }
}
