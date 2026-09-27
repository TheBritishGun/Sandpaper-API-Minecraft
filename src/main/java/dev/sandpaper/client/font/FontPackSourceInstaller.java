package dev.sandpaper.client.font;
import dev.sandpaper.Sandpaper;
import dev.sandpaper.mixin.PackRepositoryAccessor;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public final class FontPackSourceInstaller {
    private static final Logger LOGGER = LoggerFactory.getLogger(Sandpaper.MOD_ID);
    private static final AtomicBoolean missingRepositoryReported = new AtomicBoolean();
    private static final AtomicBoolean missingFontsSourceReported = new AtomicBoolean();
    private FontPackSourceInstaller() {
    }
    public static void addFontPackSource(PackRepository repository) {
        addFontPackSource(repository, repository == null ? null : SandpaperFonts.packSource());
    }
    static void addFontPackSource(PackRepository repository, RepositorySource fontsSource) {
        if (repository == null) {
            reportMissingRepositoryOnce();
            return;
        }
        if (fontsSource == null) {
            reportMissingFontsSourceOnce();
            return;
        }
        PackRepositoryAccessor accessor = (PackRepositoryAccessor) repository;
        Set<RepositorySource> combined = new LinkedHashSet<>(accessor.sandpaper$sources());
        combined.add(fontsSource);
        accessor.sandpaper$setSources(combined);
        SandpaperFonts.packSourceRegistered();
    }
    private static void reportMissingRepositoryOnce() {
        if (!missingRepositoryReported.compareAndSet(false, true)) {
            return;
        }
        LOGGER.warn("No resource pack repository at boot; "
                + "new fonts will not show "
                + "up this session.");
    }
    private static void reportMissingFontsSourceOnce() {
        if (!missingFontsSourceReported.compareAndSet(false, true)) {
            return;
        }
        LOGGER.warn("No fonts source at boot; "
                + "new fonts will not show up "
                + "this session.");
    }
}
