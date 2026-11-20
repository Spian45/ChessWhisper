package com.spian.chesswhisper;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Optional. When turned on (Settings → Accessibility), it catches the volume buttons
 * during a game *before* Android shows the volume slider, so nothing pops up on screen.
 * Only volume keys, only while a game is running; everything else passes through untouched.
 */
public class KeyService extends AccessibilityService {

    private static boolean connected = false;
    public static boolean isOn() { return connected; }

    @Override protected void onServiceConnected() { connected = true; }
    @Override public boolean onUnbind(android.content.Intent i) { connected = false; return super.onUnbind(i); }
    @Override public void onDestroy() { connected = false; super.onDestroy(); }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        int c = e.getKeyCode();
        if (c != KeyEvent.KEYCODE_VOLUME_UP && c != KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        WhisperService s = WhisperService.get();
        if (s == null || !s.brain().busy()) return false;        // no game: normal volume
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            s.key(c == KeyEvent.KEYCODE_VOLUME_UP);
        }
        return true;                                             // swallow it: no slider, no volume change
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
