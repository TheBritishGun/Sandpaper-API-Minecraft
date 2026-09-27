package dev.sandpaper.core;
// Phase offsets so same-cadence jobs do not all fire on the same pump.
public final class Stagger {
    private Stagger() {}
    // Consecutive phase indices spread across separate pumps, for any period.
    public static long forPumps(long phaseIndex, long periodPumps) {
        return periodPumps <= 1 ? 0 : Math.floorMod(phaseIndex, periodPumps);
    }
    // Time offset that spreads new jobs across the largest gaps.
    public static long forNanos(int registrationIndex, long periodNanos) {
        return (long) (radicalInverseBase2(registrationIndex + 1) * periodNanos);
    }
    static double radicalInverseBase2(int i) {
        return Integer.toUnsignedLong(Integer.reverse(i)) * 0x1.0p-32;
    }
}
