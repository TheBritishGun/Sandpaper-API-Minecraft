package dev.sandpaper.api.settings;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;
public record SettingsGroup(Component title, Component description,
        List<OptionSpec> options) {
    public SettingsGroup {
        Objects.requireNonNull(title, "title");
        options = List.copyOf(options);
        if (options.isEmpty()) {
            throw new IllegalArgumentException("a group has no options.");
        }
    }
    public SettingsGroup(Component title, List<OptionSpec> options) {
        this(title, null, options);
    }
}
