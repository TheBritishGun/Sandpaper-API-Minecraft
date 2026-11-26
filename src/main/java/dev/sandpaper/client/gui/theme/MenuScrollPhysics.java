package dev.sandpaper.client.gui.theme;

public final class MenuScrollPhysics {

    private static final int SAVED_VIEW_CAPACITY = 32;
    private static final double SCROLL_EASE_RATE = 13.5;
    private static final double WHEEL_STEP_PX = 24.0;
    private static final double SNAP_EPSILON = 0.05;
    private static final double MAX_DT = 0.1;
    private static final double JUMP_DURATION_SECONDS = 0.32;
    private static final double JUMP_EASE_RANGE = -Math.expm1(-JUMP_DURATION_SECONDS * SCROLL_EASE_RATE);
    private static final double HALF = 0.5;
    private static final double SMOOTHSTEP_FACTOR = 3.0;
    private static final double SMOOTHSTEP_SCALE = 2.0;

    private final String[] viewKeys = new String[SAVED_VIEW_CAPACITY];
    private final double[] viewScroll = new double[SAVED_VIEW_CAPACITY];
    private int nextViewSlot = 0;
    private double maxScrollPx = 0.0;
    private double targetScrollPx = 0.0;
    private double displayScrollPx = 0.0;
    private double jumpStartPx = 0.0;
    private double jumpElapsedSeconds = JUMP_DURATION_SECONDS;

    public double displayScrollPx() {
        return displayScrollPx;
    }

    public double targetScrollPx() {
        return targetScrollPx;
    }

    public void configureBounds(double maxScroll) {
        maxScrollPx = nonNegativeFinite(maxScroll);
        double boundedTarget = Math.min(targetScrollPx, maxScrollPx);
        if (boundedTarget < targetScrollPx) {
            targetScrollPx = boundedTarget;
            jumpElapsedSeconds = JUMP_DURATION_SECONDS;
        }
    }

    public void onWheel(double wheelDelta) {
        if (!Double.isFinite(wheelDelta) || Math.abs(wheelDelta) <= 0.0) {
            return;
        }
        targetScrollPx = Math.clamp(targetScrollPx - wheelDelta * WHEEL_STEP_PX, 0.0, maxScrollPx);
        jumpElapsedSeconds = JUMP_DURATION_SECONDS;
    }

    public void jumpTo(double scrollPx) {
        double destination = boundScroll(scrollPx);
        if (Math.abs(destination - targetScrollPx) > 0.0 || jumpElapsedSeconds >= JUMP_DURATION_SECONDS) {
            targetScrollPx = destination;
            jumpStartPx = displayScrollPx;
            jumpElapsedSeconds = 0.0;
        }
    }

    public void snapTo(double scrollPx) {
        targetScrollPx = boundScroll(scrollPx);
        displayScrollPx = targetScrollPx;
        jumpElapsedSeconds = JUMP_DURATION_SECONDS;
    }

    // Each view and filter has its own key.
    public void saveViewScroll(String viewKey) {
        int slot = findViewSlot(viewKey);
        if (slot < 0) {
            slot = nextViewSlot;
            viewKeys[slot] = viewKey;
            nextViewSlot = (slot + 1) % SAVED_VIEW_CAPACITY;
        }
        viewScroll[slot] = displayScrollPx;
    }

    public void restoreViewScroll(String viewKey) {
        int slot = findViewSlot(viewKey);
        snapTo(slot < 0 ? 0.0 : viewScroll[slot]);
    }

    public void step(double dtSeconds) {
        if (!Double.isFinite(dtSeconds) || dtSeconds <= 0.0) {
            return;
        }
        double dt = Math.min(dtSeconds, MAX_DT);
        if (jumpElapsedSeconds < JUMP_DURATION_SECONDS) {
            stepJump(dt);
        } else {
            double delta = targetScrollPx - displayScrollPx;
            double fraction = -Math.expm1(-dt * SCROLL_EASE_RATE);
            displayScrollPx += delta * fraction;
        }
        if (Math.abs(targetScrollPx - displayScrollPx) <= SNAP_EPSILON) {
            displayScrollPx = targetScrollPx;
            jumpElapsedSeconds = JUMP_DURATION_SECONDS;
        }
    }

    public double heroAnchorY(double crownBaseY, double spanStartPx, double scrollLiftFactor) {
        double crown = Double.isFinite(crownBaseY) ? crownBaseY : 0.0;
        double scrollLift = (displayScrollPx - nonNegativeFinite(spanStartPx)) * nonNegativeFinite(scrollLiftFactor);
        return Math.clamp(crown - scrollLift, -Double.MAX_VALUE, Double.MAX_VALUE);
    }

    public static float heroEdgeFade(double anchorY, double screenHeight, double fadeMarginPx) {
        if (!isHeroVisible(anchorY, screenHeight, fadeMarginPx)) {
            return 0.0F;
        }
        double margin = nonNegativeFinite(fadeMarginPx);
        double edgeDistance = Math.min(anchorY, screenHeight - anchorY);
        double fraction = margin > 0.0 ? HALF + HALF * Math.clamp(edgeDistance / margin, -1.0, 1.0) : 1.0;
        return (float) (fraction * fraction * (SMOOTHSTEP_FACTOR - SMOOTHSTEP_SCALE * fraction));
    }

    public static boolean isHeroVisible(double anchorY, double screenHeight, double marginPx) {
        if (!Double.isFinite(anchorY) || !Double.isFinite(screenHeight) || screenHeight <= 0.0) {
            return false;
        }
        double margin = nonNegativeFinite(marginPx);
        return margin > 0.0 ? anchorY > -margin && anchorY - screenHeight < margin
                : anchorY >= 0.0 && anchorY <= screenHeight;
    }

    private void stepJump(double dt) {
        jumpElapsedSeconds = Math.min(jumpElapsedSeconds + dt, JUMP_DURATION_SECONDS);
        double fraction = -Math.expm1(-jumpElapsedSeconds * SCROLL_EASE_RATE) / JUMP_EASE_RANGE;
        displayScrollPx = jumpStartPx + (targetScrollPx - jumpStartPx) * fraction;
        if (jumpElapsedSeconds >= JUMP_DURATION_SECONDS) {
            displayScrollPx = targetScrollPx;
        }
    }

    private int findViewSlot(String viewKey) {
        int match = -1;
        int slot = 0;
        while (slot < SAVED_VIEW_CAPACITY && match < 0) {
            if (viewKey.equals(viewKeys[slot])) {
                match = slot;
            }
            slot++;
        }
        return match;
    }

    private double boundScroll(double scrollPx) {
        return Math.min(nonNegativeFinite(scrollPx), maxScrollPx);
    }

    private static double nonNegativeFinite(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 0.0;
    }
}
