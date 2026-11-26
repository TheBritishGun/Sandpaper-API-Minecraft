package dev.sandpaper.client.gui;
public final class AeroTheme {
    private AeroTheme() {
    }
    public static final int TEXT = 0xFFEAF6F8;
    public static final int TEXT_DIM = 0xFFB6CDD4;
    public static final int TEXT_OFF = 0xFF63787F;
    public static final int TEXT_HEAD = 0xFF8FD6DE;
    public static final int VALUE = 0xFFCDE7EC;
    public static final int VALUE_CHANGED = 0xFFFFD489;
    public static final int VALUE_INVALID = 0xFFFF9C86;
    public static final int ACCENT = 0xFF46DCC6;
    public static final int ACCENT_DEEP = 0xFF12907F;
    public static final int ACCENT_LIGHT = 0xFFB6FBEF;
    public static final int GLASS_TOP = 0xA61B333C;
    public static final int GLASS_BOTTOM = 0xB80C171D;
    public static final int WELL_TOP = 0x8C050C10;
    public static final int WELL_BOTTOM = 0x6E0A171D;
    private static final int TINT_SPAN = 120;
    private static final int GLOSS_BAND = 22;
    public static final int RULE = 0x2E8FD6DE;
    public static final int RULE_STRONG = 0x478FD6DE;
    public static final int EDGE_LIGHT = 0x59BCF0FF;
    public static final int EDGE_DARK = 0x8C03080B;
    public static final int GLOW = 0x2E9FE8FA;
    public static final int GLOW_INVALID = 0x3DFF6A50;
    public static final int SPECULAR = 0x3DE4FDFF;
    public static final int LIQUID_TOP = 0x7A000000;
    public static final int LIQUID_BOTTOM = 0x8C000000;
    public static final int LIQUID_WELL_TOP = 0x6B000000;
    public static final int LIQUID_WELL_BOTTOM = 0x52000000;
    public static final int CLEAR_TOP = 0x83000000;
    public static final int CLEAR_BOTTOM = 0x83000000;
    public static final int CLEAR_WELL_TOP = 0x5F000000;
    public static final int CLEAR_WELL_BOTTOM = 0x5F000000;
    public static final int CLEAR_EDGE = 0x3DFFFFFF;
    public static final int RIM_LIGHT = 0x8CE8FEFF;
    public static final int RIM_DARK = 0x9E03070A;
    public static final int LENS = 0x38CFF2FF;
    public static final int SHEEN = 0x2ADFF8FF;
    public static final int RADIUS_PANEL = 3;
    public static final int RADIUS_ROW = 2;
    public static final int RADIUS_GLASS = 6;
    public static final int RADIUS_CLEAR = 6;
    private static final int[] R0 = {};
    private static final int[] R1 = {1};
    private static final int[] R2 = {2, 1};
    private static final int[] R3 = {3, 1, 1};
    private static final int[] R4 = {4, 2, 1, 1};
    static int[] insets(int radius) {
        return switch (radius) {
            case 1 -> R1;
            case 2 -> R2;
            case 3 -> R3;
            case 4 -> R4;
            default -> radius <= 0 ? R0 : R4;
        };
    }
    private static final int[] S4 = {4, 1, 1, 1};
    private static final int[] S5 = {5, 2, 1, 1, 1};
    private static final int[] S6 = {6, 2, 1, 1, 1, 1};
    private static final int[] S7 = {7, 2, 1, 1, 1, 1, 1};
    private static final int[] S8 = {8, 3, 2, 1, 1, 1, 1, 1};
    static int[] squircle(int radius) {
        return switch (radius) {
            case 3 -> insets(3);
            case 4 -> S4;
            case 5 -> S5;
            case 6 -> S6;
            case 7 -> S7;
            case 8 -> S8;
            default -> radius <= 2 ? insets(radius) : S8;
        };
    }
    public static float easeOut(float t) {
        float c = clamp01(t);
        float inv = 1.0f - c;
        return 1.0f - inv * inv * inv;
    }
    public static float clamp01(float t) {
        return t < 0.0f ? 0.0f : (t > 1.0f ? 1.0f : t);
    }
    public static int scaleAlpha(int argb, float factor) {
        int a = Math.round((argb >>> 24) * clamp01(factor));
        return (a << 24) | (argb & 0x00FFFFFF);
    }
    public static int mix(int from, int to, float t) {
        float k = clamp01(t);
        if (k <= 0.0f) {
            return from;
        }
        if (k >= 1.0f) {
            return to;
        }
        float fa = lane(from, 24);
        float fr = lane(from, 16);
        float fg = lane(from, 8);
        float fb = lane(from, 0);
        int a = Math.round(fa + (lane(to, 24) - fa) * k);
        int r = Math.round(fr + (lane(to, 16) - fr) * k);
        int g = Math.round(fg + (lane(to, 8) - fg) * k);
        int b = Math.round(fb + (lane(to, 0) - fb) * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
    private static float lane(int argb, int shift) {
        return (argb >>> shift) & 0xFF;
    }
    public static void rounded(AeroSurface s, int x, int y, int w, int h,
            int radius, int top, int bottom) {
        roundedWith(s, insets(radius), x, y, w, h, top, bottom);
    }
    public static void roundedWith(AeroSurface s, int[] table, int x, int y, int w, int h,
            int top, int bottom) {
        if (w <= 0 || h <= 0) {
            return;
        }
        if (top == bottom) {
            flatWith(s, table, x, y, w, h, top);
        } else {
            gradientWith(s, table, x, y, w, h, top, bottom);
        }
    }
    private static void gradientWith(AeroSurface s, int[] table, int x, int y, int w, int h,
            int top, int bottom) {
        int span = Math.max(1, Math.min(h - 1, TINT_SPAN));
        int corner = Math.min(table.length, h / 2);
        float fa = lane(top, 24);
        float da = lane(bottom, 24) - fa;
        float fr = lane(top, 16);
        float dr = lane(bottom, 16) - fr;
        float fg = lane(top, 8);
        float dg = lane(bottom, 8) - fg;
        float fb = lane(top, 0);
        float db = lane(bottom, 0) - fb;
        for (int i = 0; i < corner; i++) {
            int in = Math.min(table[i], w / 2);
            s.fill(x + in, y + i, x + w - in, y + i + 1,
                    mixLanes(top, bottom, fa, da, fr, dr, fg, dg, fb, db, (float) i / span));
            int j = h - 1 - i;
            s.fill(x + in, y + j, x + w - in, y + j + 1,
                    mixLanes(top, bottom, fa, da, fr, dr, fg, dg, fb, db, (float) j / span));
        }
        int lo = y + corner;
        int hi = y + h - corner;
        if (hi > lo) {
            int fade = Math.min(hi, y + span);
            if (fade > lo) {
                s.gradient(x, lo, x + w, fade,
                        mixLanes(top, bottom, fa, da, fr, dr, fg, dg, fb, db,
                                (float) corner / span),
                        mixLanes(top, bottom, fa, da, fr, dr, fg, dg, fb, db,
                                (float) (fade - y) / span));
            }
            if (hi > fade) {
                s.fill(x, fade, x + w, hi, bottom);
            }
        }
    }
    private static int mixLanes(int from, int to, float fa, float da, float fr, float dr,
            float fg, float dg, float fb, float db, float t) {
        float k = clamp01(t);
        if (k <= 0.0f) {
            return from;
        }
        if (k >= 1.0f) {
            return to;
        }
        int a = Math.round(fa + da * k);
        int r = Math.round(fr + dr * k);
        int g = Math.round(fg + dg * k);
        int b = Math.round(fb + db * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
    private static void flatWith(AeroSurface s, int[] table, int x, int y, int w, int h,
            int argb) {
        int corner = Math.min(table.length, h / 2);
        int i = 0;
        while (i < corner) {
            int in = Math.min(table[i], w / 2);
            int run = i + 1;
            while (run < corner && Math.min(table[run], w / 2) == in) {
                run++;
            }
            s.fill(x + in, y + i, x + w - in, y + run, argb);
            s.fill(x + in, y + h - run, x + w - in, y + h - i, argb);
            i = run;
        }
        if (h > corner * 2) {
            s.fill(x, y + corner, x + w, y + h - corner, argb);
        }
    }
    public static void flatRounded(AeroSurface s, int x, int y, int w, int h,
            int radius, int argb) {
        rounded(s, x, y, w, h, radius, argb, argb);
    }
    public static void lipped(AeroSurface s, int x, int y, int w, int h, int radius,
            int light, int dark) {
        lippedWith(s, insets(radius), x, y, w, h, light, dark);
    }
    public static void lippedWith(AeroSurface s, int[] table, int x, int y, int w, int h,
            int light, int dark) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int in = cornerInset(table, 0, 0, w);
        s.fill(x + in, y, x + w - in, y + 1, light);
        s.fill(x + in, y + h - 1, x + w - in, y + h, dark);
    }
    private static int cornerInset(int[] table, int index, int fallback, int w) {
        return Math.min(index < table.length ? table[index] : fallback, w / 2);
    }
    public static void hairlineWith(AeroSurface s, int[] table, int x, int y, int w, int h,
            int argb) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int corner = Math.min(table.length, h / 2);
        int in = corner > 0 ? cornerInset(table, 0, 0, w) : 0;
        s.fill(x + in, y, x + w - in, y + 1, argb);
        if (h > 1) {
            s.fill(x + in, y + h - 1, x + w - in, y + h, argb);
        }
        int outer = in;
        int i = 1;
        while (i < corner) {
            int inset = Math.min(table[i], w / 2);
            int stop = Math.max(inset + 1, outer);
            hairlineRows(s, x, y + i, 1, w, inset, stop, argb);
            hairlineRows(s, x, y + h - 1 - i, 1, w, inset, stop, argb);
            outer = inset;
            int run = i + 1;
            while (run < corner && Math.min(table[run], w / 2) == inset) {
                run++;
            }
            if (run > i + 1) {
                hairlineRows(s, x, y + i + 1, run - i - 1, w, inset, inset + 1, argb);
                hairlineRows(s, x, y + h - run, run - i - 1, w, inset, inset + 1, argb);
            }
            i = run;
        }
        int first = y + Math.max(corner, 1);
        int last = y + h - Math.max(corner, 1);
        if (last <= first) {
            return;
        }
        int stop = Math.max(1, outer);
        if (stop > 1) {
            hairlineRows(s, x, first, 1, w, 0, stop, argb);
            first++;
            if (last - 1 >= first) {
                hairlineRows(s, x, last - 1, 1, w, 0, stop, argb);
                last--;
            }
        }
        if (last <= first) {
            return;
        }
        if (w <= 2) {
            s.fill(x, first, x + w, last, argb);
            return;
        }
        s.fill(x, first, x + 1, last, argb);
        s.fill(x + w - 1, first, x + w, last, argb);
    }
    private static void hairlineRows(AeroSurface s, int x, int y, int rows, int w, int inset,
            int stop, int argb) {
        if (w - stop <= stop) {
            s.fill(x + inset, y, x + w - inset, y + rows, argb);
            return;
        }
        s.fill(x + inset, y, x + stop, y + rows, argb);
        s.fill(x + w - stop, y, x + w - inset, y + rows, argb);
    }
    public static void gloss(AeroSurface s, int x, int y, int w, int h, float strength) {
        if (w <= 2 || h <= 4) {
            return;
        }
        int specular = colours().specular;
        int third = y + Math.max(2, Math.min(h / 3, GLOSS_BAND));
        int mid = y + Math.max(3, Math.min(h / 2, GLOSS_BAND * 3 / 2));
        int peak = scaleAlpha(specular, strength);
        int fade = scaleAlpha(specular, strength * 0.42f);
        s.gradient(x + 1, y + 1, x + w - 1, third, peak, fade);
        s.gradient(x + 1, third, x + w - 1, mid, fade, specular & 0x00FFFFFF);
    }
    public static void innerGlow(AeroSurface s, int x, int y, int w, int h, float strength) {
        innerGlow(s, x, y, w, h, strength, colours().glow);
    }
    public static void innerGlow(AeroSurface s, int x, int y, int w, int h,
            float strength, int colour) {
        for (int i = 0; i < 3; i++) {
            int a = scaleAlpha(colour, strength * innerGlowK(i));
            if (!ring(s, x, y, w, h, i, a)) {
                return;
            }
        }
    }
    private static float innerGlowK(int i) {
        return 1.0f - i * 0.34f;
    }
    private static void innerGlow(AeroSurface s, int x, int y, int w, int h, int a0, int a1,
            int a2) {
        if (!ring(s, x, y, w, h, 0, a0)) {
            return;
        }
        if (!ring(s, x, y, w, h, 1, a1)) {
            return;
        }
        ring(s, x, y, w, h, 2, a2);
    }
    private static boolean ring(AeroSurface s, int x, int y, int w, int h, int i,
            int alpha) {
        int gx = x + i;
        int gy = y + i;
        int gw = w - i * 2;
        int gh = h - i * 2;
        if (gw <= 2 || gh <= 2) {
            return false;
        }
        s.fill(gx, gy, gx + gw, gy + 1, alpha);
        s.fill(gx, gy + gh - 1, gx + gw, gy + gh, alpha);
        s.fill(gx, gy + 1, gx + 1, gy + gh - 1, alpha);
        s.fill(gx + gw - 1, gy + 1, gx + gw, gy + gh - 1, alpha);
        return true;
    }
    private static final int RIM_STEPS = 5;
    private static final int RIM_REACH = 55;
    private static final float RIM_INNER = 0.38f;
    private static final float RIM_RUN = 0.9f;
    private static final int LENS_BAND = 5;
    private static final int SHEEN_BAND = 8;
    public static void specularRim(AeroSurface s, int x, int y, int w, int h,
            int radius, float strength) {
        if (w <= 4 || h <= 4) {
            return;
        }
        rim(s, x, y, w, h, squircle(radius), strength, RIM_LIGHT, true);
    }
    private static void specularRim(AeroSurface s, int x, int y, int w, int h, int[] table,
            int a0, int a1, int[] run) {
        if (w <= 4 || h <= 4) {
            return;
        }
        rim(s, x, y, w, h, table, a0, a1, run, true);
    }
    public static void rimShadow(AeroSurface s, int x, int y, int w, int h,
            int radius, float strength) {
        if (w <= 4 || h <= 4) {
            return;
        }
        rim(s, x, y, w, h, squircle(radius), strength, RIM_DARK, false);
    }
    private static void rimShadow(AeroSurface s, int x, int y, int w, int h, int[] table,
            int a0, int a1, int[] run) {
        if (w <= 4 || h <= 4) {
            return;
        }
        rim(s, x, y, w, h, table, a0, a1, run, false);
    }
    private static void rim(AeroSurface s, int x, int y, int w, int h, int[] table,
            float strength, int colour, boolean downward) {
        int a0 = scaleAlpha(colour, strength);
        int a1 = scaleAlpha(colour, strength * RIM_INNER);
        rimRows(s, x, y, w, h, table, a0, a1, downward);
        rimRun(s, downward ? x : x + w - 1, y + 2, h, strength, colour, downward);
    }
    private static void rim(AeroSurface s, int x, int y, int w, int h, int[] table, int a0,
            int a1, int[] run, boolean downward) {
        rimRows(s, x, y, w, h, table, a0, a1, downward);
        rimRun(s, downward ? x : x + w - 1, y + 2, h, run, downward);
    }
    private static void rimRows(AeroSurface s, int x, int y, int w, int h, int[] table, int a0,
            int a1, boolean downward) {
        int in = cornerInset(table, 0, 0, w);
        int in1 = cornerInset(table, 1, in, w);
        int row0 = downward ? y : y + h - 1;
        int row1 = downward ? y + 1 : y + h - 2;
        s.fill(x + in, row0, x + w - in, row0 + 1, a0);
        s.fill(x + in1, row1, x + w - in1, row1 + 1, a1);
    }
    private static void rimRun(AeroSurface s, int x, int top, int h, float strength,
            int colour, boolean downward) {
        int reach = rimRunReach(h);
        for (int i = 0; i < RIM_STEPS; i++) {
            long span = rimRunSpan(reach, top, h, i, downward);
            if (span == 0L) {
                continue;
            }
            float k = rimRunK(i);
            int alpha = scaleAlpha(colour, strength * k * k * RIM_RUN);
            int y0 = (int) (span >> 32);
            s.fill(x, y0, x + 1, y0 + (int) span, alpha);
        }
    }
    private static float rimRunK(int i) {
        return 1.0f - (float) i / RIM_STEPS;
    }
    private static int[] rimRunAlphas(int colour) {
        int[] alphas = new int[RIM_STEPS];
        for (int i = 0; i < RIM_STEPS; i++) {
            float k = rimRunK(i);
            alphas[i] = scaleAlpha(colour, k * k * RIM_RUN);
        }
        return alphas;
    }
    private static void rimRun(AeroSurface s, int x, int top, int h, int[] alphas,
            boolean downward) {
        int reach = rimRunReach(h);
        for (int i = 0; i < RIM_STEPS; i++) {
            long span = rimRunSpan(reach, top, h, i, downward);
            if (span == 0L) {
                continue;
            }
            int y0 = (int) (span >> 32);
            s.fill(x, y0, x + 1, y0 + (int) span, alphas[i]);
        }
    }
    private static long rimRunSpan(int reach, int top, int h, int i, boolean downward) {
        int a = reach * i / RIM_STEPS;
        int b = reach * (i + 1) / RIM_STEPS;
        if (b <= a) {
            return 0L;
        }
        int y0 = downward ? top + a : top + h - 4 - b;
        return ((long) y0 << 32) | (b - a);
    }
    private static int rimRunReach(int h) {
        return Math.max(2, Math.min(h - 4, h * RIM_REACH / 100));
    }
    public static void edgeLens(AeroSurface s, int x, int y, int w, int h, float strength) {
        for (int i = 1; i <= LENS_BAND; i++) {
            int a = edgeLensAlpha(strength, i);
            if (!ring(s, x, y, w, h, i, a)) {
                return;
            }
        }
    }
    private static float edgeLensK(int i) {
        return 1.0f - (float) (i - 1) / LENS_BAND;
    }
    private static int edgeLensAlpha(float strength, int i) {
        float k = edgeLensK(i);
        return scaleAlpha(LENS, strength * k * k);
    }
    private static int innermostLensAlpha(float strength, int i) {
        if (LENS_BAND > i) {
            throw new IllegalStateException("LENS_BAND " + LENS_BAND
                    + " exceeds " + i + " rings.");
        }
        return edgeLensAlpha(strength, i);
    }
    private static void edgeLens(AeroSurface s, int x, int y, int w, int h, int a1, int a2,
            int a3, int a4, int a5) {
        if (LENS_BAND < 1 || !ring(s, x, y, w, h, 1, a1)) {
            return;
        }
        if (LENS_BAND < 2 || !ring(s, x, y, w, h, 2, a2)) {
            return;
        }
        if (LENS_BAND < 3 || !ring(s, x, y, w, h, 3, a3)) {
            return;
        }
        if (LENS_BAND < 4 || !ring(s, x, y, w, h, 4, a4)) {
            return;
        }
        if (LENS_BAND >= 5) {
            ring(s, x, y, w, h, 5, a5);
        }
    }
    public static void sheen(AeroSurface s, int x, int y, int w, int h, float strength) {
        if (w <= 2 || h <= 4) {
            return;
        }
        int clear = SHEEN & 0x00FFFFFF;
        int band = y + Math.max(2, Math.min(h / 5, SHEEN_BAND));
        s.gradient(x + 1, y + 1, x + w - 1, band, scaleAlpha(SHEEN, strength), clear);
        int foot = y + h - 1 - Math.max(1, Math.min(h / 8, SHEEN_BAND / 2));
        s.gradient(x + 1, foot, x + w - 1, y + h - 1, clear,
                scaleAlpha(SHEEN, strength * 0.30f));
    }
    private static final int PANEL_GLOW_0 = scaleAlpha(GLOW, 1.0f * innerGlowK(0));
    private static final int PANEL_GLOW_1 = scaleAlpha(GLOW, 1.0f * innerGlowK(1));
    private static final int PANEL_GLOW_2 = scaleAlpha(GLOW, 1.0f * innerGlowK(2));
    private static final float WELL_LENS = 0.45f;
    private static final int GLASS_PANEL_LENS_1 = edgeLensAlpha(1.0f, 1);
    private static final int GLASS_PANEL_LENS_2 = edgeLensAlpha(1.0f, 2);
    private static final int GLASS_PANEL_LENS_3 = edgeLensAlpha(1.0f, 3);
    private static final int GLASS_PANEL_LENS_4 = edgeLensAlpha(1.0f, 4);
    private static final int GLASS_PANEL_LENS_5 = innermostLensAlpha(1.0f, 5);
    private static final int GLASS_WELL_LENS_1 = edgeLensAlpha(WELL_LENS, 1);
    private static final int GLASS_WELL_LENS_2 = edgeLensAlpha(WELL_LENS, 2);
    private static final int GLASS_WELL_LENS_3 = edgeLensAlpha(WELL_LENS, 3);
    private static final int GLASS_WELL_LENS_4 = edgeLensAlpha(WELL_LENS, 4);
    private static final int GLASS_WELL_LENS_5 = innermostLensAlpha(WELL_LENS, 5);
    private static final int GLASS_RIM_LIGHT_0 = scaleAlpha(RIM_LIGHT, 1.0f);
    private static final int GLASS_RIM_LIGHT_1 = scaleAlpha(RIM_LIGHT, RIM_INNER);
    private static final int[] GLASS_RIM_LIGHT_RUN = rimRunAlphas(RIM_LIGHT);
    private static final int GLASS_RIM_DARK_0 = scaleAlpha(RIM_DARK, 1.0f);
    private static final int GLASS_RIM_DARK_1 = scaleAlpha(RIM_DARK, RIM_INNER);
    private static final int[] GLASS_RIM_DARK_RUN = rimRunAlphas(RIM_DARK);
    private static final int[] GLASS_PANEL_RIM_TABLE = squircle(RADIUS_GLASS);
    private static final int[] CLEAR_PANEL_TABLE = squircle(RADIUS_CLEAR);
    private static final int GLASS_WELL_RIM_LIGHT_45 = scaleAlpha(RIM_LIGHT, 0.45f);

    public static final int SHIPPED_COLOUR = -1;
    private static final int SHIPPED_ROW_HOVER = 0x59AEEFFF;
    private static final int ROW_HOVER_BASE_RGB = 0xAEEFFF;
    private static final double LIGHT_LIGHTNESS_THRESHOLD = 0.75;
    private static final double LIGHT_ACCENT_FLOOR = 0.35;
    private static final int STRONGER_PANEL_GLASS_TOP_A = 0xD8;
    private static final int STRONGER_PANEL_GLASS_BOTTOM_A = 0xE0;
    private static final int STRONGER_PANEL_WELL_TOP_A = 0xC8;
    private static final int STRONGER_PANEL_WELL_BOTTOM_A = 0xB4;

    public static final class Palette {
        public final int text;
        public final int textDim;
        public final int textOff;
        public final int textHead;
        public final int value;
        public final int valueChanged;
        public final int valueInvalid;
        public final int accent;
        public final int accentDeep;
        public final int accentLight;
        public final int glassTop;
        public final int glassBottom;
        public final int wellTop;
        public final int wellBottom;
        public final int buttonTop;
        public final int buttonBottom;
        public final int rule;
        public final int ruleStrong;
        public final int edgeLight;
        public final int edgeDark;
        public final int glow;
        public final int glowInvalid;
        public final int specular;
        public final int rowHover;
        public final int panelGlow0;
        public final int panelGlow1;
        public final int panelGlow2;

        Palette(int text, int textDim, int textOff, int textHead, int value, int valueChanged,
                int valueInvalid, int accent, int accentDeep, int accentLight, int glassTop,
                int glassBottom, int wellTop, int wellBottom, int buttonTop, int buttonBottom,
                int rule, int ruleStrong, int edgeLight, int edgeDark, int glow,
                int glowInvalid, int specular, int rowHover) {
            this.text = text;
            this.textDim = textDim;
            this.textOff = textOff;
            this.textHead = textHead;
            this.value = value;
            this.valueChanged = valueChanged;
            this.valueInvalid = valueInvalid;
            this.accent = accent;
            this.accentDeep = accentDeep;
            this.accentLight = accentLight;
            this.glassTop = glassTop;
            this.glassBottom = glassBottom;
            this.wellTop = wellTop;
            this.wellBottom = wellBottom;
            this.buttonTop = buttonTop;
            this.buttonBottom = buttonBottom;
            this.rule = rule;
            this.ruleStrong = ruleStrong;
            this.edgeLight = edgeLight;
            this.edgeDark = edgeDark;
            this.glow = glow;
            this.glowInvalid = glowInvalid;
            this.specular = specular;
            this.rowHover = rowHover;
            this.panelGlow0 = scaleAlpha(glow, 1.0f * innerGlowK(0));
            this.panelGlow1 = scaleAlpha(glow, 1.0f * innerGlowK(1));
            this.panelGlow2 = scaleAlpha(glow, 1.0f * innerGlowK(2));
        }

        public static final Palette SHIPPED = new Palette(TEXT, TEXT_DIM, TEXT_OFF, TEXT_HEAD,
                VALUE, VALUE_CHANGED, VALUE_INVALID, ACCENT, ACCENT_DEEP, ACCENT_LIGHT,
                GLASS_TOP, GLASS_BOTTOM, WELL_TOP, WELL_BOTTOM, GLASS_TOP, GLASS_BOTTOM, RULE,
                RULE_STRONG, EDGE_LIGHT, EDGE_DARK, GLOW, GLOW_INVALID, SPECULAR,
                SHIPPED_ROW_HOVER);
    }

    private static volatile Palette aeroPalette = Palette.SHIPPED;
    private static volatile Palette filmPalette = Palette.SHIPPED;

    public static void useColours(int themeRgb, int accentRgb) {
        int theme = themeRgb == SHIPPED_COLOUR ? SHIPPED_COLOUR : rgbOf(themeRgb);
        int accent = accentRgb == SHIPPED_COLOUR ? SHIPPED_COLOUR : rgbOf(accentRgb);
        aeroPalette = aeroPaletteFor(theme, accent);
        filmPalette = darkPalette(SHIPPED_COLOUR, accent);
    }

    public static Palette colours() {
        return aeroPalette;
    }

    public static Palette colours(Treatment t) {
        return t == Treatment.LIQUID || t == Treatment.CLEAR ? filmPalette : aeroPalette;
    }

    private static Palette aeroPaletteFor(int theme, int accent) {
        if (theme == SHIPPED_COLOUR) {
            return darkPalette(SHIPPED_COLOUR, accent);
        }
        double lightness = lightnessOf(theme);
        return lightness < LIGHT_LIGHTNESS_THRESHOLD ? darkPalette(theme, accent)
                : lightPalette(theme, accent);
    }

    private static Palette darkPalette(int theme, int accent) {
        int glassTop;
        int glassBottom;
        int wellTop;
        int wellBottom;
        int buttonTop;
        int buttonBottom;
        if (theme == SHIPPED_COLOUR) {
            glassTop = GLASS_TOP;
            glassBottom = GLASS_BOTTOM;
            wellTop = WELL_TOP;
            wellBottom = WELL_BOTTOM;
            buttonTop = GLASS_TOP;
            buttonBottom = GLASS_BOTTOM;
        } else {
            int cr = redOf(theme);
            int cg = greenOf(theme);
            int cb = blueOf(theme);
            glassTop = formulaTCArgb(cr, cg, cb, GLASS_TOP);
            glassBottom = formulaTCArgb(cr, cg, cb, GLASS_BOTTOM);
            wellTop = formulaTCArgb(cr, cg, cb, WELL_TOP);
            wellBottom = formulaTCArgb(cr, cg, cb, WELL_BOTTOM);
            buttonTop = formulaTAArgb(cr, cg, cb, GLASS_TOP);
            buttonBottom = formulaTAArgb(cr, cg, cb, GLASS_BOTTOM);
        }
        int accentOut;
        int accentDeep;
        int accentLight;
        int textHead;
        int rule;
        int ruleStrong;
        int edgeLight;
        int glow;
        if (accent == SHIPPED_COLOUR) {
            accentOut = ACCENT;
            accentDeep = ACCENT_DEEP;
            accentLight = ACCENT_LIGHT;
            textHead = TEXT_HEAD;
            rule = RULE;
            ruleStrong = RULE_STRONG;
            edgeLight = EDGE_LIGHT;
            glow = GLOW;
        } else {
            int cr = redOf(accent);
            int cg = greenOf(accent);
            int cb = blueOf(accent);
            accentOut = argb(0xFF, cr, cg, cb);
            accentDeep = formulaTAArgb(cr, cg, cb, ACCENT_DEEP);
            accentLight = formulaTAArgb(cr, cg, cb, ACCENT_LIGHT);
            textHead = formulaTAArgb(cr, cg, cb, TEXT_HEAD);
            rule = formulaTAArgb(cr, cg, cb, RULE);
            ruleStrong = argb(alphaOf(RULE_STRONG), rgbOf(rule));
            edgeLight = formulaTAArgb(cr, cg, cb, EDGE_LIGHT);
            glow = formulaTAArgb(cr, cg, cb, GLOW);
        }
        return new Palette(TEXT, TEXT_DIM, TEXT_OFF, textHead, VALUE, VALUE_CHANGED,
                VALUE_INVALID, accentOut, accentDeep, accentLight, glassTop, glassBottom,
                wellTop, wellBottom, buttonTop, buttonBottom, rule, ruleStrong, edgeLight,
                EDGE_DARK, glow, GLOW_INVALID, SPECULAR, SHIPPED_ROW_HOVER);
    }

    private static Palette lightPalette(int theme, int accent) {
        double th = hueOf(theme);
        double ts = satOf(theme);
        int accentRgb = accent == SHIPPED_COLOUR ? rgbOf(ACCENT) : accent;
        double ah = hueOf(accentRgb);
        double ac = Math.max(chromaOf(accentRgb), LIGHT_ACCENT_FLOOR);

        double glassTopLight = 1.0 - lightnessOf(GLASS_BOTTOM);
        double glassBottomLight = 1.0 - lightnessOf(GLASS_TOP);
        double wellTopLight = 1.0 - lightnessOf(WELL_BOTTOM);
        double wellBottomLight = 1.0 - lightnessOf(WELL_TOP);

        int glassTop = argb(STRONGER_PANEL_GLASS_TOP_A,
                shade(th, glassTopLight, ts * room(glassTopLight) * 0.5));
        int glassBottom = argb(STRONGER_PANEL_GLASS_BOTTOM_A,
                shade(th, glassBottomLight, ts * room(glassBottomLight) * 0.5));
        int wellTop = argb(STRONGER_PANEL_WELL_TOP_A,
                shade(th, wellTopLight, ts * room(wellTopLight) * 0.5));
        int wellBottom = argb(STRONGER_PANEL_WELL_BOTTOM_A,
                shade(th, wellBottomLight, ts * room(wellBottomLight) * 0.5));
        int buttonTop = argb(STRONGER_PANEL_GLASS_TOP_A, hls01ToRgb255(th, glassTopLight, ts));
        int buttonBottom = argb(STRONGER_PANEL_GLASS_BOTTOM_A,
                hls01ToRgb255(th, glassBottomLight, ts));

        int text = mirroredShade(TEXT, th, Math.min(chromaOf(TEXT), chromaOf(theme)));
        int textDim = mirroredShade(TEXT_DIM, th, Math.min(chromaOf(TEXT_DIM), chromaOf(theme)));
        int textOff = mirroredShade(TEXT_OFF, th, Math.min(chromaOf(TEXT_OFF), chromaOf(theme)));
        int value = mirroredShade(VALUE, th, Math.min(chromaOf(VALUE), chromaOf(theme)));

        int valueChanged = ownHueMirroredShade(VALUE_CHANGED);
        int valueInvalid = ownHueMirroredShade(VALUE_INVALID);

        int textHead = mirroredShade(TEXT_HEAD, ah, ac);
        int rule = mirroredShade(RULE, ah, ac);
        int ruleStrong = argb(alphaOf(RULE_STRONG), rgbOf(rule));
        int glow = mirroredShade(GLOW, ah, ac);

        int accentOut = mirroredShade(ACCENT, ah, ac);
        int accentDeep = mirroredShade(ACCENT_DEEP, ah, ac);
        int accentLight = mirroredShade(ACCENT_LIGHT, ah, ac);

        int edgeLight = argb(alphaOf(EDGE_LIGHT), shade(ah, lightnessOf(EDGE_LIGHT),
                chromaOf(accentRgb)));

        int rowHover = argb(0x59, shade(ah, 1.0 - lightnessOf(ROW_HOVER_BASE_RGB), ac));

        return new Palette(text, textDim, textOff, textHead, value, valueChanged, valueInvalid,
                accentOut, accentDeep, accentLight, glassTop, glassBottom, wellTop, wellBottom,
                buttonTop, buttonBottom, rule, ruleStrong, edgeLight, EDGE_DARK, glow,
                GLOW_INVALID, SPECULAR, rowHover);
    }

    private static int mirroredShade(int shipped, double hue, double colourAmount) {
        return argb(alphaOf(shipped), shade(hue, 1.0 - lightnessOf(shipped), colourAmount));
    }

    private static int ownHueMirroredShade(int shipped) {
        return argb(alphaOf(shipped),
                shade(hueOf(shipped), 1.0 - lightnessOf(shipped), chromaOf(shipped)));
    }

    private static int formulaTAArgb(int cr, int cg, int cb, int shippedArgb) {
        int[] out = hueSwap(cr, cg, cb, redOf(shippedArgb), greenOf(shippedArgb),
                blueOf(shippedArgb));
        return argb(alphaOf(shippedArgb), out);
    }

    private static int formulaTCArgb(int cr, int cg, int cb, int shippedArgb) {
        int taArgb = formulaTAArgb(cr, cg, cb, shippedArgb);
        int rr = clampByte(roundHalfUp((redOf(shippedArgb) + redOf(taArgb)) / 2.0));
        int gg = clampByte(roundHalfUp((greenOf(shippedArgb) + greenOf(taArgb)) / 2.0));
        int bb = clampByte(roundHalfUp((blueOf(shippedArgb) + blueOf(taArgb)) / 2.0));
        return argb(alphaOf(shippedArgb), rr, gg, bb);
    }

    private static int[] hueSwap(int sr, int sg, int sb, int tr, int tg, int tb) {
        double[] shls = rgbToHls01(sr, sg, sb);
        double[] thls = rgbToHls01(tr, tg, tb);
        double chroma = chroma01(sr, sg, sb);
        double rm = room(thls[1]);
        double s = rm <= 0.0 ? shls[2] : Math.min(shls[2], chroma / rm);
        return hls01ToRgb255(shls[0], thls[1], s);
    }

    private static double room(double light) {
        return 1.0 - Math.abs(2.0 * light - 1.0);
    }

    private static int[] shade(double hue, double light, double colourAmount) {
        double rm = room(light);
        double s = rm <= 0.0 ? 0.0 : Math.min(1.0, colourAmount / rm);
        return hls01ToRgb255(hue, light, s);
    }

    private static double lightnessOf(int argbValue) {
        return rgbToHls01(redOf(argbValue), greenOf(argbValue), blueOf(argbValue))[1];
    }

    private static double hueOf(int argbValue) {
        return rgbToHls01(redOf(argbValue), greenOf(argbValue), blueOf(argbValue))[0];
    }

    private static double satOf(int argbValue) {
        return rgbToHls01(redOf(argbValue), greenOf(argbValue), blueOf(argbValue))[2];
    }

    private static double chromaOf(int argbValue) {
        return chroma01(redOf(argbValue), greenOf(argbValue), blueOf(argbValue));
    }

    private static double chroma01(int r255, int g255, int b255) {
        double r = r255 / 255.0;
        double g = g255 / 255.0;
        double b = b255 / 255.0;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }

    private static int alphaOf(int argbValue) {
        return (argbValue >>> 24) & 0xFF;
    }

    private static int redOf(int argbValue) {
        return (argbValue >>> 16) & 0xFF;
    }

    private static int greenOf(int argbValue) {
        return (argbValue >>> 8) & 0xFF;
    }

    private static int blueOf(int argbValue) {
        return argbValue & 0xFF;
    }

    private static int rgbOf(int argbValue) {
        return argbValue & 0xFFFFFF;
    }

    private static int argb(int alpha, int r255, int g255, int b255) {
        return (alpha << 24) | (r255 << 16) | (g255 << 8) | b255;
    }

    private static int argb(int alpha, int[] rgb255) {
        return argb(alpha, rgb255[0], rgb255[1], rgb255[2]);
    }

    private static int argb(int alpha, int rgb24) {
        return (alpha << 24) | (rgb24 & 0xFFFFFF);
    }

    private static final double ONE_THIRD = 1.0 / 3.0;
    private static final double ONE_SIXTH = 1.0 / 6.0;
    private static final double TWO_THIRD = 2.0 / 3.0;

    private static double mod1(double x) {
        return x - Math.floor(x);
    }

    private static int roundHalfUp(double x) {
        return (int) Math.floor(x + 0.5);
    }

    private static int clampByte(int x) {
        return Math.max(0, Math.min(255, x));
    }

    private static double[] rgbToHls01(int r255, int g255, int b255) {
        return rgbToHls(r255 / 255.0, g255 / 255.0, b255 / 255.0);
    }

    private static int[] hls01ToRgb255(double h, double l, double s) {
        double[] rgb = hlsToRgb(h, l, s);
        return new int[] {
            clampByte(roundHalfUp(rgb[0] * 255.0)),
            clampByte(roundHalfUp(rgb[1] * 255.0)),
            clampByte(roundHalfUp(rgb[2] * 255.0)),
        };
    }

    private static double[] rgbToHls(double r, double g, double b) {
        double maxc = Math.max(r, Math.max(g, b));
        double minc = Math.min(r, Math.min(g, b));
        double sumc = maxc + minc;
        double rangec = maxc - minc;
        double l = sumc / 2.0;
        if (minc == maxc) {
            return new double[] {0.0, l, 0.0};
        }
        double s = l <= 0.5 ? rangec / sumc : rangec / (2.0 - maxc - minc);
        double rc = (maxc - r) / rangec;
        double gc = (maxc - g) / rangec;
        double bc = (maxc - b) / rangec;
        double h;
        if (r == maxc) {
            h = bc - gc;
        } else if (g == maxc) {
            h = 2.0 + rc - bc;
        } else {
            h = 4.0 + gc - rc;
        }
        h = mod1(h / 6.0);
        return new double[] {h, l, s};
    }

    private static double[] hlsToRgb(double h, double l, double s) {
        if (s == 0.0) {
            return new double[] {l, l, l};
        }
        double m2 = l <= 0.5 ? l * (1.0 + s) : l + s - (l * s);
        double m1 = 2.0 * l - m2;
        return new double[] {
            hlsLaneV(m1, m2, h + ONE_THIRD),
            hlsLaneV(m1, m2, h),
            hlsLaneV(m1, m2, h - ONE_THIRD),
        };
    }

    private static double hlsLaneV(double m1, double m2, double hue) {
        double hh = mod1(hue);
        if (hh < ONE_SIXTH) {
            return m1 + (m2 - m1) * hh * 6.0;
        }
        if (hh < 0.5) {
            return m2;
        }
        if (hh < TWO_THIRD) {
            return m1 + (m2 - m1) * (TWO_THIRD - hh) * 6.0;
        }
        return m1;
    }

    public static void panel(AeroSurface s, int x, int y, int w, int h) {
        Palette p = colours();
        rounded(s, x, y, w, h, RADIUS_PANEL, p.glassTop, p.glassBottom);
        innerGlow(s, x, y, w, h, p.panelGlow0, p.panelGlow1, p.panelGlow2);
        lipped(s, x, y, w, h, RADIUS_PANEL, p.edgeLight, p.edgeDark);
        gloss(s, x, y, w, h, 1.0f);
    }
    public static void well(AeroSurface s, int x, int y, int w, int h) {
        Palette p = colours();
        rounded(s, x, y, w, h, RADIUS_ROW, p.wellTop, p.wellBottom);
        lipped(s, x, y, w, h, RADIUS_ROW, p.edgeDark, scaleAlpha(p.edgeLight, 0.5f));
    }
    public enum Treatment {
        AERO,
        LIQUID,
        CLEAR
    }
    public static boolean needsBlurredBackdrop(Treatment treatment) {
        return treatment == Treatment.LIQUID || treatment == Treatment.CLEAR;
    }
    public static void glassPanel(AeroSurface s, int x, int y, int w, int h) {
        roundedWith(s, GLASS_PANEL_RIM_TABLE, x, y, w, h, LIQUID_TOP, LIQUID_BOTTOM);
        edgeLens(s, x, y, w, h, GLASS_PANEL_LENS_1, GLASS_PANEL_LENS_2, GLASS_PANEL_LENS_3,
                GLASS_PANEL_LENS_4, GLASS_PANEL_LENS_5);
        sheen(s, x, y, w, h, 1.0f);
        specularRim(s, x, y, w, h, GLASS_PANEL_RIM_TABLE, GLASS_RIM_LIGHT_0, GLASS_RIM_LIGHT_1,
                GLASS_RIM_LIGHT_RUN);
        rimShadow(s, x, y, w, h, GLASS_PANEL_RIM_TABLE, GLASS_RIM_DARK_0, GLASS_RIM_DARK_1,
                GLASS_RIM_DARK_RUN);
    }
    public static void glassPanel(AeroSurface s, int x, int y, int w, int h, float strength) {
        roundedWith(s, GLASS_PANEL_RIM_TABLE, x, y, w, h, LIQUID_TOP, LIQUID_BOTTOM);
        edgeLens(s, x, y, w, h, strength);
        sheen(s, x, y, w, h, strength);
        specularRim(s, x, y, w, h, RADIUS_GLASS, strength);
        rimShadow(s, x, y, w, h, RADIUS_GLASS, strength);
    }
    public static void glassWell(AeroSurface s, int x, int y, int w, int h) {
        int radius = Math.min(RADIUS_GLASS, Math.max(1, h / 3));
        int[] table = squircle(radius);
        roundedWith(s, table, x, y, w, h, LIQUID_WELL_TOP, LIQUID_WELL_BOTTOM);
        edgeLens(s, x, y, w, h, GLASS_WELL_LENS_1, GLASS_WELL_LENS_2, GLASS_WELL_LENS_3,
                GLASS_WELL_LENS_4, GLASS_WELL_LENS_5);
        lippedWith(s, table, x, y, w, h, RIM_DARK, GLASS_WELL_RIM_LIGHT_45);
    }
    public static void glassWell(AeroSurface s, int x, int y, int w, int h, float strength) {
        int radius = Math.min(RADIUS_GLASS, Math.max(1, h / 3));
        int[] table = squircle(radius);
        roundedWith(s, table, x, y, w, h, LIQUID_WELL_TOP, LIQUID_WELL_BOTTOM);
        edgeLens(s, x, y, w, h, strength * WELL_LENS);
        lippedWith(s, table, x, y, w, h, RIM_DARK, GLASS_WELL_RIM_LIGHT_45);
    }
    public static void clearPanel(AeroSurface s, int x, int y, int w, int h) {
        roundedWith(s, CLEAR_PANEL_TABLE, x, y, w, h, CLEAR_TOP, CLEAR_BOTTOM);
        hairlineWith(s, CLEAR_PANEL_TABLE, x, y, w, h, CLEAR_EDGE);
    }
    public static void clearPanel(AeroSurface s, int x, int y, int w, int h, float strength) {
        roundedWith(s, CLEAR_PANEL_TABLE, x, y, w, h, CLEAR_TOP, CLEAR_BOTTOM);
        hairlineWith(s, CLEAR_PANEL_TABLE, x, y, w, h, scaleAlpha(CLEAR_EDGE, strength));
    }
    public static void clearWell(AeroSurface s, int x, int y, int w, int h) {
        int[] table = squircle(clearWellRadius(h));
        roundedWith(s, table, x, y, w, h, CLEAR_WELL_TOP, CLEAR_WELL_BOTTOM);
        hairlineWith(s, table, x, y, w, h, CLEAR_EDGE);
    }
    public static void clearWell(AeroSurface s, int x, int y, int w, int h, float strength) {
        int[] table = squircle(clearWellRadius(h));
        roundedWith(s, table, x, y, w, h, CLEAR_WELL_TOP, CLEAR_WELL_BOTTOM);
        hairlineWith(s, table, x, y, w, h, scaleAlpha(CLEAR_EDGE, strength));
    }
    private static int clearWellRadius(int h) {
        return Math.min(RADIUS_CLEAR, Math.max(1, h / 3));
    }
    public static void panel(AeroSurface s, Treatment treatment, int x, int y, int w, int h) {
        treatmentDispatch(s, treatment, x, y, w, h, 1.0f, false);
    }
    public static void panel(AeroSurface s, Treatment treatment, int x, int y, int w, int h,
            float strength) {
        treatmentDispatch(s, treatment, x, y, w, h, strength, false);
    }
    public static void well(AeroSurface s, Treatment treatment, int x, int y, int w, int h) {
        treatmentDispatch(s, treatment, x, y, w, h, 1.0f, true);
    }
    public static void well(AeroSurface s, Treatment treatment, int x, int y, int w, int h,
            float strength) {
        treatmentDispatch(s, treatment, x, y, w, h, strength, true);
    }
    private static void treatmentDispatch(AeroSurface s, Treatment treatment, int x, int y,
            int w, int h, float strength, boolean well) {
        if (treatment == Treatment.LIQUID) {
            if (strength == 1.0f) {
                if (well) {
                    glassWell(s, x, y, w, h);
                } else {
                    glassPanel(s, x, y, w, h);
                }
                return;
            }
            if (well) {
                glassWell(s, x, y, w, h, strength);
            } else {
                glassPanel(s, x, y, w, h, strength);
            }
            return;
        }
        if (treatment == Treatment.CLEAR) {
            if (strength == 1.0f) {
                if (well) {
                    clearWell(s, x, y, w, h);
                } else {
                    clearPanel(s, x, y, w, h);
                }
                return;
            }
            if (well) {
                clearWell(s, x, y, w, h, strength);
            } else {
                clearPanel(s, x, y, w, h, strength);
            }
            return;
        }
        if (well) {
            well(s, x, y, w, h);
        } else {
            panel(s, x, y, w, h);
        }
    }
    public static void backdrop(AeroSurface.Backdrop s) {
        s.beginBlurredStratum();
        int w = s.width();
        int h = s.height();
        if (s.blursBehind()) {
            s.gradient(0, 0, w, h, 0x2C0B2027, 0x3A050D11);
        } else {
            s.gradient(0, 0, w, h, 0x740B2027, 0x82050D11);
        }
        s.gradient(0, 0, w, Math.max(1, h / 3), 0x1C1A5A63, 0x00000000);
    }
}
