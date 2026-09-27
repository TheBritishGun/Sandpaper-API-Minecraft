package dev.sandpaper.core;
// Computes a time budget from frame load and headroom; not thread safe, use only from the pumping thread.
public final class FrameBudget {
    // Matches vanilla's own sentinel for unlimited framerate.
    private static final int UNLIMITED_FRAMERATE_CUTOFF = 260;
    private final long targetNanos;
    private final long minNanos;
    private final long maxNanos;
    private final double share;
    private long smoothedFrameNanos;
    public FrameBudget(long targetNanos, double share, long minNanos, long maxNanos) {
        if (targetNanos <= 0) {
            throw new IllegalArgumentException("targetNanos must be positive");
        }
        if (Double.isNaN(share) || share <= 0.0 || share > 1.0) {
            throw new IllegalArgumentException("share must be in (0, 1], not " + share);
        }
        if (minNanos < 0 || maxNanos < minNanos) {
            throw new IllegalArgumentException("need 0 <= minNanos <= maxNanos");
        }
        this.targetNanos = targetNanos;
        this.share = share;
        this.minNanos = minNanos;
        this.maxNanos = maxNanos;
    }
    // Matches SandpaperConfig's own defaults.
    private static final double DEFAULT_RENDER_SHARE = 1.0 / 3.0;
    private static final long DEFAULT_FLOOR_NANOS = 250_000L;
    private static final long DEFAULT_CEILING_NANOS = 4_000_000L;
    private static final long MIN_CEILING_GUARD_NANOS = 1_000_000L;
    public static FrameBudget sixtyHertz() {
        long target = 16_666_666L;
        return new FrameBudget(target, DEFAULT_RENDER_SHARE, DEFAULT_FLOOR_NANOS,
                DEFAULT_CEILING_NANOS);
    }
    public static FrameBudget forFramerate(int framesPerSecond) {
        if (framesPerSecond <= 0 || framesPerSecond >= UNLIMITED_FRAMERATE_CUTOFF) {
            return sixtyHertz();
        }
        long target = 1_000_000_000L / framesPerSecond;
        // Ceiling scales with frame rate; floor stays flat.
        long ceiling = Math.min(DEFAULT_CEILING_NANOS,
                Math.max(MIN_CEILING_GUARD_NANOS, target / 4));
        return new FrameBudget(target, DEFAULT_RENDER_SHARE,
                Math.min(DEFAULT_FLOOR_NANOS, target / 8), ceiling);
    }
    public void sample(long frameNanos) {
        if (frameNanos <= 0) {
            return;
        }
        // Exact only when both operands are non-negative.
        smoothedFrameNanos = smoothedFrameNanos == 0L
                ? frameNanos
                : (smoothedFrameNanos * 7 + frameNanos) >>> 3;
    }
    public long budgetNanos() {
        if (smoothedFrameNanos == 0L) {
            return minNanos;
        }
        long gapNanos = targetNanos - smoothedFrameNanos;
        if (gapNanos <= 0L) {
            return minNanos;
        }
        long budget = (long) (share * gapNanos);
        // Relies on the constructor's minNanos <= maxNanos guarantee.
        return Math.max(minNanos, Math.min(maxNanos, budget));
    }
    public long smoothedFrameNanos() {
        return smoothedFrameNanos;
    }
    public long targetNanos() {
        return targetNanos;
    }
}
