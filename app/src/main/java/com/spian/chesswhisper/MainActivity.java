package com.spian.chesswhisper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

/** Phone-friendly UI, built in code (no XML). */
public class MainActivity extends Activity implements WhisperService.UiListener {

    // colors
    static final int BG = 0xFF0E1116, CARD = 0xFF1A1F27, CARD2 = 0xFF232A34, LINE = 0xFF2E3744;
    static final int TEXT = 0xFFE8ECF1, MUTED = 0xFF8B95A3, ACCENT = 0xFFF5A524, BLUE = 0xFF7DB4FF, GREEN = 0xFF3FB950, RED = 0xFFE5534B;

    private SharedPreferences prefs;
    private TextView pill, question, item, big, opening, moves, hint, thinkLabel;
    private BoardView board;
    private LinearLayout settingsBody, padRow;
    private Button sideW, sideB;
    private TextView settingsHeader;
    private TextView hideStatus;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(WhisperService.PREFS, MODE_PRIVATE);
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        LinearLayout root = vbox();
        root.setPadding(dp(16), dp(14), dp(16), dp(24));
        root.setBackgroundColor(BG);

        // ---- header ----
        LinearLayout head = hbox();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("♞ Chess Whisper", 22, TEXT, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pill = text("Off", 13, TEXT, true);
        pill.setPadding(dp(12), dp(5), dp(12), dp(5));
        head.addView(pill);
        root.addView(head);

        // ---- "now" card ----
        LinearLayout now = card();
        opening = text("", 13, BLUE, false);
        opening.setSingleLine(true); opening.setEllipsize(TextUtils.TruncateAt.END);
        question = text("", 14, MUTED, false);
        item = text("", 64, ACCENT, true); item.setGravity(Gravity.CENTER);
        big = text("Press New game to start", 22, TEXT, true); big.setGravity(Gravity.CENTER);
        hint = text("", 13, MUTED, false); hint.setGravity(Gravity.CENTER);
        now.addView(opening); now.addView(question); now.addView(item); now.addView(big); now.addView(hint);
        root.addView(now, cardLp());

        // ---- on-screen buttons (same as volume keys) ----
        padRow = hbox();
        padRow.addView(padButton("▲  Next", "Vol UP", CARD2, v -> svcKey(true)), weight());
        padRow.addView(space(dp(10)));
        padRow.addView(padButton("▼  Choose", "Vol DOWN", 0xFF3A2C0E, v -> svcKey(false)), weight());
        root.addView(padRow, topLp(10));
        LinearLayout small = hbox();
        small.addView(chip("↶ Undo", v -> { WhisperService s = WhisperService.get(); if (s != null) s.brain().uiUndo(); }), weight());
        small.addView(space(dp(10)));
        small.addView(chip("🔁 Repeat", v -> { WhisperService s = WhisperService.get(); if (s != null) s.brain().uiRepeat(); }), weight());
        root.addView(small, topLp(8));
        Button other = chip("✋ I played a different move", v -> { WhisperService s = WhisperService.get(); if (s != null) s.brain().uiOtherMove(); });
        style(other, 0xFF2A1F33, 0xFFD2B6FF);
        root.addView(other, topLp(8));

        // ---- board ----
        LinearLayout boardCard = card();
        board = new BoardView(this);
        boardCard.addView(board);
        moves = text("", 13, MUTED, false);
        moves.setPadding(0, dp(8), 0, 0);
        boardCard.addView(moves);
        root.addView(boardCard, cardLp());

        // ---- game controls ----
        LinearLayout ctl = hbox();
        ctl.addView(bigButton("▶ New game", ACCENT, 0xFF111111, v -> startSvc(WhisperService.ACTION_START)), weight());
        ctl.addView(space(dp(8)));
        ctl.addView(bigButton("⏯ Resume", CARD2, TEXT, v -> startSvc(WhisperService.ACTION_RESUME)), weight());
        ctl.addView(space(dp(8)));
        ctl.addView(bigButton("⏹ Stop", 0xFF3A1A1A, RED, v -> startSvc(WhisperService.ACTION_STOP)), weight());
        root.addView(ctl, topLp(14));

        // ---- settings (collapsible) ----
        LinearLayout set = card();
        settingsHeader = text("⚙  Settings  ▾", 16, TEXT, true);
        settingsHeader.setPadding(0, dp(2), 0, dp(2));
        set.addView(settingsHeader);
        settingsBody = vbox();
        settingsHeader.setOnClickListener(v -> toggleSettings());

        settingsBody.addView(sectionLabel("I play"));
        LinearLayout seg = hbox();
        sideW = segButton("♔ White", v -> setSide(true));
        sideB = segButton("♚ Black", v -> setSide(false));
        seg.addView(sideW, weight()); seg.addView(space(dp(8))); seg.addView(sideB, weight());
        settingsBody.addView(seg);
        paintSide();

        settingsBody.addView(sectionLabel("Opening as White"));
        settingsBody.addView(spinner(Openings.WHITE_NAMES, "opW"));
        settingsBody.addView(sectionLabel("As Black vs e4"));
        settingsBody.addView(spinner(Openings.VS_E4_NAMES, "opE4"));
        settingsBody.addView(sectionLabel("As Black vs d4 / other"));
        settingsBody.addView(spinner(Openings.VS_D4_NAMES, "opD4"));

        settingsBody.addView(sectionLabel("Counting"));
        settingsBody.addView(toggle("Ask number first", "1–8 before A–H", "numberFirst"));
        settingsBody.addView(toggle("Letters H → A", "Good when you play Black", "lettersRev"));
        settingsBody.addView(toggle("Numbers 8 → 1", "Good when you play Black", "numbersRev"));

        thinkLabel = sectionLabel("");
        settingsBody.addView(thinkLabel);
        SeekBar sb = new SeekBar(this);
        sb.setMax(9);
        sb.setProgress(prefs.getInt("think", 1000) / 500 - 1);
        sb.setProgressTintList(ColorStateList.valueOf(ACCENT));
        sb.setThumbTintList(ColorStateList.valueOf(ACCENT));
        updateThink(prefs.getInt("think", 1000));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { int ms = (p + 1) * 500; prefs.edit().putInt("think", ms).apply(); updateThink(ms); refreshSvc(); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        settingsBody.addView(sb);
        set.addView(settingsBody);
        settingsBody.setVisibility(prefs.getBoolean("settingsOpen", true) ? View.VISIBLE : View.GONE);
        settingsHeader.setText(settingsBody.getVisibility() == View.VISIBLE ? "⚙  Settings  ▴" : "⚙  Settings  ▾");
        root.addView(set, cardLp());

        // ---- hide volume slider ----
        LinearLayout hide = card();
        hide.addView(text("Hide the volume slider", 16, TEXT, true));
        hideStatus = text("", 13, MUTED, false);
        hide.addView(hideStatus);
        hide.addView(text("Android shows the volume slider when you press the buttons with the screen on. Turn on “Chess Whisper – hide volume slider” in Accessibility to stop that. It only catches the volume buttons during a game.\nIf it’s greyed out: App info → ⋮ → Allow restricted settings, then try again.", 12, MUTED, false));
        Button openAcc = bigButton("Open Accessibility settings", CARD2, TEXT, v -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) { }
        });
        LinearLayout.LayoutParams alp = topLp(10);
        hide.addView(openAcc, alp);
        root.addView(hide, cardLp());

