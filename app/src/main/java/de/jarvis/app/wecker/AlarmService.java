package de.jarvis.app.wecker;

import de.jarvis.app.R;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;

public class AlarmService extends Service {
    static final String CHANNEL = "wecker_alarm";

    private MediaPlayer player;
    private Ringtone fallback;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private int oldVolume = -1;
    private boolean started;

    static void stop(Context c) {
        c.stopService(new Intent(c, AlarmService.class));
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean wasRinging = Prefs.get(this).getBoolean("ringing", false);
        if (intent == null && !wasRinging) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!started && intent != null && !wasRinging) {
            boolean test = intent.getBooleanExtra("test", false);
            Prefs.get(this).edit()
                    .putBoolean("ringing", true)
                    .putBoolean("testMode", test)
                    .putInt("progress", 0)
                    .apply();
        }
        started = true;
        startForegroundNow();
        startSound();
        openRingScreen();
        return START_STICKY;
    }

    private void startForegroundNow() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Wecker", NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setBypassDnd(true);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);

        Intent ring = new Intent(this, RingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent pi = PendingIntent.getActivity(this, 10, ring,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_wecker_alarm)
                .setContentTitle("Aufstehen! ⏰")
                .setContentText("Tippe hier, um den Wecker auszuschalten.")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(87100, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(87100, n);
        }
    }

    private void startSound() {
        if (player != null || fallback != null) return;

        AudioManager am = getSystemService(AudioManager.class);
        try {
            oldVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        } catch (Exception e) {
            oldVolume = -1;
        }

        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        Uri uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);

        try {
            player = new MediaPlayer();
            player.setAudioAttributes(aa);
            player.setDataSource(this, uri);
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception e) {
            if (player != null) {
                try { player.release(); } catch (Exception ignored) {}
                player = null;
            }
            try {
                fallback = RingtoneManager.getRingtone(this, Settings.System.DEFAULT_RINGTONE_URI);
                if (fallback != null) {
                    fallback.setAudioAttributes(aa);
                    if (Build.VERSION.SDK_INT >= 28) fallback.setLooping(true);
                    fallback.play();
                }
            } catch (Exception ignored) {}
        }

        vibrator = getSystemService(Vibrator.class);
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 800, 600}, 0), aa);
        }

        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aufstehwecker:ring");
        wakeLock.acquire(2 * 60 * 60 * 1000L);
    }

    private void openRingScreen() {
        try {
            startActivity(new Intent(this, RingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
            // Wird auf neueren Android-Versionen ggf. blockiert – dann übernimmt die Vollbild-Benachrichtigung.
        }
    }

    @Override
    public void onDestroy() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
        if (fallback != null) {
            try { fallback.stop(); } catch (Exception ignored) {}
            fallback = null;
        }
        if (vibrator != null) vibrator.cancel();
        if (oldVolume >= 0) {
            try {
                getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_ALARM, oldVolume, 0);
            } catch (Exception ignored) {}
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        Prefs.get(this).edit().putBoolean("ringing", false).putInt("progress", 0).apply();
        stopForeground(true);
        super.onDestroy();
    }
}
