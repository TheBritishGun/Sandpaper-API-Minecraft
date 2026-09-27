package dev.sandpaper.client.gui.options;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;
// A named section of settings; groups is never empty, icon may be null.
public record OptPage(Component name, List<Group> groups, String icon) {
    // Throws NPE for a null name, list, or group; IllegalArgumentException if empty.
    public OptPage {
        Objects.requireNonNull(name, "name");
        groups = List.copyOf(groups);
        if (groups.isEmpty()) {
            // Wording matched to SettingsPage's own empty-groups guard; keep them identical.
            throw new IllegalArgumentException("a page has no groups.");
        }
    }
    public OptPage(Component name, List<Group> groups) {
        this(name, groups, null);
    }
    public static OptPage of(Component name, Group... groups) {
        return new OptPage(name, List.of(groups));
    }
    // A heading and its rows; description may be null, but options is never empty.
    public record Group(Component name, Component description, List<Opt> options) {
        // Throws NPE for a null name, list, or row; IllegalArgumentException if empty.
        public Group {
            Objects.requireNonNull(name, "name");
            options = List.copyOf(options);
            if (options.isEmpty()) {
                // Same sentence as SettingsGroup's own empty-options guard; keep the two identical.
                throw new IllegalArgumentException("a group has no options.");
            }
        }
        public static Group of(Component name, Opt... options) {
            return new Group(name, null, List.of(options));
        }
        public static Group of(Component name, Component description, Opt... options) {
            return new Group(name, description, List.of(options));
        }
    }
}
