package dev.sandpaper.core;
// Ordering weight among deferrable jobs, higher drains first, starves later.
public enum Priority {
    LOW(0),
    NORMAL(1),
    HIGH(2);
    private final int weight;
    Priority(int weight) {
        this.weight = weight;
    }
    public int weight() {
        return weight;
    }
}
