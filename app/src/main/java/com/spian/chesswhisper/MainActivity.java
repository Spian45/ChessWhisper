package com.spian.chesswhisper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

/** Settings + board. All UI is built in code (no XML) to keep the project small. */
public class MainActivity extends Activity implements WhisperService.UiListener {

    private TextView status, big, item, opening, think;
    private BoardView board;
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(WhisperService.PREFS, MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        col.setPadding(pad, pad, pad, pad);
        col.setBackgroundColor(0xFF111111);

        TextView title = label("Chess Whisper", 22, Color.WHITE); title.setTypeface(null, Typeface.BOLD);
        col.addView(title);
        col.addView(label("Volume UP = next (A, B, C… / 1, 2, 3…)\nVolume DOWN = choose / yes\nVolume DOWN ×2 = undo   ·   ×3 = repeat my move\nWorks with the screen off. Earbud tap = Volume DOWN.", 13, 0xFF999999));

        // side
        RadioGroup side = new RadioGroup(this); side.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton w = radio("I play White"), bl = radio("I play Black");
        side.addView(w); side.addView(bl);
        (prefs.getBoolean("white", true) ? w : bl).setChecked(true);
        side.setOnCheckedChangeListener((g, id) -> save("white", id == w.getId()));
        col.addView(side);

        // openings
        col.addView(label("Opening as White", 14, Color.WHITE));
        col.addView(spinner(Openings.WHITE_NAMES, "opW"));
        col.addView(label("As Black, if they play e4", 14, Color.WHITE));
        col.addView(spinner(Openings.VS_E4_NAMES, "opE4"));
        col.addView(label("As Black, if they play d4 / other", 14, Color.WHITE));
        col.addView(spinner(Openings.VS_D4_NAMES, "opD4"));

        // counting
        col.addView(check("Ask number first (1-8, then A-H)", "numberFirst"));
        col.addView(check("Letters H → A (tip: on for Black)", "lettersRev"));
        col.addView(check("Numbers 8 → 1 (tip: on for Black)", "numbersRev"));

        // think time
        think = label("", 14, Color.WHITE);
        col.addView(think);
        SeekBar sb = new SeekBar(this); sb.setMax(9); sb.setProgress(prefs.getInt("think", 1000) / 500 - 1);
        updateThink(prefs.getInt("think", 1000));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { int ms = (p + 1) * 500; prefs.edit().putInt("think", ms).apply(); updateThink(ms); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        col.addView(sb);

        // buttons
        LinearLayout row = new LinearLayout(this);
        row.addView(button("▶ New game", v -> startSvc(WhisperService.ACTION_START)));
        row.addView(button("⏯ Resume", v -> startSvc(WhisperService.ACTION_RESUME)));
        row.addView(button("⏹ Stop", v -> startSvc(WhisperService.ACTION_STOP)));
        col.addView(row);

        status = label("Not running", 15, 0xFFF5A524);
        big = label("", 26, 0xFFF5A524); big.setTypeface(null, Typeface.BOLD); big.setGravity(Gravity.CENTER);
        item = label("", 40, Color.WHITE); item.setGravity(Gravity.CENTER); item.setTypeface(null, Typeface.BOLD);
        opening = label("", 14, 0xFF9CC4FF); opening.setGravity(Gravity.CENTER);
        col.addView(status); col.addView(opening); col.addView(big); col.addView(item);

        board = new BoardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        col.addView(board, lp);
        col.addView(label("Engine: CuckooChess (GPLv3). Pieces: cburnett (CC BY-SA 3.0). Opening names: lichess (CC0).", 11, 0xFF666666));

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    @Override protected void onResume() { super.onResume(); WhisperService.setListener(this); onBrainChanged(); }
    @Override protected void onPause() { WhisperService.setListener(null); super.onPause(); }

    /** When the app is on screen, the keys come here first: pass them on. */
    @Override public boolean onKeyDown(int code, KeyEvent e) {
        WhisperService s = WhisperService.get();
        if (s != null && s.brain().busy() && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (e.getRepeatCount() == 0) s.key(code == KeyEvent.KEYCODE_VOLUME_UP);
            return true;
        }
        return super.onKeyDown(code, e);
    }
    @Override public boolean onKeyUp(int code, KeyEvent e) {
        WhisperService s = WhisperService.get();
        if (s != null && s.brain().busy() && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) return true;
        return super.onKeyUp(code, e);
    }

    @Override public void onBrainChanged() {
        WhisperService s = WhisperService.get();
        if (s == null) { status.setText("Not running"); big.setText(""); item.setText(""); opening.setText(""); return; }
        Brain br = s.brain();
        status.setText(br.status);
        big.setText(br.big);
        item.setText(br.currentItem());
        String on = br.openingNow();
        opening.setText(on == null ? "" : "📖 " + on);
        board.show(br.core, br.settings.iAmWhite);
    }

    private void startSvc(String action) {
        Intent i = new Intent(this, WhisperService.class).setAction(action);
        if (WhisperService.ACTION_STOP.equals(action) && WhisperService.get() == null) return;
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        board.postDelayed(this::onBrainChanged, 300);
    }

    // ---------- small UI helpers ----------
    private void updateThink(int ms) { think.setText("Engine think time: " + (ms / 1000f) + " s (longer = stronger)"); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private TextView label(String t, int sp, int color) {
        TextView v = new TextView(this); v.setText(t); v.setTextSize(sp); v.setTextColor(color); v.setPadding(0, dp(6), 0, dp(2)); return v;
    }
    private RadioButton radio(String t) { RadioButton r = new RadioButton(this); r.setText(t); r.setTextColor(Color.WHITE); r.setId(View.generateViewId()); return r; }
    private CheckBox check(String t, String key) {
        CheckBox c = new CheckBox(this); c.setText(t); c.setTextColor(Color.WHITE);
        c.setChecked(prefs.getBoolean(key, false));
        c.setOnCheckedChangeListener((v, on) -> save(key, on));
        return c;
    }
    private Button button(String t, View.OnClickListener l) {
        Button b = new Button(this); b.setText(t); b.setAllCaps(false); b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }
    private Spinner spinner(String[][] opts, String key) {
        Spinner sp = new Spinner(this);
        String[] labels = new String[opts.length];
        int sel = 0; String cur = prefs.getString(key, "none");
        for (int i = 0; i < opts.length; i++) { labels[i] = opts[i][1]; if (opts[i][0].equals(cur)) sel = i; }
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        sp.setAdapter(ad);
        sp.setSelection(sel);
        sp.setBackgroundColor(0xFF2A2A2A);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { prefs.edit().putString(key, opts[pos][0]).apply(); refreshSvc(); }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        return sp;
    }
    private void save(String key, boolean v) { prefs.edit().putBoolean(key, v).apply(); refreshSvc(); }
    /** Settings changes apply right away (except "I play", which applies on New game). */
    private void refreshSvc() {
        WhisperService s = WhisperService.get();
        if (s != null) { boolean white = s.brain().settings.iAmWhite; s.loadSettings(); s.brain().settings.iAmWhite = white; }
    }
}
