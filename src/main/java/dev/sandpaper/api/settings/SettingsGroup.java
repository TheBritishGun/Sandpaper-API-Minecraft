package dev.sandpaper.api.settings;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;
// A heading on a page and the rows grouped under it
public record SettingsGroup(Component title, Component description,
        List<OptionSpec> options) {
    // Throws if title, options, or any option is null, or if options is empty
    public SettingsGroup {
        Objects.requireNonNull(title, "title");
        options = List.copyOf(options);
        if (options.isEmpty()) {
            // Wording must match the painter-side empty-options guard; keep both in sync
            throw new IllegalArgumentException("a group has no options.");
        }
    }
    // Convenience constructor for a group with no description
    public SettingsGroup(Component title, List<OptionSpec> options) {
        this(title, null, options);
    }
}
