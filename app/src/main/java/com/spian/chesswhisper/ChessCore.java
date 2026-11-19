package com.spian.chesswhisper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import chess.ChessParseError;
import chess.ComputerPlayer;
import chess.Move;
import chess.MoveGen;
import chess.Piece;
import chess.Position;
import chess.TextIO;
import chess.TwoReturnValues;
import chess.UndoInfo;

/** Board, rules, engine, openings. Pure Java (no Android), so it can be tested on a PC. */
public class ChessCore {

    /** One move with everything needed to describe it out loud. */
    public static class Mv {
        public final String uci;     // e2e4, e7e8q
        public final String san;     // e4, Nxf3+, O-O
        public final int from, to;   // 0..63, a1 = 0
        public final char piece;     // p n b r q k
        public final char captured;  // 0 if none
        public final boolean white;
        public final boolean shortCastle, longCastle, enPassant;
        public final char promotion; // 0 if none

        Mv(Position pos, Move m) {
            int p = pos.getPiece(m.from);
            white = pos.whiteMove;
            from = m.from; to = m.to;
            piece = type(p);
            boolean ep = piece == 'p' && Position.getX(m.from) != Position.getX(m.to) && pos.getPiece(m.to) == Piece.EMPTY;
            enPassant = ep;
            captured = ep ? 'p' : (pos.getPiece(m.to) == Piece.EMPTY ? 0 : type(pos.getPiece(m.to)));
            shortCastle = piece == 'k' && Position.getX(m.to) - Position.getX(m.from) == 2;
            longCastle = piece == 'k' && Position.getX(m.from) - Position.getX(m.to) == 2;
            promotion = m.promoteTo == Piece.EMPTY ? 0 : type(m.promoteTo);
            san = TextIO.moveToString(pos, m, false);
            uci = TextIO.moveToUCIString(m);
        }
        public String fromSq() { return TextIO.squareToString(from); }
        public String toSq() { return TextIO.squareToString(to); }
    }

    public static char type(int p) {
        switch (p) {
            case Piece.WKING: case Piece.BKING: return 'k';
            case Piece.WQUEEN: case Piece.BQUEEN: return 'q';
            case Piece.WROOK: case Piece.BROOK: return 'r';
            case Piece.WBISHOP: case Piece.BBISHOP: return 'b';
            case Piece.WKNIGHT: case Piece.BKNIGHT: return 'n';
            case Piece.WPAWN: case Piece.BPAWN: return 'p';
            default: return 0;
        }
    }

    public static String name(char t) {
        switch (t) {
            case 'p': return "Pawn"; case 'n': return "Knight"; case 'b': return "Bishop";
            case 'r': return "Rook"; case 'q': return "Queen"; case 'k': return "King";
        }
        return "?";
    }

    private Position start;
    private Position pos;
    private final List<Move> moves = new ArrayList<>();
    private final List<UndoInfo> undos = new ArrayList<>();
    private final List<Mv> history = new ArrayList<>();
    private final ComputerPlayer engine = new ComputerPlayer();
    private Map<String, String> eco = new HashMap<>();
    public int thinkMillis = 1000;

    public ChessCore() {
        engine.useBook(false);
        engine.verbose = false;
        reset(TextIO.startPosFEN);
    }

