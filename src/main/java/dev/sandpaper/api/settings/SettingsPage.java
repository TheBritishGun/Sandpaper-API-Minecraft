package dev.sandpaper.api.settings;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
public record SettingsPage(Identifier id, Component title, List<SettingsGroup> groups,
        Identifier icon) {
    public SettingsPage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        groups = List.copyOf(groups);
        if (groups.isEmpty()) {
            throw new IllegalArgumentException(id + " has no groups.");
        }
    }
    public SettingsPage(Identifier id, Component title, List<SettingsGroup> groups) {
        this(id, title, groups, null);
    }
}