        // ---- help ----
        LinearLayout help = card();
        help.addView(text("Buttons", 16, TEXT, true));
        help.addView(helpRow("Vol UP", "Next letter / number / choice"));
        help.addView(helpRow("Vol DOWN", "Choose · “yes, I played it” · “yes, that’s their move”"));
        help.addView(helpRow("DOWN ×2", "Undo"));
        help.addView(helpRow("DOWN ×3", "Repeat my move"));
        help.addView(helpRow("DOWN ×4", "I played a different move (misheard / blunder): take back the suggestion and enter what you really played"));
        help.addView(helpRow("Earbud 1 tap", "Choose / yes (like Vol DOWN)"));
        help.addView(helpRow("Earbud 2 taps", "Next (like Vol UP)"));
        help.addView(helpRow("Earbud 3 taps", "Undo"));
        help.addView(text("Castling: enter the square the king lands on (G or C). Works with the screen off — set battery to “Unrestricted” for this app.", 12, MUTED, false));
        root.addView(help, cardLp());

        root.addView(text("Engine: CuckooChess (GPLv3) · Pieces: cburnett (CC BY-SA 3.0) · Opening names: lichess (CC0)", 11, 0xFF5C6673, false), topLp(12));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        sv.addView(root);
        setContentView(sv);
    }

    @Override protected void onResume() {
        super.onResume(); WhisperService.setListener(this); onBrainChanged();
        boolean on = KeyService.isOn();
        hideStatus.setText(on ? "✅ On — no volume slider during games" : "Off — the slider will pop up when the screen is on");
        hideStatus.setTextColor(on ? GREEN : MUTED);
    }
    @Override protected void onPause() { WhisperService.setListener(null); super.onPause(); }

    /** With the app on screen, the keys come here first. */
    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (isVol(code) && running()) {
            if (e.getRepeatCount() == 0) svcKey(code == KeyEvent.KEYCODE_VOLUME_UP);
            return true;
        }
        return super.onKeyDown(code, e);
    }
    @Override public boolean onKeyUp(int code, KeyEvent e) {
        if (isVol(code) && running()) return true;
        return super.onKeyUp(code, e);
    }
    private static boolean isVol(int c) { return c == KeyEvent.KEYCODE_VOLUME_UP || c == KeyEvent.KEYCODE_VOLUME_DOWN; }
    private boolean running() { WhisperService s = WhisperService.get(); return s != null && s.brain().busy(); }

    private void svcKey(boolean up) {
        WhisperService s = WhisperService.get();
        if (s == null) return;
        board.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        s.key(up);
    }

    @Override public void onBrainChanged() {
        WhisperService s = WhisperService.get();
        boolean on = s != null && s.brain().busy();
        setPill(on);
        if (s == null) {
            question.setText(""); item.setText(""); hint.setText("");
            big.setText("Press New game to start");
            opening.setText(""); moves.setText("");
            board.show(null, prefs.getBoolean("white", true));
            return;
        }
        Brain br = s.brain();
        String q = br.question;
        question.setText(q);
        question.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
        String cur = br.currentItem();
        item.setText(cur);
        item.setVisibility(cur.isEmpty() ? View.GONE : View.VISIBLE);
        item.setTextSize(cur.length() > 3 ? 34 : 64);
        big.setText(br.big.isEmpty() ? br.status : br.big);
        hint.setText(br.status);
        String op = br.openingNow();
        opening.setText(op == null ? "" : "📖 " + op);
        opening.setVisibility(op == null ? View.GONE : View.VISIBLE);
        moves.setText(br.movesText());
        board.show(br.core, br.settings.iAmWhite);
    }

    private void startSvc(String action) {
        if (WhisperService.ACTION_STOP.equals(action) && WhisperService.get() == null) return;
        Intent i = new Intent(this, WhisperService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        board.postDelayed(this::onBrainChanged, 400);
    }

    // ---------- settings actions ----------
    private void toggleSettings() {
        boolean open = settingsBody.getVisibility() != View.VISIBLE;
        settingsBody.setVisibility(open ? View.VISIBLE : View.GONE);
        settingsHeader.setText(open ? "⚙  Settings  ▴" : "⚙  Settings  ▾");
        prefs.edit().putBoolean("settingsOpen", open).apply();
    }
    private void setSide(boolean white) {
        prefs.edit().putBoolean("white", white).apply();
        // handy default: count from your own side when you play Black
        prefs.edit().putBoolean("lettersRev", !white).putBoolean("numbersRev", !white).apply();
        recreate();
    }
    private void paintSide() {
        boolean white = prefs.getBoolean("white", true);
        style(sideW, white ? ACCENT : CARD2, white ? 0xFF111111 : TEXT);
        style(sideB, !white ? ACCENT : CARD2, !white ? 0xFF111111 : TEXT);
    }
    private void updateThink(int ms) { thinkLabel.setText("Engine think time: " + (ms / 1000f) + " s  (longer = stronger)"); }
    private void refreshSvc() {
        WhisperService s = WhisperService.get();
        if (s != null) { boolean white = s.brain().settings.iAmWhite; s.loadSettings(); s.brain().settings.iAmWhite = white; }
    }
    private void setPill(boolean on) {
        pill.setText(on ? "● Listening" : "○ Off");
        pill.setTextColor(on ? 0xFF0E1116 : MUTED);
        pill.setBackground(round(on ? GREEN : CARD2, dp(20)));
    }

    // ---------- view helpers ----------
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private GradientDrawable round(int color, int radius) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(radius); return g; }
    private LinearLayout vbox() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout hbox() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private View space(int w) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(w, 1)); return v; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); }
    private LinearLayout.LayoutParams topLp(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(top); return lp;
    }
    private LinearLayout.LayoutParams cardLp() { return topLp(14); }
    private LinearLayout card() {
        LinearLayout c = vbox();
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        GradientDrawable g = round(CARD, dp(16)); g.setStroke(dp(1), LINE);
        c.setBackground(g);
        return c;
    }
    private TextView text(String t, int sp, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(t); v.setTextSize(sp); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }
    private TextView sectionLabel(String t) { TextView v = text(t, 13, MUTED, true); v.setPadding(0, dp(14), 0, dp(6)); return v; }
    private void style(Button b, int bg, int fg) { b.setBackground(round(bg, dp(12))); b.setTextColor(fg); }
    private Button baseButton(String t, View.OnClickListener l) {
        Button b = new Button(this); b.setText(t); b.setAllCaps(false); b.setTextSize(15); b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null); b.setOnClickListener(l); b.setMinHeight(dp(48)); return b;
    }
    private Button bigButton(String t, int bg, int fg, View.OnClickListener l) { Button b = baseButton(t, l); style(b, bg, fg); return b; }
    private Button segButton(String t, View.OnClickListener l) { return baseButton(t, l); }
    private Button chip(String t, View.OnClickListener l) { Button b = baseButton(t, l); b.setTextSize(14); style(b, CARD, TEXT); return b; }
    private View padButton(String t, String sub, int bg, View.OnClickListener l) {
        LinearLayout box = vbox();
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(8), dp(16), dp(8), dp(14));
        GradientDrawable g = round(bg, dp(16)); g.setStroke(dp(1), LINE);
        box.setBackground(g);
        TextView a = text(t, 20, TEXT, true); a.setGravity(Gravity.CENTER);
        TextView s = text(sub, 12, MUTED, false); s.setGravity(Gravity.CENTER);
        box.addView(a); box.addView(s);
        box.setClickable(true); box.setOnClickListener(l);
        return box;
    }
    private View toggle(String title, String sub, String key) {
        LinearLayout row = hbox(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(6), 0, dp(6));
        LinearLayout txt = vbox();
        txt.addView(text(title, 15, TEXT, false));
        txt.addView(text(sub, 12, MUTED, false));
        row.addView(txt, weight());
        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(key, false));
        sw.setThumbTintList(ColorStateList.valueOf(ACCENT));
        sw.setOnCheckedChangeListener((v, on) -> { prefs.edit().putBoolean(key, on).apply(); refreshSvc(); });
        row.addView(sw);
        return row;
    }
    private View helpRow(String key, String what) {
        LinearLayout row = hbox(); row.setPadding(0, dp(6), 0, 0);
        TextView k = text(key, 13, ACCENT, true);
        k.setLayoutParams(new LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(k);
        row.addView(text(what, 13, TEXT, false), weight());
        return row;
    }
    private Spinner spinner(String[][] opts, String key) {
        Spinner sp = new Spinner(this, Spinner.MODE_DROPDOWN);
        String[] labels = new String[opts.length];
        int sel = 0; String cur = prefs.getString(key, "none");
        for (int i = 0; i < opts.length; i++) { labels[i] = opts[i][1]; if (opts[i][0].equals(cur)) sel = i; }
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                TextView v = (TextView) super.getView(pos, cv, parent);
                v.setTextColor(TEXT); v.setTextSize(15); v.setPadding(dp(12), dp(12), dp(12), dp(12)); return v;
            }
            @Override public View getDropDownView(int pos, View cv, ViewGroup parent) {
                TextView v = (TextView) super.getDropDownView(pos, cv, parent);
                v.setTextColor(TEXT); v.setBackgroundColor(CARD2); v.setTextSize(15); v.setPadding(dp(14), dp(14), dp(14), dp(14)); return v;
            }
        };
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(sel);
        sp.setBackground(round(CARD2, dp(12)));
        sp.setPopupBackgroundDrawable(round(CARD2, dp(12)));
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { prefs.edit().putString(key, opts[pos][0]).apply(); refreshSvc(); }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        return sp;
    }
}
