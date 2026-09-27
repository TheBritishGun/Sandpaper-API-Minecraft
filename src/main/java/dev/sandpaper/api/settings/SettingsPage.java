package dev.sandpaper.api.settings;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
// One entry in the settings screen's left column, and its groups; icon may be null
public record SettingsPage(Identifier id, Component title, List<SettingsGroup> groups,
        Identifier icon) {
    // Throws if any argument or group is null, or if there are no groups
    public SettingsPage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        groups = List.copyOf(groups);
        if (groups.isEmpty()) {
            // Wording must match the painter-side empty-groups guard; keep both in sync
            throw new IllegalArgumentException(id + " has no groups.");
        }
    }
    public SettingsPage(Identifier id, Component title, List<SettingsGroup> groups) {
        this(id, title, groups, null);
    }
}