    public void loadEco(InputStream in) {
        Map<String, String> m = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int t = line.indexOf('\t');
                if (t > 0) m.put(line.substring(0, t), line.substring(t + 1));
            }
        } catch (Exception ignored) { }
        eco = m;
    }

    public final void reset(String fen) {
        try { start = TextIO.readFEN(fen); } catch (ChessParseError e) { throw new IllegalArgumentException(e); }
        pos = new Position(start);
        moves.clear(); undos.clear(); history.clear();
    }

    public String startFen() { return TextIO.toFEN(start); }
    public boolean fromStartPosition() { return TextIO.toFEN(start).equals(TextIO.startPosFEN); }
    public boolean whiteToMove() { return pos.whiteMove; }
    public Position position() { return pos; }
    public List<Mv> history() { return history; }
    public Mv last() { return history.isEmpty() ? null : history.get(history.size() - 1); }
    public int plies() { return history.size(); }

    public List<Move> legal() {
        MoveGen.MoveList ml = new MoveGen().pseudoLegalMoves(pos);
        MoveGen.removeIllegal(pos, ml);
        List<Move> out = new ArrayList<>();
        for (int i = 0; i < ml.size; i++) out.add(ml.m[i]);
        return out;
    }

    /** Legal moves that land on a square. Promotions are reduced to queen only. */
    public List<Mv> movesTo(int sq) {
        List<Mv> out = new ArrayList<>();
        for (Move m : legal()) {
            if (m.to != sq) continue;
            if (m.promoteTo != Piece.EMPTY && type(m.promoteTo) != 'q') continue;
            out.add(new Mv(pos, m));
        }
        return out;
    }

    public Mv play(String uci) {
        for (Move m : legal()) {
            if (TextIO.moveToUCIString(m).equals(uci) ||
                (uci.length() == 4 && m.promoteTo != Piece.EMPTY && type(m.promoteTo) == 'q'
                        && TextIO.moveToUCIString(m).startsWith(uci))) {
                Mv d = new Mv(pos, m);
                UndoInfo ui = new UndoInfo();
                pos.makeMove(m, ui);
                moves.add(m); undos.add(ui); history.add(d);
                return d;
            }
        }
        return null;
    }

    public boolean undo() {
        if (moves.isEmpty()) return false;
        int i = moves.size() - 1;
        pos.unMakeMove(moves.remove(i), undos.remove(i));
        history.remove(i);
        return true;
    }

    public boolean inCheck() { return MoveGen.inCheck(pos); }
    public boolean noMoves() { return legal().isEmpty(); }
    public boolean checkmate() { return noMoves() && inCheck(); }
    public boolean gameOver() {
        if (noMoves()) return true;
        if (Integer.parseInt(TextIO.toFEN(pos).split(" ")[4]) >= 100) return true;
        return insufficient();
    }
    private boolean insufficient() {
        int minors = 0;
        for (int sq = 0; sq < 64; sq++) {
            char t = type(pos.getPiece(sq));
            if (t == 'p' || t == 'r' || t == 'q') return false;
            if (t == 'n' || t == 'b') minors++;
        }
        return minors <= 1;
    }

    /** Engine best move (UCI). Blocking: call from a background thread. */
    public String engineMove() {
        Position copy = new Position(pos);
        TwoReturnValues<Move, String> r = engine.searchPosition(copy, thinkMillis);
        return r.first == null ? null : TextIO.moveToUCIString(r.first);
    }

    /** Emergency move if the engine fails: a capture or check if there is one, else the first legal move. */
    public String fallbackMove() {
        List<Move> l = legal();
        if (l.isEmpty()) return null;
        for (Move m : l) if (pos.getPiece(m.to) != Piece.EMPTY) return TextIO.moveToUCIString(m);
        return TextIO.moveToUCIString(l.get(0));
    }

    // ---------- opening name (from the moves played) ----------
    private static String key(Position p) {
        String[] f = TextIO.toFEN(p).split(" ");
        return f[0] + " " + f[1] + " " + f[2];
    }
    public String openingName() {
        if (!fromStartPosition()) return null;
        Position p = new Position(start);
        String name = null;
        UndoInfo ui = new UndoInfo();
        for (Move m : moves) {
            p.makeMove(m, ui);
            String n = eco.get(key(p));
            if (n != null) name = n;
        }
        return name;
    }

    // ---------- opening book (your chosen opening) ----------
    public String bookMove(String[] line) {
        if (line == null || line.length == 0 || !fromStartPosition()) return null;
        boolean white = pos.whiteMove;
        List<String> mine = new ArrayList<>();
        for (Mv h : history) if (h.white == white) mine.add(clean(h.san));
        if (mine.size() >= 10) return null;
        List<Move> legal = legal();
        for (String want : line) {
            if (mine.contains(want)) continue;
            for (Move m : legal) {
                if (clean(TextIO.moveToString(pos, m, false)).equals(want)) {
                    return dropsMaterial(m) ? null : TextIO.moveToUCIString(m);
                }
            }
        }
        return null;
    }
    private static String clean(String san) { return san.replace("+", "").replace("#", ""); }

    private static int value(char t) {
        switch (t) { case 'p': return 100; case 'n': case 'b': return 320; case 'r': return 500; case 'q': return 900; }
        return 0;
    }
    /** Rough check: after this move, can the opponent win a piece outright? */
    private boolean dropsMaterial(Move m) {
        UndoInfo ui = new UndoInfo();
        pos.makeMove(m, ui);
        boolean bad = false;
        MoveGen.MoveList ml = new MoveGen().pseudoLegalMoves(pos);
        MoveGen.removeIllegal(pos, ml);
        for (int i = 0; i < ml.size && !bad; i++) {
            Move c = ml.m[i];
            char victim = type(pos.getPiece(c.to));
            if (victim == 0) continue;
            char attacker = type(pos.getPiece(c.from));
            // side to move is the opponent here, so this asks: do *we* attack that square (can we recapture)?
            boolean defended = MoveGen.sqAttacked(pos, c.to);
            int gain = value(victim) - (defended ? value(attacker) : 0);
            if (gain >= 200) bad = true;
        }
        pos.unMakeMove(m, ui);
        return bad;
    }

    // ---------- save / load ----------
    public String saveString() {
        StringBuilder sb = new StringBuilder(startFen()).append('|');
        for (Mv h : history) sb.append(h.uci).append(' ');
        return sb.toString().trim();
    }
    public boolean loadString(String s) {
        try {
            int bar = s.indexOf('|');
            reset(s.substring(0, bar));
            String rest = s.substring(bar + 1).trim();
            if (!rest.isEmpty()) for (String u : rest.split(" ")) if (play(u) == null) return false;
            return true;
        } catch (Exception e) { reset(TextIO.startPosFEN); return false; }
    }

    public static int square(char file, char rank) { return (file - 'a') + 8 * (rank - '1'); }

    /** Piece letter on a square for drawing: 'K','q',... or 0. Uppercase = white. */
    public char pieceAt(int sq) {
        int p = pos.getPiece(sq);
        char t = type(p);
        if (t == 0) return 0;
        return Piece.isWhite(p) ? Character.toUpperCase(t) : t;
    }
}
