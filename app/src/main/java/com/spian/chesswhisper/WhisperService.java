package com.spian.chesswhisper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.view.KeyEvent;

import java.util.Locale;

/**
 * Runs in the background (foreground service) so it keeps working with the screen off:
 *  - a MediaSession with "remote" volume catches the volume buttons (screen on or off),
 *  - a silent audio loop keeps that session active,
 *  - TextToSpeech talks into your earbuds.
 */
public class WhisperService extends Service {

    public interface UiListener { void onBrainChanged(); }

    public static final String ACTION_START = "start";
    public static final String ACTION_RESUME = "resume";
    public static final String ACTION_STOP = "stop";
    public static final String PREFS = "whisper";

    private static WhisperService instance;
    private static UiListener listener;
    public static WhisperService get() { return instance; }
    public static void setListener(UiListener l) { listener = l; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Brain brain;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String pendingSpeech = null;
    private MediaSession session;
    private AudioTrack silence;
    private volatile boolean silenceOn = false;
    private PowerManager.WakeLock wakeLock;

    public Brain brain() { return brain; }
    private final Runnable endRunner = this::shutdown;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        ChessCore core = new ChessCore();
        try { core.loadEco(getAssets().open("eco.tsv")); } catch (Exception ignored) { }
        brain = new Brain(core, new Brain.Host() {
            @Override public void say(String text) { speak(text); }
            @Override public void post(Runnable r, long delayMs) { main.postDelayed(r, delayMs); }
            @Override public void cancel(Runnable r) { main.removeCallbacks(r); }
            @Override public void changed() { notifyUi(); }
            @Override public void save(String state) { prefs().edit().putString("game", state).apply(); }
            @Override public void ended() { main.postDelayed(endRunner, 6000); }   // let it finish talking first
        });
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                tts.setSpeechRate(1.05f);
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                ttsReady = true;
                if (pendingSpeech != null) { speak(pendingSpeech); pendingSpeech = null; }
            }
        });
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChessWhisper:game");
        wakeLock.setReferenceCounted(false);
        setupSession();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        String action = intent != null ? intent.getAction() : null;
        loadSettings();
        if (ACTION_STOP.equals(action)) { shutdown(); return START_NOT_STICKY; }
        main.removeCallbacks(endRunner);
        if (!wakeLock.isHeld()) wakeLock.acquire(6 * 60 * 60 * 1000L);
        startSilence();
        session.setActive(true);
        if (ACTION_START.equals(action)) {
            brain.newGame(brain.settings.iAmWhite);
        } else if (ACTION_RESUME.equals(action)) {
            String s = prefs().getString("game", null);
            if (s != null) {
                String[] p = s.split("#", 3);
                brain.settings.iAmWhite = p[0].equals("w");
                if (brain.core.loadString(p[2])) brain.resume(p[1].equals("1"));
                else brain.newGame(brain.settings.iAmWhite);
            }
        }
        return START_NOT_STICKY;
    }

    public SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    public void loadSettings() {
        SharedPreferences p = prefs();
        Brain.Settings s = brain.settings;
        s.iAmWhite = p.getBoolean("white", true);
        s.numberFirst = p.getBoolean("numberFirst", false);
        s.lettersReversed = p.getBoolean("lettersRev", false);
        s.numbersReversed = p.getBoolean("numbersRev", false);
        s.openingWhite = Openings.line(p.getString("opW", "none"));
        s.openingVsE4 = Openings.line(p.getString("opE4", "none"));
        s.openingVsD4 = Openings.line(p.getString("opD4", "none"));
        brain.core.thinkMillis = p.getInt("think", 1000);
    }

    // ---------- volume buttons ----------
    private void setupSession() {
        session = new MediaSession(this, "ChessWhisper");
        session.setPlaybackToRemote(new VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
            @Override public void onAdjustVolume(int direction) {
                if (direction > 0) main.post(() -> brain.volUp());
                else if (direction < 0) main.post(() -> brain.volDown());
            }
        });
        session.setCallback(new MediaSession.Callback() {
            @Override public boolean onMediaButtonEvent(Intent i) {
                KeyEvent ev = i.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (ev == null || ev.getAction() != KeyEvent.ACTION_DOWN) return true;
                switch (ev.getKeyCode()) {
                    case KeyEvent.KEYCODE_HEADSETHOOK: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
                        brain.volDown(); return true;              // earbud tap = choose / yes
                    case KeyEvent.KEYCODE_MEDIA_NEXT:
                        brain.volUp(); return true;                // earbud next = next
                }
                return super.onMediaButtonEvent(i);
            }
        });
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f).build());
    }

    /** Called by the activity when it is on screen (keys reach the activity first). */
    public void key(boolean up) { if (up) brain.volUp(); else brain.volDown(); }

    // ---------- silent audio keeps the media session "playing" ----------
    private void startSilence() {
        if (silenceOn) return;
        silenceOn = true;
        new Thread(() -> {
            int rate = 8000;
            int buf = Math.max(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), 4096);
            silence = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(buf).setTransferMode(AudioTrack.MODE_STREAM).build();
            short[] zeros = new short[buf / 2];
            silence.play();
            while (silenceOn) silence.write(zeros, 0, zeros.length);
            silence.stop(); silence.release(); silence = null;
        }, "silence").start();
    }

    // ---------- speech ----------
    private void speak(String text) {
        if (text == null || text.isEmpty()) return;
        if (!ttsReady) { pendingSpeech = text; return; }
        tts.speak(text.replace("…", ","), TextToSpeech.QUEUE_FLUSH, null, "w" + System.nanoTime());
    }

    // ---------- notification ----------
    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel("whisper") == null) {
            nm.createNotificationChannel(new NotificationChannel("whisper", "Chess Whisper", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, WhisperService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "whisper") : new Notification.Builder(this);
        Notification n = b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Chess Whisper is listening")
                .setContentText("Vol UP = next · Vol DOWN = choose")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(1, n);
    }

    private void notifyUi() { if (listener != null) main.post(() -> { if (listener != null) listener.onBrainChanged(); }); }

    /** Swiped away from recent apps: stop and give the volume buttons back. */
    @Override public void onTaskRemoved(Intent rootIntent) {
        shutdown();
        super.onTaskRemoved(rootIntent);
    }

    private void shutdown() {
        main.removeCallbacks(endRunner);
        brain.stop();
        silenceOn = false;
        if (session != null) session.setActive(false);
        if (wakeLock.isHeld()) wakeLock.release();
        stopForeground(true);
        stopSelf();
        notifyUi();
    }

    @Override public void onDestroy() {
        silenceOn = false;
        if (session != null) session.release();
        if (tts != null) tts.shutdown();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        instance = null;
        notifyUi();
        super.onDestroy();
    }
}
