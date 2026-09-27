package dev.sandpaper.core;
// Point where the scheduler is pumped, each with its own budget.
public enum Lane {
    // World state is safe to read here.
    TICK,
    // Pumps once per HUD extraction, not per frame; with no pumps its jobs stop.
    RENDER
}
