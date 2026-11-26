package dev.sandpaper.client.gui.options;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;
public record OptPage(Component name, List<Group> groups, String icon) {
    public OptPage {
        Objects.requireNonNull(name, "name");
        groups = List.copyOf(groups);
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("a page has no groups.");
        }
    }
    public OptPage(Component name, List<Group> groups) {
        this(name, groups, null);
    }
    public static OptPage of(Component name, Group... groups) {
        return new OptPage(name, List.of(groups));
    }
    public record Group(Component name, Component description, List<Opt> options) {
        public Group {
            Objects.requireNonNull(name, "name");
            options = List.copyOf(options);
            if (options.isEmpty()) {
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
