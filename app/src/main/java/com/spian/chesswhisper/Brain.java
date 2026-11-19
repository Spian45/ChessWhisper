package com.spian.chesswhisper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The game flow, driven by two buttons:
 *   Volume UP   = next letter / number / choice  (or repeat, when it's asking you to confirm)
 *   Volume DOWN = choose / yes
 *   Volume DOWN x2 (quick) = undo
 *   Volume DOWN x3 (quick) = repeat your last move
 * Pure Java: the Android service plugs in speech, timers and saving through {@link Host}.
 */
public class Brain {

    public interface Host {
        void say(String text);                       // speak (replaces anything being spoken)
        void post(Runnable r, long delayMs);         // run on the main thread later
        void cancel(Runnable r);
        void changed();                              // board / status changed: redraw UI
        void save(String state);                     // persist the game
        void ended();                                // game finished: give the volume buttons back soon
    }

    public static class Settings {
        public boolean iAmWhite = true;
        public boolean numberFirst = false;
        public boolean lettersReversed = false;     // H..A
        public boolean numbersReversed = false;     // 8..1
        public String[] openingWhite = new String[0];
        public String[] openingVsE4 = new String[0];
        public String[] openingVsD4 = new String[0];
    }

    enum State { IDLE, THINKING, MY_CONFIRM, ENTER, ENEMY_CONFIRM, OVER }

    static final long MULTI_MS = 450;
    static final long REPEAT_MS = 6000;

    public final ChessCore core;
    public Settings settings = new Settings();
    private final Host host;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    State state = State.IDLE;
    public String status = "Press New game";
    public String big = "";
    public String question = "";     // what it is asking right now (for the screen)
    private String myText = "";        // what to say for my move
    private String myExtra = null;     // opening name to add once
    private String enemyText = "";
    private int thinkToken = 0;

    // entering the friend's move
    private List<Item> items = new ArrayList<>();
    private int idx = 0;
    private int stage = 0;             // 0 = nothing picked yet
    private Picker picker;
    private char pickFile, pickRank;

    private int downCount = 0;
    private final Runnable resolveDown = new Runnable() { public void run() { int n = downCount; downCount = 0; onDown(n); } };
    private final Runnable repeater = new Runnable() { public void run() { repeatNow(); } };
    private String spokenOpening = null;

    static class Item { final String say; final Object val; Item(String s, Object v) { say = s; val = v; } }
    interface Picker { void pick(Object val); }

    public Brain(ChessCore core, Host host) { this.core = core; this.host = host; }

    // ================= buttons =================
    private long lastUp = 0, lastDown = 0;
    static final long DEBOUNCE_MS = 60;     // ignore key auto-repeat / double events

    public void volUp() {
        long now = System.currentTimeMillis();
        if (now - lastUp < DEBOUNCE_MS) return;
        lastUp = now;
        switch (state) {
            case ENTER:
                if (items.isEmpty()) return;
                idx = (idx + 1) % items.size();
                say(items.get(idx).say, null);
                break;
            case MY_CONFIRM: case ENEMY_CONFIRM:
                repeatNow();
                break;
            default:
                break;
        }
    }

    public void volDown() {
        long now = System.currentTimeMillis();
        if (now - lastDown < DEBOUNCE_MS) return;
        lastDown = now;
        downCount++;
        host.cancel(resolveDown);
        host.post(resolveDown, MULTI_MS);
    }

    private void onDown(int n) {
        if (state == State.IDLE || state == State.OVER) { if (n == 1 && state == State.OVER) say(status, status); return; }
        if (n >= 3) { repeatMyMove(); return; }
        if (n == 2) { undo(); return; }
        switch (state) {
            case MY_CONFIRM:
                stopRepeat(); say("OK", "OK");
                if (!checkEnd()) host.post(new Runnable() { public void run() { enemyTurn(); } }, 700);
                break;
            case ENEMY_CONFIRM:
                stopRepeat(); persist(false); say("OK", "OK");
                host.post(new Runnable() { public void run() { myTurn(); } }, 700);
                break;
            case ENTER:
                if (items.isEmpty()) return;
                Item it = items.get(idx);
                picker.pick(it.val);
                break;
            default:
                break;
        }
        host.changed();
    }

    // ================= game control =================
    public void newGame(boolean iAmWhite) {
        cancelAll();
        settings.iAmWhite = iAmWhite;
        core.reset(chess.TextIO.startPosFEN);
        spokenOpening = null;
        persist(false);
        say("New game. You are " + (iAmWhite ? "White" : "Black"), "New game");
        host.post(new Runnable() { public void run() { if (iAmWhite) myTurn(); else enemyTurn(); } }, 1800);
    }

    /** Continue from the saved state. */
    public void resume(boolean pendingConfirm) {
        cancelAll();
        spokenOpening = core.openingName();
        if (checkEnd()) return;
        final ChessCore.Mv last = core.last();
        boolean myTurnNow = core.whiteToMove() == settings.iAmWhite;
        if (myTurnNow && last != null && pendingConfirm) {
            // the friend's move was entered but not confirmed yet
            say("Resumed. Check their move", "Resumed");
            host.post(new Runnable() { public void run() { core.undo(); applyEnemy(last.uci); } }, 1800);
        } else if (myTurnNow) {
            say("Resumed", "Resumed");
            host.post(new Runnable() { public void run() { myTurn(); } }, 1000);
        } else if (last != null) {
            myText = describeMine(last); myExtra = null;
            say("Resumed. Your last move", "Resumed");
            host.post(new Runnable() { public void run() { waitMyConfirm(); } }, 1800);
        } else {
            say("Resumed", "Resumed");
            host.post(new Runnable() { public void run() { enemyTurn(); } }, 1000);
        }
    }

    public void stop() {
        boolean over = state == State.OVER;
        cancelAll();
        state = over ? State.OVER : State.IDLE;
        if (!over) status = "Stopped";
        host.changed();
    }

    private void cancelAll() {
        thinkToken++;
        host.cancel(resolveDown); downCount = 0;
        stopRepeat();
        items = new ArrayList<>(); question = "";
        state = State.IDLE;
    }

    // ================= my turn =================
    private void myTurn() {
        if (checkEnd()) return;
        state = State.THINKING; status = "Thinking…"; host.changed();
        final int token = ++thinkToken;
        final String book = core.bookMove(currentOpening());
        if (book != null) { finishMyMove(book, true); return; }
        worker.execute(new Runnable() { public void run() {
            String u;
            try { u = core.engineMove(); }
            catch (Throwable t) { u = core.fallbackMove(); }      // never get stuck
            final String uci = u;
            host.post(new Runnable() { public void run() {
                if (token != thinkToken) return;
                finishMyMove(uci, false);
            } }, 0);
        } });
    }

    private void finishMyMove(String uci, boolean book) {
        ChessCore.Mv m = uci == null ? null : core.play(uci);
        if (m == null) { status = "Engine error"; state = State.IDLE; host.changed(); return; }
        persist(false);
        myText = describeMine(m);
        myExtra = newOpening();
        big = (book ? "📖 " : "") + bigText(m);
        waitMyConfirm();
    }

    private void waitMyConfirm() {
        state = State.MY_CONFIRM; question = "";
        status = "Play it, then press Volume DOWN";
        String t = myExtra != null ? myText + ". Opening: " + myExtra : myText;
        myExtra = null;
        say(t, big);
        scheduleRepeat();
        host.changed();
    }

    String describeMine(ChessCore.Mv m) {
        String to = letter(m.toSq().charAt(0)) + "… " + m.toSq().charAt(1);
        String from = letter(m.fromSq().charAt(0)) + " " + m.fromSq().charAt(1);
        String pn = ChessCore.name(m.piece);
        String t;
        if (m.shortCastle) t = "Castle short, king side. King to G " + m.toSq().charAt(1);
        else if (m.longCastle) t = "Castle long, queen side. King to C " + m.toSq().charAt(1);
        else if (m.captured != 0) t = pn + " from " + from + ", takes " + ChessCore.name(m.captured) + "… on " + to + (m.enPassant ? ", en passant" : "");
        else if (hasTwin(m)) t = pn + " from " + from + ", to " + to;
        else t = pn + "… " + to;
        if (m.promotion != 0) t += ", promote queen";
        if (isLast(m)) {
            if (core.checkmate()) t += ", checkmate";
            else if (core.inCheck()) t += ", check";
        }
        return t;
    }

    private boolean isLast(ChessCore.Mv m) { ChessCore.Mv l = core.last(); return l != null && l.uci.equals(m.uci) && l.white == m.white; }

    /** Could another piece of the same type have gone to the same square? */
    private boolean hasTwin(ChessCore.Mv m) {
        if (!isLast(m)) return false;
        core.undo();
        boolean twin = false;
        for (ChessCore.Mv x : core.movesTo(m.to)) if (x.piece == m.piece && x.from != m.from) twin = true;
        core.play(m.uci);
        return twin;
    }

    static String bigText(ChessCore.Mv m) {
        if (m.shortCastle) return "O-O  (castle short)";
        if (m.longCastle) return "O-O-O  (castle long)";
        return ChessCore.name(m.piece) + " " + m.fromSq() + (m.captured != 0 ? " × " + ChessCore.name(m.captured) + " " : " → ") + m.toSq();
    }

    // ================= friend's turn =================
    private void enemyTurn() {
        if (checkEnd()) return;
        state = State.ENTER; stage = 0;
        status = "Their move: Vol UP = next, Vol DOWN = choose";
        final boolean letterFirst = !settings.numberFirst;
        List<Item> first = letterFirst ? letters() : numbers();
        final List<Item> second = letterFirst ? numbers() : letters();
        ask(first, "Their move", new Picker() { public void pick(Object v) {
            if (letterFirst) pickFile = (Character) v; else pickRank = (Character) v;
            stage = 1;
            ask(second, null, new Picker() { public void pick(Object v2) {
                if (letterFirst) pickRank = (Character) v2; else pickFile = (Character) v2;
                squareChosen(ChessCore.square(pickFile, pickRank));
            } }, letterFirst ? "Their move: row (number) · column " + letter(pickFile) : "Their move: column (letter) · row " + pickRank);
        } }, letterFirst ? "Their move: column (letter)" : "Their move: row (number)");
    }

    private void ask(List<Item> list, String prompt, Picker p) { ask(list, prompt, p, null); }
    private void ask(List<Item> list, String prompt, Picker p, String label) {
        items = list; idx = 0; picker = p;
        question = label != null ? label : (prompt != null ? prompt : question);
        String first = list.get(0).say;
        say(prompt != null ? prompt + ". " + first : first, null);
        host.changed();
    }

    private List<Item> letters() {
        List<Item> l = new ArrayList<>();
        for (int i = 0; i < 8; i++) { char f = (char) ('a' + (settings.lettersReversed ? 7 - i : i)); l.add(new Item(letter(f), f)); }
        return l;
    }
    private List<Item> numbers() {
        List<Item> l = new ArrayList<>();
        for (int i = 0; i < 8; i++) { char r = (char) ('1' + (settings.numbersReversed ? 7 - i : i)); l.add(new Item(String.valueOf(r), r)); }
        return l;
    }

    private void squareChosen(int sq) {
        final List<ChessCore.Mv> cands = core.movesTo(sq);
        if (cands.isEmpty()) {
            say("No move there. Again", "Again");
            host.post(new Runnable() { public void run() { enemyTurn(); } }, 1500);
            return;
        }
        LinkedHashSet<Character> types = new LinkedHashSet<>();
        for (ChessCore.Mv m : cands) types.add(m.piece);
        if (types.size() > 1) {
            List<Item> l = new ArrayList<>();
            for (char t : types) l.add(new Item(ChessCore.name(t) + "?", t));
            stage = 2;
            ask(l, "Which piece", new Picker() { public void pick(Object v) {
                List<ChessCore.Mv> f = new ArrayList<>();
                for (ChessCore.Mv m : cands) if (m.piece == (Character) v) f.add(m);
                chooseFrom(f);
            } });
        } else chooseFrom(cands);
    }

    private void chooseFrom(final List<ChessCore.Mv> cands) {
        if (cands.size() > 1) {
            List<Item> l = new ArrayList<>();
            for (ChessCore.Mv m : cands) l.add(new Item("From " + letter(m.fromSq().charAt(0)) + " " + m.fromSq().charAt(1) + "?", m.uci));
            stage = 3;
            ask(l, "Which one", new Picker() { public void pick(Object v) { applyEnemy((String) v); } });
        } else applyEnemy(cands.get(0).uci);
    }

    private void applyEnemy(String uci) {
        items = new ArrayList<>(); question = "";
        ChessCore.Mv m = core.play(uci);
        if (m == null) { enemyTurn(); return; }
        String sq = letter(m.toSq().charAt(0)) + " " + m.toSq().charAt(1);
        String from = letter(m.fromSq().charAt(0)) + " " + m.fromSq().charAt(1);
        String t;
        if (m.shortCastle) t = "They castled short, king side";
        else if (m.longCastle) t = "They castled long, queen side";
        else if (m.captured != 0) t = ChessCore.name(m.piece) + " from " + from + ", takes your " + ChessCore.name(m.captured) + " on " + sq;
        else t = ChessCore.name(m.piece) + " from " + from + ", to " + sq;
        if (core.checkmate()) t += ", checkmate";
        else if (core.inCheck()) t += ", check";
        enemyText = t;
        String extra = newOpening();
        big = "Them: " + bigText(m);
        state = State.ENEMY_CONFIRM;
        status = "Right? Vol DOWN = yes · Vol DOWN x2 = wrong";
        persist(true);
        say(extra != null ? t + ". Opening: " + extra : t, big);
        scheduleRepeat();
        host.changed();
    }

    // ================= undo / repeat =================
    public void uiUndo() { if (busy()) undo(); }
    public void uiRepeat() { if (busy()) repeatMyMove(); }

    void undo() {
        if (state == State.ENTER && stage > 0) {               // wrong letter / number / piece
            say("Back", "Back");
            items = new ArrayList<>();
            host.post(new Runnable() { public void run() { enemyTurn(); } }, 800);
            return;
        }
        if (state == State.ENTER && stage == 0 && core.last() != null && core.last().white == settings.iAmWhite) {
            // pressed "I played it" by mistake: hear my move again
            items = new ArrayList<>();
            say("Back", "Back");
            host.post(new Runnable() { public void run() { waitMyConfirm(); } }, 800);
            return;
        }
        cancelAll();
        if (core.plies() > 0 && core.last().white != settings.iAmWhite) {
            core.undo();                                         // remove the friend's move
        } else if (core.plies() >= 2) {
            core.undo(); core.undo();                           // remove my move and the friend's before it
        } else if (core.plies() == 1) {
            core.undo();
        }
        spokenOpening = core.openingName();
        persist(false);
        say("Undo", "Undo");
        big = "";
        host.post(new Runnable() { public void run() {
            if (core.whiteToMove() == settings.iAmWhite) myTurn(); else enemyTurn();
        } }, 1000);
        host.changed();
    }

    private void repeatMyMove() {
        if (state == State.ENEMY_CONFIRM || state == State.MY_CONFIRM) { repeatNow(); return; }
        if (myText.isEmpty()) { say("Nothing yet", "Nothing yet"); return; }
        say("Your move was. " + myText, big);
        if (state == State.ENTER) {
            final List<Item> l = items; final Picker p = picker; final int i = idx;
            host.post(new Runnable() { public void run() {
                if (state == State.ENTER && items == l) { idx = i; say(l.get(i).say, l.get(i).say); }
            } }, 4500);
        }
    }

    private void repeatNow() {
        if (state == State.MY_CONFIRM) say(myText, big);
        else if (state == State.ENEMY_CONFIRM) say(enemyText, big);
        else return;
        scheduleRepeat();
    }
    private void scheduleRepeat() { host.cancel(repeater); host.post(repeater, REPEAT_MS); }
    private void stopRepeat() { host.cancel(repeater); }

    // ================= helpers =================
    private boolean checkEnd() {
        if (!core.gameOver()) return false;
        cancelAll();
        state = State.OVER;
        if (core.checkmate()) status = core.whiteToMove() == settings.iAmWhite ? "Checkmate. You lost." : "Checkmate. You win!";
        else status = "Draw.";
        say(status + " Volume buttons are back to normal.", status);
        host.changed();
        host.ended();
        return true;
    }

    private String[] currentOpening() {
        if (settings.iAmWhite) return settings.openingWhite;
        List<ChessCore.Mv> h = core.history();
        boolean vsE4 = !h.isEmpty() && h.get(0).white && h.get(0).san.equals("e4");
        return vsE4 ? settings.openingVsE4 : settings.openingVsD4;
    }

    private String newOpening() {
        String n = core.openingName();
        if (n != null && !n.equals(spokenOpening)) { spokenOpening = n; return n; }
        return null;
    }

    public String openingNow() { return core.openingName(); }

    private void persist(boolean pendingConfirm) {
        host.save((settings.iAmWhite ? "w" : "b") + "#" + (pendingConfirm ? "1" : "0") + "#" + core.saveString());
    }

    private void say(String speech, String shown) {
        if (shown != null && !shown.isEmpty()) big = shown.equals("OK") || shown.equals("Back") || shown.equals("Undo") ? big : shown;
        host.say(speech);
        host.changed();               // keep the screen in sync with what is spoken
    }

    static String letter(char f) { return String.valueOf(Character.toUpperCase(f)); }

    /** Move list like "1. e4 e5 2. Nf3" for the screen. */
    public String movesText() {
        StringBuilder sb = new StringBuilder();
        List<ChessCore.Mv> h = core.history();
        boolean startWhite = h.isEmpty() || h.get(0).white;
        int n = 1;
        for (int i = 0; i < h.size(); i++) {
            ChessCore.Mv m = h.get(i);
            if (m.white) sb.append(n).append(". ");
            else if (i == 0 && !startWhite) sb.append(n).append("… ");
            sb.append(m.san).append(' ');
            if (!m.white) n++;
        }
        return sb.toString().trim();
    }
    public boolean isMyTurnUi() { return state == State.MY_CONFIRM || state == State.THINKING; }
    public boolean isEntering() { return state == State.ENTER; }

    /** Item currently being offered (for the screen). */
    public String currentItem() { return state == State.ENTER && !items.isEmpty() ? items.get(idx).say : ""; }
    public boolean busy() { return state != State.IDLE && state != State.OVER; }
}
