package com.example.sikad_notifier;

import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;
import com.google.firebase.Timestamp;


import com.google.firebase.firestore.FirebaseFirestore;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private SharedPreferences prefs;
    private Button logoutButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("AdminSession", Context.MODE_PRIVATE);

        // ✅ Check login state first
        if (!prefs.getBoolean("isLoggedIn", false)) {
            Log.w(TAG, "⚠️ No admin logged in. Redirecting to LoginActivity...");
            Intent intent = new Intent(MainActivity.this, LoginActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        String adminName = prefs.getString("admin_name", "Admin");
        Toast.makeText(this, "Welcome, " + adminName, Toast.LENGTH_SHORT).show();
        Log.d(TAG, "🟢 Admin logged in: " + adminName);

        // ✅ Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        // ✅ Quick Firestore connectivity check
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("alerts").get()
                .addOnSuccessListener(q -> Log.d(TAG, "✅ Firestore reachable, total docs: " + q.size()))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Firestore not reachable", e));

        // ✅ Start the alert listener service only if admin is logged in
        startAlertListenerService();

        // ✅ Setup Logout Button
        logoutButton = findViewById(R.id.logoutButton);
        logoutButton.setOnClickListener(v -> logoutAdmin());
    }

    private void startAlertListenerService() {
        // Only start service if admin session is active
        if (!prefs.getBoolean("isLoggedIn", false)) {
            Log.w(TAG, "⚠️ Admin not logged in. AlertListenerService will not start.");
            return;
        }

        Intent serviceIntent = new Intent(this, AlertListenerService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG, "▶️ Starting foreground service (persistent)...");
            startForegroundService(serviceIntent);
        } else {
            Log.d(TAG, "▶️ Starting background service...");
            startService(serviceIntent);
        }
    }

    private void logoutAdmin() {
        Log.d(TAG, "🚪 Logging out admin...");

        // Clear stored session
        SharedPreferences.Editor editor = prefs.edit();
        editor.clear();
        editor.apply();

        // Stop foreground service
        Intent serviceIntent = new Intent(this, AlertListenerService.class);
        stopService(serviceIntent);

        Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show();

        // Redirect to login
        Intent intent = new Intent(MainActivity.this, LoginActivity.class);
        startActivity(intent);
        finish();
    }
}
