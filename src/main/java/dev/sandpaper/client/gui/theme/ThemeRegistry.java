package dev.sandpaper.client.gui.theme;

import dev.sandpaper.api.theme.MenuTheme;
import dev.sandpaper.api.theme.ThemeAmbient;
import dev.sandpaper.api.theme.ThemeBackdrop;
import dev.sandpaper.api.theme.ThemeCenterpiece;
import dev.sandpaper.api.theme.ThemeContributor;
import dev.sandpaper.api.theme.ThemeSurface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ThemeRegistry implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ThemeRegistry.class);
    private static final String HOST_MOD_ID = "sandpaper";
    private static final float MIN_BACKDROP_ALPHA = 0.01f;
    private static final Comparator<Supplied> DISCOVERY_ORDER = Comparator
            .<Supplied>comparingInt(entry -> HOST_MOD_ID.equals(entry.modId()) ? 0 : 1)
            .thenComparing(Supplied::modId);

    record Supplied(String modId, Supplier<ThemeContributor> supplier) {
        Supplied {
            Objects.requireNonNull(modId, "modId");
            Objects.requireNonNull(supplier, "supplier");
        }
    }

    private record Contribution(
            List<MenuTheme> themes,
            Identifier defaultId,
            Map<Identifier, Identifier> pages) {
    }

    private static final class Bindings {
        private final Map<Identifier, Identifier> pages = new HashMap<>();
        private Identifier defaultId = null;
    }

    private static final class Registered {
        private final MenuTheme theme;
        private boolean backdropDisabled = false;
        private boolean centerpieceDisabled = false;
        private boolean ambientDisabled = false;

        private Registered(MenuTheme theme) {
            this.theme = theme;
        }
    }

    private final Registered fallback;
    private final Map<Identifier, Registered> registered = new HashMap<>();
    private final Map<String, Bindings> bindings = new HashMap<>();
    private int engaged = 0;
    private boolean closed = false;

    private ThemeRegistry(MenuTheme fallbackTheme) {
        fallback = new Registered(Objects.requireNonNull(fallbackTheme, "fallbackTheme"));
    }

    public static ThemeRegistry gather(MenuTheme fallbackTheme) {
        return gatherFrom(discover(), fallbackTheme);
    }

    public MenuTheme resolve(String modId, Identifier pageId) {
        if (closed || modId == null) {
            return fallback.theme;
        }
        Bindings mod = bindings.get(modId);
        if (mod == null) {
            return fallback.theme;
        }
        Registered match = pageId == null ? null : registered.get(mod.pages.get(pageId));
        if (match == null) {
            match = registered.get(mod.defaultId);
        }
        if (match != null && engaged < Integer.MAX_VALUE) {
            engaged++;
        }
        return match == null ? fallback.theme : match.theme;
    }

    public int engagedCount() {
        return engaged;
    }

    public int registeredCount() {
        return registered.size();
    }

    public boolean paintBackdrop(MenuTheme theme, ThemeSurface surface, ThemeBackdrop.BackdropContext ctx) {
        Registered state = activeState(theme);
        if (surface == null || ctx == null || state == null || state.backdropDisabled
                || !(theme.backdrop() instanceof ThemeBackdrop.Custom custom)
                || ctx.blendAlpha() < MIN_BACKDROP_ALPHA) {
            return false;
        }
        boolean painted = false;
        try {
            custom.painter().paint(surface, ctx);
            painted = true;
        } catch (Throwable broken) {
            state.backdropDisabled = true;
            LOGGER.error("Theme {} backdrop failed and was disabled for this menu.", theme.id(), broken);
        }
        return painted;
    }

    public boolean paintCenterpiece(MenuTheme theme, ThemeSurface surface, ThemeCenterpiece.CenterpieceContext ctx) {
        Registered state = activeState(theme);
        if (surface == null || ctx == null || state == null || state.centerpieceDisabled
                || !(theme.centerpiece() instanceof ThemeCenterpiece.Custom custom)) {
            return false;
        }
        boolean painted = false;
        try {
            custom.painter().paint(surface, ctx);
            painted = true;
        } catch (Throwable broken) {
            disableCenterpiece(state, broken);
        }
        return painted;
    }

    public boolean pointerDownCenterpiece(MenuTheme theme, double mouseX, double mouseY,
            ThemeCenterpiece.CenterpieceContext ctx) {
        Registered state = activeState(theme);
        if (ctx == null || state == null || state.centerpieceDisabled
                || !(theme.centerpiece() instanceof ThemeCenterpiece.Custom custom)) {
            return false;
        }
        boolean handled = false;
        try {
            handled = custom.painter().pointerDown(mouseX, mouseY, ctx);
        } catch (Throwable broken) {
            disableCenterpiece(state, broken);
        }
        return handled;
    }

    public boolean paintAmbient(MenuTheme theme, ThemeSurface surface, ThemeBackdrop.BackdropContext ctx) {
        Registered state = activeState(theme);
        if (surface == null || ctx == null || state == null || state.ambientDisabled) {
            return false;
        }
        ThemeAmbient painter = theme.ambient();
        if (painter == null) {
            return false;
        }
        boolean painted = false;
        try {
            painter.paint(surface, ctx);
            painted = true;
        } catch (Throwable broken) {
            state.ambientDisabled = true;
            LOGGER.error("Theme {} ambient layer failed and was disabled for this menu.", theme.id(), broken);
        }
        return painted;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Set<AutoCloseable> retired = Collections.newSetFromMap(new IdentityHashMap<>());
        closeTheme(fallback.theme, retired);
        for (Registered entry : registered.values()) {
            closeTheme(entry.theme, retired);
        }
    }

    static ThemeRegistry gatherFrom(List<Supplied> contributors, MenuTheme fallbackTheme) {
        Objects.requireNonNull(contributors, "contributors");
        ThemeRegistry made = new ThemeRegistry(fallbackTheme);
        for (Supplied entry : contributors) {
            if (entry == null) {
                LOGGER.error("A null theme contributor was skipped.");
                continue;
            }
            Contribution offered = read(entry);
            if (offered != null) {
                made.register(entry.modId(), offered);
            }
        }
        return made;
    }

    private static List<Supplied> discover() {
        List<EntrypointContainer<ThemeContributor>> found = List.of();
        try {
            found = new ArrayList<>(FabricLoader.getInstance().getEntrypointContainers(
                    ThemeContributor.ENTRYPOINT, ThemeContributor.class));
        } catch (Throwable broken) {
            LOGGER.error("Sandpaper could not list theme contributors.", broken);
        }
        List<Supplied> contributors = new ArrayList<>(found.size());
        for (EntrypointContainer<ThemeContributor> container : found) {
            try {
                String modId = container.getProvider().getMetadata().getId();
                contributors.add(new Supplied(modId, container::getEntrypoint));
            } catch (Throwable broken) {
                LOGGER.error("Sandpaper could not identify a theme contributor.", broken);
            }
        }
        contributors.sort(DISCOVERY_ORDER);
        return contributors;
    }

    private static Contribution read(Supplied entry) {
        Contribution offered = null;
        try {
            ThemeContributor contributor = Objects.requireNonNull(entry.supplier().get(), "null entrypoint");
            List<MenuTheme> themes = List.copyOf(contributor.themes());
            Identifier defaultId = contributor.defaultThemeId();
            Map<Identifier, Identifier> pages = Map.copyOf(contributor.pageThemes());
            offered = new Contribution(themes, defaultId, pages);
        } catch (Throwable broken) {
            LOGGER.error("Mod {} failed to contribute themes for this menu.", entry.modId(), broken);
        }
        return offered;
    }

    private void register(String modId, Contribution offered) {
        for (MenuTheme theme : offered.themes()) {
            Identifier id = theme.id();
            if (fallback.theme.id().equals(id) || registered.containsKey(id)) {
                LOGGER.error("Mod {} supplied duplicate theme {}. The theme was skipped.", modId, id);
                continue;
            }
            registered.put(id, new Registered(theme));
        }
        Bindings mod = bindings.computeIfAbsent(modId, unused -> new Bindings());
        if (mod.defaultId == null) {
            mod.defaultId = offered.defaultId();
        }
        for (Map.Entry<Identifier, Identifier> page : offered.pages().entrySet()) {
            mod.pages.putIfAbsent(page.getKey(), page.getValue());
        }
    }

    private Registered activeState(MenuTheme theme) {
        if (closed || theme == null) {
            return null;
        }
        Registered state = theme == fallback.theme ? fallback : registered.get(theme.id());
        return state != null && state.theme == theme ? state : null;
    }

    private static void disableCenterpiece(Registered state, Throwable broken) {
        state.centerpieceDisabled = true;
        LOGGER.error("Theme {} centerpiece failed and was disabled for this menu.", state.theme.id(), broken);
    }

    private static void closeTheme(MenuTheme theme, Set<AutoCloseable> retired) {
        if (theme.backdrop() instanceof ThemeBackdrop.Custom backdrop) {
            closePainter(theme.id(), "backdrop", backdrop.painter(), retired);
        }
        if (theme.centerpiece() instanceof ThemeCenterpiece.Custom centerpiece) {
            closePainter(theme.id(), "centerpiece", centerpiece.painter(), retired);
        }
        ThemeAmbient ambient = theme.ambient();
        if (ambient != null) {
            closePainter(theme.id(), "ambient", ambient, retired);
        }
    }

    private static void closePainter(Identifier themeId, String layer, AutoCloseable painter,
            Set<AutoCloseable> retired) {
        if (!retired.add(painter)) {
            return;
        }
        try {
            painter.close();
        } catch (Throwable broken) {
            LOGGER.error("Theme {} {} failed to close.", themeId, layer, broken);
        }
    }
}
