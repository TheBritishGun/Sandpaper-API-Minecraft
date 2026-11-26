package dev.sandpaper.client.gui.theme;

import java.util.Arrays;

public final class ThemeBlender {

    private static final int NONE = -1;
    private static final double FOCUS_FRACTION = 0.35;
    private static final double BAND_FRACTION = 0.28;
    private static final double MIN_BAND_HALF_PX = 48.0;
    private static final double MAX_BAND_HALF_PX = 120.0;
    private static final double SHORT_SPAN_FRACTION = 0.45;
    private static final double FULL_BAND_FACTOR = 2.0;
    private static final double QUINTIC_HIGH = 6.0;
    private static final double QUINTIC_MIDDLE = 15.0;
    private static final double QUINTIC_LOW = 10.0;
    private static final double FILTER_EASE_RATE = 6.0;
    private static final double FILTER_SNAP_EPSILON = 0.001;
    private static final float MIDPOINT = 0.5F;
    private static final float HYSTERESIS = 0.08F;

    private final BlendedPalette lastPalette = new BlendedPalette();
    private final BlendedPalette filterStartPalette = new BlendedPalette();
    private final BlendedPalette emptyPalette = new BlendedPalette();
    private int[] spanStart = new int[0];
    private int[] spanEnd = new int[0];
    private int[] spanTheme = new int[0];
    private int spanCount = 0;
    private int activeSpan = NONE;
    private int primarySpan = NONE;
    private int secondarySpan = NONE;
    private int primaryTheme = NONE;
    private int secondaryTheme = NONE;
    private float geometricWeight = 0.0F;
    private float displayWeight = 0.0F;
    private int dominantTheme = NONE;
    private int crossings = 0;
    private boolean hasLastPalette = false;
    private boolean hasFilterStartPalette = false;
    private boolean lastPaletteUsedSnapshot = false;
    private boolean filterStartUsedSnapshot = false;
    private boolean filterPairContinues = false;
    private boolean needsDominantSeed = true;
    private int filterStartPrimary = NONE;
    private int filterStartSecondary = NONE;
    private float filterStartWeight = 0.0F;
    private double filterRetention = 0.0;

    public void rebuildSpans(int[] sectionY, int[] sectionEnd, int[] sectionTheme, int count) {
        int limit = Math.max(0, Math.min(count, Math.min(sectionY.length, Math.min(sectionEnd.length,
                sectionTheme.length))));
        ensureCapacity(limit);
        int written = 0;
        int pendingStart = 0;
        int pendingEnd = 0;
        int pendingTheme = NONE;
        boolean changed = false;
        for (int i = 0; i < limit; i++) {
            int start = Math.max(sectionY[i], pendingEnd);
            int end = sectionEnd[i];
            int theme = sectionTheme[i];
            if (sectionY[i] < 0 || theme < 0 || end <= start) {
                continue;
            }
            if (theme != pendingTheme) {
                if (pendingTheme != NONE) {
                    changed |= writeSpan(written, pendingStart, pendingEnd, pendingTheme);
                    written++;
                }
                pendingStart = start;
                pendingTheme = theme;
            }
            pendingEnd = end;
        }
        if (pendingTheme != NONE) {
            changed |= writeSpan(written, pendingStart, pendingEnd, pendingTheme);
            written++;
        }
        if (changed || written != spanCount) {
            beginFilterEase();
        }
        spanCount = written;
    }

    public void update(double displayScrollPx, double viewHeight, double focusLeadPx, double dtSeconds) {
        double height = nonNegativeFinite(viewHeight);
        double lead = Math.min(height * FOCUS_FRACTION, nonNegativeFinite(focusLeadPx));
        double focusY = Math.min(Double.MAX_VALUE, nonNegativeFinite(displayScrollPx) + lead);
        locateBlend(focusY, height);
        primaryTheme = primarySpan == NONE ? NONE : spanTheme[primarySpan];
        secondaryTheme = secondarySpan == NONE ? NONE : spanTheme[secondarySpan];
        easeFilter(dtSeconds);
        updateDominant();
    }

