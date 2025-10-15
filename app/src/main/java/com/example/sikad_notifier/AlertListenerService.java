package com.example.sikad_notifier;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.QuerySnapshot;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class AlertListenerService extends Service {

    private static final String TAG = "AlertListenerService";

    private static final String CHANNEL_ID_MONITOR = "monitor_channel";
    private static final String CHANNEL_ID_ALERT = "alert_channel";

    // throttle sound to at most once per SOUND_THROTTLE_MS
    private static final long SOUND_THROTTLE_MS = 3000L;

    private FirebaseFirestore db;
    private SharedPreferences prefs;
    private long serviceStartTime;

    // last time we played sound
    private long lastSoundTime = 0L;

    // single "active" notification id for coalescing rapid alerts (updated instead of creating new noisy notifications)
    private final int COALESCE_NOTIFICATION_ID = 1001;

    // unique id generator for non-coalesced notifications (if you still want separate ones)
    private final AtomicInteger uniq = new AtomicInteger((int) (System.currentTimeMillis() & 0xffff));

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "🟢 Service created");

        prefs = getSharedPreferences("AdminSession", Context.MODE_PRIVATE);

        if (!prefs.getBoolean("isLoggedIn", false)) {
            Log.w(TAG, "⚠️ Admin not logged in. Service will stop.");
            stopSelf();
            return;
        }

        db = FirebaseFirestore.getInstance();

        // recreate channels now (deletes old alert channel so sound is applied)
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "▶️ Service started or restarted");

        if (intent != null && intent.hasExtra("SERVICE_START_TIME")) {
            serviceStartTime = intent.getLongExtra("SERVICE_START_TIME", System.currentTimeMillis());
        } else {
            serviceStartTime = System.currentTimeMillis();
        }

        // start foreground silent monitor notification
        startForeground(1, buildPersistentNotification());

        // start listening
        listenToAlerts();

        return START_STICKY;
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);

            // Monitor channel (silent)
            if (manager.getNotificationChannel(CHANNEL_ID_MONITOR) == null) {
                NotificationChannel monitorChannel = new NotificationChannel(
                        CHANNEL_ID_MONITOR,
                        "Monitor Notifications",
                        NotificationManager.IMPORTANCE_LOW
                );
                monitorChannel.setDescription("Shows that alert monitoring is active (no sound)");
                manager.createNotificationChannel(monitorChannel);
            }

            // Recreate alert channel to ensure custom sound is set
            manager.deleteNotificationChannel(CHANNEL_ID_ALERT);

            Uri soundUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.alert);

            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();

            NotificationChannel alertChannel = new NotificationChannel(
                    CHANNEL_ID_ALERT,
                    "Alert Notifications",
                    NotificationManager.IMPORTANCE_HIGH
            );
            alertChannel.setDescription("Notifies when bikes trigger alerts");
            alertChannel.enableVibration(true);
            alertChannel.setVibrationPattern(new long[]{0, 400, 200, 400});
            alertChannel.setSound(soundUri, audioAttributes);

            manager.createNotificationChannel(alertChannel);
        }
    }

    private NotificationCompat.Builder baseAlertBuilder(String bikeId, String message, boolean playSound) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("🚨 Alert from " + bikeId)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        // Note: on Android O+, sound is controlled by channel. We include setSound for pre-O devices
        if (playSound) {
            b.setVibrate(new long[]{0, 400, 200, 400})
                    .setSound(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.alert));
        } else {
            // do not set sound/vibrate on builder; channel rules apply for O+
        }
        return b;
    }

    private NotificationCompat.Builder baseCoalesceBuilder(String summaryText, int count) {
        // convenience builder to update existing notification with counter
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String title = "🚨 " + count + " new alerts";
        return new NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(summaryText)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);
    }

    private android.app.Notification buildPersistentNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID_MONITOR)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("🚲 SIKAD Alert Monitor Active")
                .setContentText("Listening for new bike alerts…")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .build();
    }

    private volatile int coalesceCount = 0;
    private volatile long coalesceFirstTs = 0L;

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
                    if (snapshots == null) return;

                    for (DocumentChange dc : snapshots.getDocumentChanges()) {
                        if (dc.getType() == DocumentChange.Type.ADDED) {
                            long alertTimestamp = safeTimestamp(dc.getDocument().get("timestamp"));
                            if (alertTimestamp < serviceStartTime) continue;

                            Map<String, Object> data = dc.getDocument().getData();
                            Log.d(TAG, "📄 New alert data: " + data);

                            String bikeId = safeString(data.get("bikeId"));
                            String message = safeString(data.get("message"));

                            // Decide whether to play sound now (throttle)
                            long now = System.currentTimeMillis();
                            boolean allowSound = (now - lastSoundTime) >= SOUND_THROTTLE_MS;
                            if (allowSound) lastSoundTime = now;

                            // Coalesce rapid alerts for a short window (e.g. 3s) to avoid sounding many times
                            if (coalesceFirstTs == 0 || now - coalesceFirstTs > SOUND_THROTTLE_MS) {
                                // start new coalesce window
                                coalesceFirstTs = now;
                                coalesceCount = 1;

                                // show a notification (with sound if allowed)
                                showAlertNotification(bikeId, message, allowSound);
                            } else {
                                // within coalesce window: increment count and update single notification without sound
                                coalesceCount++;
                                updateCoalescedNotification(bikeId, message, coalesceCount);
                            }

                            // Always persist log
                            saveNotifierLog(bikeId, message, "unknown", 1L, false, alertTimestamp);
                        }
                    }
                });
        Log.d(TAG, "📡 Listening for alert changes...");
    }

    private void showAlertNotification(String bikeId, String message, boolean playSound) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // If allowed to play sound, play via Notification (channel sound + builder) — this is preferred
        if (playSound) {
            manager.notify(uniq.incrementAndGet(), baseAlertBuilder(bikeId, message, true).build());
        } else {
            // create/update a coalesced notification (no sound)
            updateCoalescedNotification(bikeId, message, ++coalesceCount);
        }
    }

    private void updateCoalescedNotification(String bikeId, String message, int count) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String summary = message;
        NotificationCompat.Builder b = baseCoalesceBuilder(summary, count);
        // do NOT set sound here (we've decided to avoid playing it)
        manager.notify(COALESCE_NOTIFICATION_ID, b.build());
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

    // Optional: direct playback fallback (use only if you understand DND and UX implications)
    // This tries to play the mp3 directly via MediaPlayer (may bypass channel mute but not DND).
    // Use carefully; commented out by default.
    @SuppressWarnings("unused")
    private void playSoundDirectly() {
        try {
            Uri soundUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.alert);
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(this, soundUri);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
            }
            mp.setOnCompletionListener(MediaPlayer::release);
            mp.prepare();
            mp.start();
        } catch (IOException ex) {
            Log.e(TAG, "playSoundDirectly failed: " + ex.getMessage());
        }
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
        if (obj instanceof Number) return ((Number) obj).longValue();
        if (obj instanceof Timestamp) return ((Timestamp) obj).toDate().getTime();
        return System.currentTimeMillis();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
