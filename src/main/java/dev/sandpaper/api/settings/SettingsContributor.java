package dev.sandpaper.api.settings;
import java.util.List;
// How a mod adds its own pages to Sandpaper's settings screen
public interface SettingsContributor {
    // Fabric entrypoint key; frozen once a manifest uses it, cannot migrate
    String ENTRYPOINT = "sandpaper-settings";
    // Pages to show, freshly read each open; null return is a fault, not empty
    List<SettingsPage> pages();
    // Writes this mod's settings after all sinks run; return false if the write failed
    boolean save();
    // Store the handle in a field; each screen opening replaces it and retires the old one
    default void screenOpened(Rules rules) {
    }
    // Handle for your own rows on one screen opening; other contributors are unreachable
    interface Rules {
        // Client thread only; a call from elsewhere is refused and logged, not run
        void askAgain();
    }
}