    public int spanCount() {
        return spanCount;
    }

    public int spanStartPx(int span) {
        return spanStart[span];
    }

    public int spanEndPx(int span) {
        return spanEnd[span];
    }

    public int spanThemeIndex(int span) {
        return spanTheme[span];
    }

    public int activeSpanIndex() {
        return activeSpan;
    }

    public int primarySpanIndex() {
        return primarySpan;
    }

    public int secondarySpanIndex() {
        return secondarySpan;
    }

    public int primaryThemeIndex() {
        return primaryTheme;
    }

    public int secondaryThemeIndex() {
        return secondaryTheme;
    }

    public float blendWeightB() {
        return displayWeight;
    }

    public float weightForTheme(int themeIndex) {
        if (themeIndex < 0) {
            return 0.0F;
        }
        float weight;
        if (themeIndex == primaryTheme) {
            weight = 1.0F - displayWeight;
        } else if (themeIndex == secondaryTheme) {
            weight = displayWeight;
        } else {
            weight = 0.0F;
        }
        return weight;
    }

    public int dominantThemeIndex() {
        return dominantTheme;
    }

    public boolean consumeCrossing() {
        boolean pending = crossings > 0;
        if (pending) {
            crossings--;
        }
        return pending;
    }

    public void blendPaletteInto(BlendedPalette[] themePalettes, BlendedPalette out) {
        boolean retainPalette = filterRetention > 0.0 && hasFilterStartPalette
                && (!filterPairContinues || filterStartUsedSnapshot);
        BlendedPalette first = paletteFor(themePalettes, primaryTheme);
        if (secondaryTheme == NONE) {
            out.copyFrom(first);
        } else {
            float weight = retainPalette ? geometricWeight : displayWeight;
            out.mixFrom(first, paletteFor(themePalettes, secondaryTheme), weight);
        }
        if (retainPalette) {
            out.mixFrom(out, filterStartPalette, (float) filterRetention);
        }
        lastPalette.copyFrom(out);
        hasLastPalette = true;
        lastPaletteUsedSnapshot = retainPalette;
    }

    private void ensureCapacity(int count) {
        if (count > spanStart.length) {
            spanStart = Arrays.copyOf(spanStart, count);
            spanEnd = Arrays.copyOf(spanEnd, count);
            spanTheme = Arrays.copyOf(spanTheme, count);
        }
    }

    private boolean writeSpan(int span, int start, int end, int theme) {
        boolean changed = span >= spanCount || spanStart[span] != start || spanEnd[span] != end
                || spanTheme[span] != theme;
        spanStart[span] = start;
        spanEnd[span] = end;
        spanTheme[span] = theme;
        return changed;
    }

    private void beginFilterEase() {
        filterStartPrimary = primaryTheme;
        filterStartSecondary = secondaryTheme;
        filterStartWeight = displayWeight;
        hasFilterStartPalette = hasLastPalette;
        filterStartUsedSnapshot = lastPaletteUsedSnapshot;
        filterStartPalette.copyFrom(lastPalette);
        filterRetention = primaryTheme != NONE || hasLastPalette ? 1.0 : 0.0;
        needsDominantSeed = true;
    }

    private void locateBlend(double focusY, double height) {
        activeSpan = findActiveSpan(focusY);
        primarySpan = activeSpan;
        secondarySpan = NONE;
        geometricWeight = 0.0F;
        if (activeSpan != NONE) {
            double halfBand = Math.clamp(height * BAND_FRACTION, MIN_BAND_HALF_PX, MAX_BAND_HALF_PX);
            boolean inLeftBand = selectBand(activeSpan - 1, focusY, halfBand);
            if (!inLeftBand) {
                selectBand(activeSpan, focusY, halfBand);
            }
        }
    }

