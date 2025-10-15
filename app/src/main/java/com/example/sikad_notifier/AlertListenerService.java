package com.example.sikad_notifier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.HashMap;
import java.util.Map;

public class AlertListenerService extends Service {

    private static final String TAG = "AlertListenerService";
    private static final String CHANNEL_ID = "alert_channel";
    private FirebaseFirestore db;
    private SharedPreferences prefs;
    private long serviceStartTime;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "🟢 Service created");

        prefs = getSharedPreferences("AdminSession", Context.MODE_PRIVATE);

        // ✅ Only start if admin is logged in
        if (!prefs.getBoolean("isLoggedIn", false)) {
            Log.w(TAG, "⚠️ Admin not logged in. Service will stop.");
            stopSelf();
            return;
        }

        db = FirebaseFirestore.getInstance();
        createNotificationChannel();

        // Store the time when the service starts
        serviceStartTime = System.currentTimeMillis();

        // Start foreground service with persistent notification
        startForeground(1, buildPersistentNotification());

        // Start listening to alerts
        listenToAlerts();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "▶️ Service started or restarted");
        return START_STICKY; // Keep service running even if killed
    }

    private Notification buildPersistentNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("🚲 SIKAD Alert Monitor Active")
                .setContentText("Listening for new bike alerts…")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void listenToAlerts() {
        db.collection("alerts")
                .addSnapshotListener((QuerySnapshot snapshots, FirebaseFirestoreException e) -> {

                    if (!prefs.getBoolean("isLoggedIn", false)) {
                        Log.w(TAG, "⚠️ Admin logged out. Stopping alert listener.");
                        stopSelf();
                        return;
                    }

                    if (e != null) {
                        Log.e(TAG, "❌ Firestore listener error", e);
                        return;
                    }

                    if (snapshots == null) {
                        Log.w(TAG, "⚠️ No snapshot data received.");
                        return;
                    }

                    for (DocumentChange dc : snapshots.getDocumentChanges()) {
                        if (dc.getType() == DocumentChange.Type.ADDED) {

                            // ✅ Safely get timestamp in millis
                            long alertTimestamp = safeTimestamp(dc.getDocument().get("timestamp"));

                            // ✅ Ignore old alerts before service start
                            if (alertTimestamp < serviceStartTime) {
                                Log.d(TAG, "⏭️ Skipping old alert from before service start.");
                                continue;
                            }

                            Map<String, Object> data = dc.getDocument().getData();
                            Log.d(TAG, "📄 New alert data: " + data);

                            String bikeId = safeString(data.get("bikeId"));
                            String message = safeString(data.get("message"));
                            String type = safeString(data.get("type"));
                            Long count = safeLong(data.get("count"));
                            Boolean resolved = safeBool(data.get("resolved"));

                            // Show a normal alert notification
                            showAlertNotification(bikeId, message);

                            // Save to notifier_logs
                            saveNotifierLog(bikeId, message, type, count, resolved, alertTimestamp);
                        }
                    }
                });

        Log.d(TAG, "📡 Listening for alert changes...");
    }

    private void showAlertNotification(String bikeId, String message) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("🚨 Alert from " + bikeId)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setVibrate(new long[]{0, 400, 200, 400})
                .setSound(Settings.System.DEFAULT_NOTIFICATION_URI);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify((int) System.currentTimeMillis(), builder.build());
    }

    private void saveNotifierLog(String bikeId, String message, String type, Long count, Boolean resolved, long timestamp) {
        Map<String, Object> log = new HashMap<>();
        log.put("bikeId", bikeId);
        log.put("message", message);
        log.put("type", type);
        log.put("count", count);
        log.put("resolved", resolved);
        log.put("timestamp", timestamp);

        db.collection("notifier_logs").add(log)
                .addOnSuccessListener(doc -> Log.d(TAG, "✅ Log added to notifier_logs"))
                .addOnFailureListener(err -> Log.e(TAG, "❌ Failed to add log", err));
    }

    private String safeString(Object obj) {
        return obj != null ? obj.toString() : "";
    }

    private Long safeLong(Object obj) {
        return obj instanceof Number ? ((Number) obj).longValue() : 0L;
    }

    private Boolean safeBool(Object obj) {
        return obj instanceof Boolean ? (Boolean) obj : false;
    }

    private long safeTimestamp(Object obj) {
        if (obj instanceof Number) {
            return ((Number) obj).longValue();
        } else if (obj instanceof Timestamp) {
            return ((Timestamp) obj).toDate().getTime();
        } else {
            return System.currentTimeMillis();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Alert Notifications",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Notifies when bikes trigger alerts or when monitoring is active");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
