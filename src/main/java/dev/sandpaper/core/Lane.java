package dev.sandpaper.core;
// Scheduler pump points.
public enum Lane {
    // World state is safe to read here.
    TICK,
    // Pumped once per HUD extraction.
    RENDER
}