    private int findActiveSpan(double focusY) {
        if (spanCount == 0) {
            return NONE;
        }
        int low = 0;
        int high = spanCount;
        while (high - low > 1) {
            int mid = low + (high - low) / 2;
            if (focusY >= spanStart[mid]) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private boolean selectBand(int left, double focusY, double maxHalfBand) {
        if (left < 0 || left >= spanCount - 1) {
            return false;
        }
        int right = left + 1;
        int shorter = Math.min(spanEnd[left] - spanStart[left], spanEnd[right] - spanStart[right]);
        double halfBand = Math.min(maxHalfBand, SHORT_SPAN_FRACTION * shorter);
        double distance = focusY - spanStart[right];
        boolean inBand = halfBand > 0.0 && Math.abs(distance) <= halfBand;
        if (inBand) {
            double u = Math.clamp((distance + halfBand) / (FULL_BAND_FACTOR * halfBand), 0.0, 1.0);
            double weight = u * u * u * (u * (u * QUINTIC_HIGH - QUINTIC_MIDDLE) + QUINTIC_LOW);
            primarySpan = left;
            secondarySpan = right;
            geometricWeight = (float) Math.clamp(weight, 0.0, 1.0);
        }
        return inBand;
    }

    private void easeFilter(double dtSeconds) {
        if (filterRetention <= 0.0) {
            displayWeight = geometricWeight;
            filterPairContinues = false;
            return;
        }
        if (Double.isFinite(dtSeconds) && dtSeconds > 0.0) {
            filterRetention *= Math.exp(-dtSeconds * FILTER_EASE_RATE);
            if (filterRetention <= FILTER_SNAP_EPSILON) {
                filterRetention = 0.0;
            }
        }
        if (secondaryTheme != NONE && filterPairMatches()) {
            filterPairContinues = true;
            float startWeight = secondaryTheme == filterStartSecondary ? filterStartWeight
                    : 1.0F - filterStartWeight;
            displayWeight = (float) (geometricWeight + (startWeight - geometricWeight) * filterRetention);
        } else if (secondaryTheme != NONE && filterStartSecondary == NONE
                && (filterStartPrimary == primaryTheme || filterStartPrimary == secondaryTheme)) {
            filterPairContinues = true;
            float startWeight = filterStartPrimary == primaryTheme ? 0.0F : 1.0F;
            displayWeight = (float) (geometricWeight + (startWeight - geometricWeight) * filterRetention);
        } else {
            filterPairContinues = false;
            displayWeight = geometricWeight;
        }
    }

    private boolean filterPairMatches() {
        return (filterStartPrimary == primaryTheme && filterStartSecondary == secondaryTheme)
                || (filterStartPrimary == secondaryTheme && filterStartSecondary == primaryTheme);
    }

    private void updateDominant() {
        int next = primaryTheme;
        if (secondaryTheme != NONE) {
            float threshold = MIDPOINT;
            if (!needsDominantSeed && dominantTheme == primaryTheme) {
                threshold += HYSTERESIS;
            } else if (!needsDominantSeed && dominantTheme == secondaryTheme) {
                threshold -= HYSTERESIS;
            } else {
                threshold = MIDPOINT;
            }
            boolean secondaryDominates = geometricWeight > threshold
                    || (geometricWeight >= threshold && dominantTheme != secondaryTheme);
            if (secondaryDominates) {
                next = secondaryTheme;
            }
        }
        if (!needsDominantSeed && dominantTheme != NONE && next != NONE && next != dominantTheme
                && crossings < Integer.MAX_VALUE) {
            crossings++;
        }
        dominantTheme = next;
        needsDominantSeed = false;
    }

    private BlendedPalette paletteFor(BlendedPalette[] palettes, int theme) {
        BlendedPalette fallback = palettes.length > 0 && palettes[0] != null ? palettes[0] : emptyPalette;
        return theme >= 0 && theme < palettes.length && palettes[theme] != null ? palettes[theme] : fallback;
    }

    private static double nonNegativeFinite(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 0.0;
    }
}
