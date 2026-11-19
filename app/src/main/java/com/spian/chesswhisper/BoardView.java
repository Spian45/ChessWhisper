package com.spian.chesswhisper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

/** Simple board: blue-gray squares, classic pieces, last move highlighted. */
public class BoardView extends View {
    private final Paint light = new Paint(), dark = new Paint(), hlL = new Paint(), hlD = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Character, Bitmap> img = new HashMap<>();
    private ChessCore core;
    private boolean whiteBottom = true;

    public BoardView(Context c) {
        super(c);
        light.setColor(0xFFC4C9CD); dark.setColor(0xFF7B8D99);
        hlL.setColor(0xFFD8CF8A); hlD.setColor(0xFFAAA35A);
        text.setColor(0xFF333333); text.setFakeBoldText(true);
        String[] names = {"wk", "wq", "wr", "wb", "wn", "wp", "bk", "bq", "br", "bb", "bn", "bp"};
        for (String n : names) {
            int id = getResources().getIdentifier("p_" + n, "drawable", c.getPackageName());
            char key = n.charAt(0) == 'w' ? Character.toUpperCase(n.charAt(1)) : n.charAt(1);
            img.put(key, BitmapFactory.decodeResource(getResources(), id));
        }
    }

    public void show(ChessCore core, boolean whiteBottom) { this.core = core; this.whiteBottom = whiteBottom; invalidate(); }

    @Override protected void onMeasure(int w, int h) {
        int size = MeasureSpec.getSize(w);
        setMeasuredDimension(size, size);
    }

    @Override protected void onDraw(Canvas cv) {
        float s = getWidth() / 8f;
        text.setTextSize(s * 0.2f);
        ChessCore.Mv last = core == null ? null : core.last();
        Rect dst = new Rect();
        for (int row = 0; row < 8; row++) for (int col = 0; col < 8; col++) {
            int file = whiteBottom ? col : 7 - col;
            int rank = whiteBottom ? 7 - row : row;
            int sq = file + 8 * rank;
            boolean isLight = (file + rank) % 2 == 1;
            boolean hl = last != null && (last.from == sq || last.to == sq);
            cv.drawRect(col * s, row * s, (col + 1) * s, (row + 1) * s, hl ? (isLight ? hlL : hlD) : (isLight ? light : dark));
            if (col == 0) cv.drawText(String.valueOf((char) ('1' + rank)), col * s + s * 0.05f, row * s + s * 0.22f, text);
            if (row == 7) cv.drawText(String.valueOf((char) ('a' + file)), (col + 1) * s - s * 0.18f, (row + 1) * s - s * 0.06f, text);
            if (core != null) {
                char p = core.pieceAt(sq);
                Bitmap b = p == 0 ? null : img.get(p);
                if (b != null) {
                    dst.set((int) (col * s + s * 0.04f), (int) (row * s + s * 0.04f), (int) ((col + 1) * s - s * 0.04f), (int) ((row + 1) * s - s * 0.04f));
                    cv.drawBitmap(b, null, dst, null);
                }
            }
        }
    }
}
