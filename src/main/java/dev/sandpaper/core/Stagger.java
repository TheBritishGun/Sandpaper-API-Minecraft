package dev.sandpaper.core;
public final class Stagger {
    private Stagger() {}
    public static long forPumps(long phaseIndex, long periodPumps) {
        return periodPumps <= 1 ? 0 : Math.floorMod(phaseIndex, periodPumps);
    }
    public static long forNanos(int registrationIndex, long periodNanos) {
        return (long) (radicalInverseBase2(registrationIndex + 1) * periodNanos);
    }
    static double radicalInverseBase2(int i) {
        return Integer.toUnsignedLong(Integer.reverse(i)) * 0x1.0p-32;
    }
}
