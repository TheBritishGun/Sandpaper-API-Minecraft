package dev.sandpaper.api.settings;
import java.util.List;
public interface SettingsContributor {
    String ENTRYPOINT = "sandpaper-settings";
    List<SettingsPage> pages();
    boolean save();
    default void screenOpened(Rules rules) {
    }
    interface Rules {
        void askAgain();
    }
}
