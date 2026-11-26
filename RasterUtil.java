package com.kuberlaxmi.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Bitmap <-> ESC/POS raster helpers (GS v 0). */
final class RasterUtil {
    private RasterUtil() {}

    private static boolean isBlack(int c) {
        int a = (c >>> 24) & 255;
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        double lum = 0.299 * r + 0.587 * g + 0.114 * b;
        return a > 40 && lum < 170;
    }

    static Bitmap scaleToWidth(Bitmap src, int w) {
        if (src.getWidth() == w) return src;
        int h = Math.max(1, Math.round(src.getHeight() * (w / (float) src.getWidth())));
        return Bitmap.createScaledBitmap(src, w, h, true);
    }

    static Bitmap toMono(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) px[i] = isBlack(px[i]) ? 0xFF000000 : 0xFFFFFFFF;
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    static byte[] toEscPos(Bitmap src, int dots) {
        int w = (dots / 8) * 8;
        Bitmap bmp = scaleToWidth(src, w);
        int h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        int wb = w / 8;
        ByteArrayOutputStream o = new ByteArrayOutputStream(wb * h + 256);
        o.write(0x1B);
        o.write(0x40);
        final int BAND = 120;
        for (int y0 = 0; y0 < h; y0 += BAND) {
            int bh = Math.min(BAND, h - y0);
            o.write(0x1D);
            o.write(0x76);
            o.write(0x30);
            o.write(0x00);
            o.write(wb & 255);
            o.write((wb >> 8) & 255);
            o.write(bh & 255);
            o.write((bh >> 8) & 255);
            for (int y = y0; y < y0 + bh; y++) {
                for (int xb = 0; xb < wb; xb++) {
                    int v = 0;
                    for (int bit = 0; bit < 8; bit++) {
                        if (isBlack(px[y * w + xb * 8 + bit])) v |= (1 << (7 - bit));
                    }
                    o.write(v);
                }
            }
        }
        for (int i = 0; i < 5; i++) o.write(0x0A);
        return o.toByteArray();
    }

    /** Rebuild a bitmap from ESC/POS raster bands (the format the web app produces). */
    static Bitmap fromEscPos(byte[] d) {
        List<byte[]> rows = new ArrayList<>();
        int wb = 0;
        int i = 0;
        while (i < d.length - 8) {
            if ((d[i] & 255) == 0x1D && (d[i + 1] & 255) == 0x76 && (d[i + 2] & 255) == 0x30) {
                int bw = (d[i + 4] & 255) | ((d[i + 5] & 255) << 8);
                int bh = (d[i + 6] & 255) | ((d[i + 7] & 255) << 8);
                int start = i + 8;
                int len = bw * bh;
                if (bw <= 0 || bh <= 0 || start + len > d.length) break;
                if (wb == 0) wb = bw;
                if (bw == wb) {
                    for (int y = 0; y < bh; y++) {
                        rows.add(Arrays.copyOfRange(d, start + y * bw, start + (y + 1) * bw));
                    }
                }
                i = start + len;
            } else {
                i++;
            }
        }
        if (rows.isEmpty()) return null;
        int w = wb * 8, h = rows.size();
        int[] px = new int[w * h];
        Arrays.fill(px, 0xFFFFFFFF);
        for (int y = 0; y < h; y++) {
            byte[] r = rows.get(y);
            for (int x = 0; x < w; x++) {
                if (((r[x >> 3] >> (7 - (x & 7))) & 1) == 1) px[y * w + x] = 0xFF000000;
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /** Printable ASCII of an ESC/POS text stream (fallback when there is no raster image). */
    static String asciiOf(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i++) {
            int c = d[i] & 255;
            if (c == 0x1B) { i += (i + 1 < d.length && (d[i + 1] & 255) == 0x40) ? 1 : 2; continue; }
            if (c == 0x1D) { i += 2; continue; }
            if (c == 10) sb.append('\n');
            else if (c >= 32 && c < 127) sb.append((char) c);
        }
        return sb.toString();
    }

    static Bitmap textToBitmap(String text, int dots) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        p.setTextSize(24f);
        p.setColor(Color.BLACK);
        float cw = p.measureText("M");
        int cols = Math.max(8, (int) ((dots - 8) / cw));
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\n", -1)) {
            if (raw.length() == 0) { lines.add(""); continue; }
            for (int s = 0; s < raw.length(); s += cols) lines.add(raw.substring(s, Math.min(raw.length(), s + cols)));
        }
        float lh = p.getFontSpacing();
        int h = (int) (lines.size() * lh) + 30;
        Bitmap bmp = Bitmap.createBitmap(dots, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        float y = 10 + lh * 0.8f;
        for (String l : lines) {
            c.drawText(l, 4, y, p);
            y += lh;
        }
        return bmp;
    }
}
