package com.suvo.agent.ui;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;

/**
 * Your Friend — Original project by Suvo.
 *
 * Turns terminal output with ANSI escape codes into coloured text: SGR colours (16, 256 and
 * true colour), bold, dim, italic, underline, inverse; other control sequences (cursor moves,
 * window titles) are dropped, and a carriage return rewrites its line like a real terminal does
 * (progress bars of pkg / pip / git end up as their last state).
 */
final class Ansi {

    /** Kali / Termux-like palette: 0–7 normal, 8–15 bright. */
    static final int[] PALETTE = {
            0xFF3B3B3B, 0xFFE5534B, 0xFF57AB5A, 0xFFC69026, 0xFF539BF5, 0xFFB083F0, 0xFF39C5CF, 0xFFD0D0D0,
            0xFF6E6E6E, 0xFFFF6B66, 0xFF6BCB77, 0xFFE2C04C, 0xFF6CB6FF, 0xFFDCBDFB, 0xFF56D4DD, 0xFFFFFFFF};

    private Ansi() {
    }

    /** Appends {@code text} to {@code out}; plain text gets {@code defaultColor}. */
    static void append(SpannableStringBuilder out, String text, int defaultColor) {
        if (text == null || text.isEmpty()) return;
        text = carriageReturns(text);
        int fg = 0, bg = 0;
        boolean bold = false, dim = false, italic = false, under = false, inverse = false;
        int runStart = out.length();
        int n = text.length();
        int i = 0;
        StringBuilder plain = new StringBuilder();
        while (i < n) {
            char c = text.charAt(i);
            if (c == 0x1B && i + 1 < n) {
                char k = text.charAt(i + 1);
                if (k == '[') {
                    int j = i + 2;
                    while (j < n && (text.charAt(j) < 0x40 || text.charAt(j) > 0x7E)) j++;
                    if (j >= n) break;
                    char fin = text.charAt(j);
                    if (fin == 'm') {
                        // Close the run so far with the current style.
                        out.append(plain);
                        plain.setLength(0);
                        style(out, runStart, out.length(), fg, bg, bold, dim, italic, under, inverse, defaultColor);
                        runStart = out.length();
                        String[] ps = text.substring(i + 2, j).split(";", -1);
                        for (int p = 0; p < ps.length; p++) {
                            int v = num(ps[p]);
                            if (v == 0) {
                                fg = bg = 0;
                                bold = dim = italic = under = inverse = false;
                            } else if (v == 1) bold = true;
                            else if (v == 2) dim = true;
                            else if (v == 3) italic = true;
                            else if (v == 4) under = true;
                            else if (v == 7) inverse = true;
                            else if (v == 22) bold = dim = false;
                            else if (v == 23) italic = false;
                            else if (v == 24) under = false;
                            else if (v == 27) inverse = false;
                            else if (v >= 30 && v <= 37) fg = PALETTE[v - 30];
                            else if (v >= 90 && v <= 97) fg = PALETTE[v - 90 + 8];
                            else if (v >= 40 && v <= 47) bg = PALETTE[v - 40];
                            else if (v >= 100 && v <= 107) bg = PALETTE[v - 100 + 8];
                            else if (v == 39) fg = 0;
                            else if (v == 49) bg = 0;
                            else if ((v == 38 || v == 48) && p + 1 < ps.length) {
                                int col = 0;
                                int mode = num(ps[p + 1]);
                                if (mode == 5 && p + 2 < ps.length) {
                                    col = color256(num(ps[p + 2]));
                                    p += 2;
                                } else if (mode == 2 && p + 4 < ps.length) {
                                    col = 0xFF000000 | (clamp(num(ps[p + 2])) << 16) | (clamp(num(ps[p + 3])) << 8) | clamp(num(ps[p + 4]));
                                    p += 4;
                                }
                                if (v == 38) fg = col;
                                else bg = col;
                            }
                        }
                    }
                    i = j + 1;
                    continue;
                }
                if (k == ']') {
                    // OSC (window title…): up to BEL or ESC \
                    int j = i + 2;
                    while (j < n && text.charAt(j) != 0x07 && !(text.charAt(j) == 0x1B && j + 1 < n && text.charAt(j + 1) == '\\')) j++;
                    i = j < n && text.charAt(j) == 0x07 ? j + 1 : Math.min(n, j + 2);
                    continue;
                }
                i += 2; // other two-character escapes
                continue;
            }
            if (c == 0x07 || c == 0x08 && plain.length() == 0) {
                i++;
                continue;
            }
            if (c == 0x08) {
                plain.setLength(plain.length() - 1); // backspace
                i++;
                continue;
            }
            plain.append(c);
            i++;
        }
        out.append(plain);
        style(out, runStart, out.length(), fg, bg, bold, dim, italic, under, inverse, defaultColor);
    }

    /** A line with carriage returns keeps what was written last over what came before. */
    private static String carriageReturns(String text) {
        if (text.indexOf('\r') < 0) return text;
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            int cr = line.lastIndexOf('\r');
            String keep = cr < 0 ? line : line.substring(cr + 1);
            if (keep.isEmpty() && cr >= 0) {
                // "progress\r" at the end of a chunk: keep the progress text.
                String before = line.substring(0, cr);
                int prev = before.lastIndexOf('\r');
                keep = prev < 0 ? before : before.substring(prev + 1);
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(keep);
        }
        return sb.toString();
    }

    private static void style(SpannableStringBuilder out, int s, int e, int fg, int bg, boolean bold, boolean dim, boolean italic,
                              boolean under, boolean inverse, int defaultColor) {
        if (e <= s) return;
        int f = fg == 0 ? defaultColor : fg;
        int b = bg;
        if (inverse) {
            int t = f;
            f = b == 0 ? 0xFF0C0C0C : b;
            b = t;
        }
        if (bold && fg != 0 && fg == PALETTE[indexOf(fg)] && indexOf(fg) < 8) f = PALETTE[indexOf(fg) + 8]; // bold = bright
        if (dim) f = (f & 0x00FFFFFF) | 0x99000000;
        if (f != 0) out.setSpan(new ForegroundColorSpan(f), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (b != 0) out.setSpan(new BackgroundColorSpan(b), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold && italic) out.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        else if (bold) out.setSpan(new StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        else if (italic) out.setSpan(new StyleSpan(Typeface.ITALIC), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (under) out.setSpan(new UnderlineSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static int indexOf(int color) {
        for (int i = 0; i < PALETTE.length; i++) if (PALETTE[i] == color) return i;
        return 15;
    }

    private static int color256(int n) {
        if (n < 0) return 0;
        if (n < 16) return PALETTE[n];
        if (n < 232) {
            int v = n - 16;
            int r = v / 36, g = (v / 6) % 6, b = v % 6;
            return 0xFF000000 | (cube(r) << 16) | (cube(g) << 8) | cube(b);
        }
        if (n < 256) {
            int gray = 8 + (n - 232) * 10;
            return 0xFF000000 | (gray << 16) | (gray << 8) | gray;
        }
        return 0;
    }

    private static int cube(int v) {
        return v == 0 ? 0 : 55 + v * 40;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int num(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
