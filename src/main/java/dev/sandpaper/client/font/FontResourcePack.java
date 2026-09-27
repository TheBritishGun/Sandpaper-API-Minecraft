package dev.sandpaper.client.font;
import com.google.common.collect.ImmutableSet;
import dev.sandpaper.Sandpaper;
import dev.sandpaper.font.FontLibrary;
import dev.sandpaper.font.FontLibrary.Face;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.server.packs.resources.IoSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public final class FontResourcePack extends AbstractPackResources {
    public static final String NAMESPACE = Sandpaper.MOD_ID;
    public static final String PACK_ID = "sandpaper_fonts";
    private static final String FONT_DIRECTORY = "font/";
    private static final String FONT_DEFINITION_SUFFIX = ".json";
    // Vanilla's default font; every unstyled string resolves to it.
    static final String DEFAULT_NAMESPACE = "minecraft";
    static final String DEFAULT_PATH = "font/default.json";
    // Must match DEFAULT_PATH's resolved id; a test keeps it in sync, not code.
    static final String FALLBACK_FONT = DEFAULT_NAMESPACE + ":default";
    private static final Logger LOGGER = LoggerFactory.getLogger(Sandpaper.MOD_ID);
    private static String definitionPath(String id) {
        return FONT_DIRECTORY + id + FONT_DEFINITION_SUFFIX;
    }
    private static String definitionId(String path) {
        if (!path.startsWith(FONT_DIRECTORY) || !path.endsWith(FONT_DEFINITION_SUFFIX)) {
            return null;
        }
        return path.substring(FONT_DIRECTORY.length(),
                path.length() - FONT_DEFINITION_SUFFIX.length());
    }
    private static final Set<String> OURS = ImmutableSet.of(NAMESPACE);
    private static final Set<String> OURS_AND_VANILLAS =
            ImmutableSet.of(NAMESPACE, DEFAULT_NAMESPACE);
    private static final byte[] PACK_META_BYTES = FontLibrary.packMetaJson(
            SharedConstants.RESOURCE_PACK_FORMAT_MAJOR,
            "Fonts discovered in the Sandpaper fonts folder").getBytes(StandardCharsets.UTF_8);
    private record DefinitionKey(String faceId, float size, float oversample, String fallback) {
    }
    private static final class DefinitionCache {
        private final LinkedHashMap<DefinitionKey, byte[]> entries =
                new LinkedHashMap<>(16, 0.75f, true);
        private synchronized byte[] definition(Face face, Selection selection, String fallback) {
            DefinitionKey key = new DefinitionKey(face.id(), selection.size(),
                    selection.oversample(), fallback);
            byte[] bytes = entries.get(key);
            if (bytes == null) {
                bytes = FontLibrary.definitionJson(NAMESPACE, face, key.size(),
                        key.oversample(), fallback).getBytes(StandardCharsets.UTF_8);
                if (entries.size() == 256) {
                    entries.pollFirstEntry();
                }
                entries.put(key, bytes);
            }
            return bytes;
        }
    }
    // A snapshot of current settings; call the supplier fresh, never cache it.
    public record Selection(String fontId, boolean everything, float size, float oversample) {
        public static Selection of(String fontId, boolean everything,
                float size, float oversample) {
            return new Selection(fontId == null ? "" : fontId, everything, size, oversample);
        }
    }
    private final FontLibrary library;
    private final Supplier<Selection> selection;
    private final DefinitionCache definitions;
    private final Set<String> probed = ConcurrentHashMap.newKeySet();
    FontResourcePack(PackLocationInfo location, FontLibrary library,
                     Supplier<Selection> selection) {
        this(location, library, selection, new DefinitionCache());
    }
    private FontResourcePack(PackLocationInfo location, FontLibrary library,
            Supplier<Selection> selection, DefinitionCache definitions) {
        super(location);
        this.library = library;
        this.selection = selection;
        this.definitions = definitions;
    }
    // Null unless a face is chosen, enabled, and its file still exists.
    private Face overriding(Selection current) {
        if (current == null || !current.everything() || current.fontId().isEmpty()) {
            return null;
        }
        return library.face(current.fontId());
    }
    // Checks the face can load before returning its JSON; silent failures are logged here.
    private byte[] definition(Face face, Selection chosen, String fallback) {
        byte[] json = definitions.definition(face, chosen, fallback);
        announceProblems(face);
        return json;
    }
    // Falls back to vanilla for glyphs the chosen face does not have.
    private byte[] ownFontDefinition(Face face) {
        return definition(face, selection.get(), FALLBACK_FONT);
    }
    // Never pass the default font as its own fallback.
    private byte[] defaultFontDefinition(Face published, Selection requested) {
        Selection chosen = selection.get();
        Face current = overriding(chosen);
        if (current == null) {
            current = published;
            chosen = requested;
        }
        return definition(current, chosen, null);
    }
    // Logs each face's load failure once, then stays quiet.
    private void announceProblems(Face face) {
        if (!probed.add(face.id())) {
            return;
        }
        String named = FontLibrary.ttfFile(NAMESPACE, face);
        Identifier parsed = Identifier.tryParse(named);
        Identifier opened = parsed == null ? null : parsed.withPrefix("font/");
        if (opened == null || getResource(PackType.CLIENT_RESOURCES, opened) == null) {
            String cause;
            if (opened == null) {
                cause = "its file name is unparsable";
            } else {
                String published = FontLibrary.ttfId(opened.getPath());
                Face listed = library.face(published);
                if (listed == null) {
                    cause = "no longer lists a face named \"" + published
                            + "\"";
                } else if (!library.contains(listed.file())) {
                    cause = "\"" + listed.file() + "\" is no longer a file inside the "
                            + "fonts folder";
                } else {
                    cause = "nothing answered for \"" + opened.getPath()
                            + "\"";
                }
            }
            LOGGER.error("Sandpaper font \"{}\" ({}) is ignored and the game keeps "
                    + "vanilla lettering: definition \"{}\" "
                    + "unusable: {}.",
                    face.displayName(), face.file(), named, cause);
            return;
        }
        if (!FontLibrary.looksLikeTrueType(face.file())) {
            LOGGER.warn("Sandpaper font \"{}\" ({}) does not start with a TrueType "
                    + "signature. Ignored, vanilla lettering used. Choose a .ttf "
                    + "or a TrueType .otf.",
                    face.displayName(), face.file());
        }
    }
    public static PackLocationInfo locationInfo() {
        return new PackLocationInfo(
                PACK_ID,
                Component.literal("Sandpaper fonts"),
                PackSource.BUILT_IN,
                Optional.empty());
    }
    public static RepositorySource source(FontLibrary library, Supplier<Selection> selection) {
        DefinitionCache definitions = new DefinitionCache();
        return consumer -> {
            PackLocationInfo location = locationInfo();
            Pack pack = Pack.readMetaAndCreate(
                    location,
                    new Pack.ResourcesSupplier() {
                        @Override
                        public PackResources openPrimary(PackLocationInfo info) {
                            return new FontResourcePack(info, library, selection, definitions);
                        }
                        @Override
                        public PackResources openFull(PackLocationInfo info, Pack.Metadata meta) {
                            return new FontResourcePack(info, library, selection, definitions);
                        }
                    },
                    PackType.CLIENT_RESOURCES,
                    new PackSelectionConfig(true, Pack.Position.TOP, false));
            if (pack != null) {
                consumer.accept(pack);
            }
        };
    }
    @Override
    public IoSupplier<InputStream> getRootResource(String... path) {
        if (path.length == 1 && PACK_META.equals(path[0])) {
            return () -> stream(PACK_META_BYTES);
        }
        return null;
    }
    @Override
    public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
        if (type != PackType.CLIENT_RESOURCES) {
            return null;
        }
        if (DEFAULT_NAMESPACE.equals(id.getNamespace())) {
            if (!DEFAULT_PATH.equals(id.getPath())) {
                return null;
            }
            Selection requested = selection.get();
            Face face = overriding(requested);
            return face == null ? null
                    : () -> stream(defaultFontDefinition(face, requested));
        }
        if (!NAMESPACE.equals(id.getNamespace())) {
            return null;
        }
        String path = id.getPath();
        String name = definitionId(path);
        if (name != null) {
            Face face = library.face(name);
            return face == null ? null : () -> stream(ownFontDefinition(face));
        }
        String ttfId = FontLibrary.ttfId(path);
        if (ttfId != null) {
            Face face = library.face(ttfId);
            if (face == null || !library.contains(face.file())) {
                return null;
            }
            return () -> {
                if (!library.contains(face.file())) {
                    throw new IOException("font file is outside the font folder");
                }
                return Files.newInputStream(face.file());
            };
        }
        return null;
    }
    @Override
    public void listResources(PackType type, String namespace, String prefix,
                              ResourceOutput output) {
        if (type != PackType.CLIENT_RESOURCES) {
            return;
        }
        if (DEFAULT_NAMESPACE.equals(namespace)) {
            Selection requested = selection.get();
            Face face = overriding(requested);
            if (face != null && DEFAULT_PATH.startsWith(prefix)) {
                output.accept(
                        Identifier.fromNamespaceAndPath(DEFAULT_NAMESPACE, DEFAULT_PATH),
                        () -> stream(defaultFontDefinition(face, requested)));
            }
            return;
        }
        if (!NAMESPACE.equals(namespace)) {
            return;
        }
        for (Face face : library.faces()) {
            String path = definitionPath(face.id());
            if (!path.startsWith(prefix)) {
                continue;
            }
            Identifier definition = Identifier.fromNamespaceAndPath(NAMESPACE, path);
            output.accept(definition, () -> stream(ownFontDefinition(face)));
        }
    }
    // Claims minecraft only while actually overriding its font.
    @Override
    public Set<String> getNamespaces(PackType type) {
        if (type != PackType.CLIENT_RESOURCES) {
            return Set.of();
        }
        return overriding(selection.get()) == null ? OURS : OURS_AND_VANILLAS;
    }
    @Override
    public void close() {
    }
    private static InputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }
}
