package dev.sandpaper.core;
// Pump rules for the RENDER lane on a menu.
public final class MenuFrame {
    // Frame time vanilla holds on a menu.
    public static final long TARGET_NANOS = 16_666_666L;
    static final long SLACK_NANOS = TARGET_NANOS / 50L;
    static final long MIN_FRAME_GAP_NANOS = TARGET_NANOS / 4L;
    // Marks "no pump yet" for isNewFrame.
    public static final long NO_PUMP_YET = Long.MIN_VALUE;
    private MenuFrame() {
    }
    // True only when no world is loaded.
    public static boolean shouldPump(boolean worldLoaded, boolean enabled) {
        return enabled && !worldLoaded;
    }
    // One pump per frame.
    public static boolean isNewFrame(long nowNanos, long lastPumpNanos) {
        return lastPumpNanos == NO_PUMP_YET || nowNanos - lastPumpNanos >= MIN_FRAME_GAP_NANOS;
    }
    public static long budgetNanos(long smoothedFrameNanos, double share, long floorNanos, long ceilingNanos) {
        if (Double.isNaN(share) || share <= 0.0 || share > 1.0) {
            throw new IllegalArgumentException("share must be in (0, 1], not " + share);
        }
        if (floorNanos < 0 || ceilingNanos < floorNanos) {
            throw new IllegalArgumentException("need 0 <= floorNanos <= ceilingNanos");
        }
        if (smoothedFrameNanos <= 0L || smoothedFrameNanos > TARGET_NANOS + SLACK_NANOS) {
            return floorNanos;
        }
        long budget = (long) (share * TARGET_NANOS);
        return Math.max(floorNanos, Math.min(ceilingNanos, budget));
    }
}
