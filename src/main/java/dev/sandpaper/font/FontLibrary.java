package dev.sandpaper.font;
import dev.sandpaper.core.CoreLog;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.zip.CRC32;
public final class FontLibrary {
    public static final float MIN_AUTO_SIZE = 1f;
    public static final float MAX_AUTO_SIZE = 32f;
    public static final List<String> FONT_FILE_EXTENSIONS = List.of(".ttf", ".otf");
    public static final List<String> FONT_FILE_PATTERNS = FONT_FILE_EXTENSIONS.stream()
            .map(extension -> "*" + extension).toList();
    public static final String FONT_FILE_DESCRIPTION = "Font files ("
            + String.join(", ", FONT_FILE_PATTERNS) + ")";
    private static final int FONT_SCAN_DEPTH = 4;
    private static final String TTF_DIRECTORY = "font/";
    private static final String TTF_SUFFIX = ".ttf";
    public record Face(String id, String displayName, Path file, Float autoSize) {
    }
    private record Scan(Map<String, Face> byId, List<Face> listed,
            Map<String, Revision> revisions) {
    }
    public static final class Snapshot {
        private final Scan contents;
        private Snapshot(Scan contents) {
            this.contents = contents;
        }
        public List<Face> faces() {
            return contents.listed();
        }
    }
    private record Revision(long size, FileTime modified) {
    }
    private record Discovered(Path file, String fileName, Revision revision, Float autoSize) {
    }
    private final Path directory;
    // Only a successful resolve overwrites this.
    private volatile Path directoryRealPath;
    private final Consumer<String> note;
    // Immutable; always replaced whole, never mutated in place.
    private volatile Scan scan =
            new Scan(Collections.emptyMap(), List.of(), Collections.emptyMap());
    public FontLibrary(Path directory) {
        this(directory, line -> System.err.println(CoreLog.STANDARD_ERROR_PREFIX + line));
    }
    public FontLibrary(Path directory, Consumer<String> note) {
        this.directory = directory;
        this.note = note == null ? line -> { } : note;
    }
    public Path directory() {
        return directory;
    }
    public boolean refresh() {
        return adopt(scan());
    }
    public Snapshot scan() {
        Map<String, Face> built = new LinkedHashMap<>();
        Map<String, Revision> revisions = new HashMap<>();
        if (directory != null && Files.isDirectory(directory)) {
            List<Discovered> found = new ArrayList<>();
            Path realDirectory = rememberRealDirectory(realPath(directory));
            try {
                Files.walkFileTree(directory, Set.of(), FONT_SCAN_DEPTH,
                        new SimpleFileVisitor<Path>() {
                    private final boolean[] inside = new boolean[FONT_SCAN_DEPTH];
                    private int depth = -1;
                    @Override
                    public FileVisitResult preVisitDirectory(Path folder,
                            BasicFileAttributes attributes) {
                        depth++;
                        inside[depth] = depth == 0 || (mightBeALink(folder)
                                ? isWithin(realDirectory, folder) : inside[depth - 1]);
                        return FileVisitResult.CONTINUE;
                    }
                    private boolean mightBeALink(Path folder) {
                        try {
                            BasicFileAttributes own = Files.readAttributes(folder,
                                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                            return own.isSymbolicLink() || own.isOther();
                        } catch (IOException | RuntimeException unreadable) {
                            return true;
                        }
                    }
                    @Override
                    public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                        Path fileName = path.getFileName();
                        if (fileName == null || depth < 0) {
                            return FileVisitResult.CONTINUE;
                        }
                        String name = fileName.toString();
                        if (isFontFile(name) && realDirectory != null && inside[depth]) {
                            Revision revision = revisionOf(path);
                            if (revision != null) {
                                found.add(new Discovered(path, name, revision,
                                        FontTableReader.autoSize(path)));
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult postVisitDirectory(Path folder, IOException failed)
                            throws IOException {
                        depth--;
                        return super.postVisitDirectory(folder, failed);
                    }
                });
            } catch (IOException | RuntimeException partial) {
                note.accept("could not finish reading the font folder " + directory
                        + ": " + partial + " (fonts found before this"
                        + " stay listed).");
            }
            found.sort((a, b) -> a.fileName().compareToIgnoreCase(b.fileName()));
            int count = found.size();
            String[] fileNames = new String[count];
            String[] displays = new String[count];
            String[] bases = new String[count];
            Map<String, Integer> sharing = new HashMap<>();
            for (int i = 0; i < count; i++) {
                fileNames[i] = found.get(i).fileName();
                displays[i] = stripExtension(fileNames[i]);
                bases[i] = sanitise(displays[i]);
                sharing.merge(bases[i], 1, Integer::sum);
            }
            Set<String> taken = new HashSet<>();
            for (Map.Entry<String, Integer> name : sharing.entrySet()) {
                if (name.getValue() == 1) {
                    taken.add(name.getKey());
                }
            }
            Map<String, Integer> nextSuffix = new HashMap<>();
            for (int i = 0; i < count; i++) {
                Discovered each = found.get(i);
                String id = bases[i];
                if (sharing.get(id) > 1) {
                    id = uniqueId(taken, sharedId(id, directory.relativize(each.file())),
                            nextSuffix);
                    taken.add(id);
                }
                built.put(id, new Face(id, displays[i], each.file(), each.autoSize()));
                revisions.put(id, each.revision());
            }
        }
        return new Snapshot(new Scan(Collections.unmodifiableMap(built), List.copyOf(built.values()),
                Collections.unmodifiableMap(revisions)));
    }
    public synchronized boolean adopt(Snapshot snapshot) {
        Scan next = snapshot.contents;
        Scan before = scan;
        boolean changed = !before.byId().equals(next.byId())
                || !before.revisions().equals(next.revisions());
        scan = next;
        return changed;
    }
    public static boolean isFontFile(String fileName) {
        if (fileName == null) {
            return false;
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        for (String extension : FONT_FILE_EXTENSIONS) {
            if (name.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }
    private static Revision revisionOf(Path file) {
        try {
            BasicFileAttributes attributes = file.getFileSystem().provider()
                    .readAttributesIfExists(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes == null || !attributes.isRegularFile() ? null
                    : new Revision(attributes.size(), attributes.lastModifiedTime());
        } catch (IOException unreadable) {
            return null;
        }
    }
    static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }
    public static String sanitise(String name) {
        if (name == null || name.isBlank()) {
            return "font";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = Character.toLowerCase(name.charAt(i));
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.';
            sb.append(ok ? c : '_');
        }
        int start = 0;
        int end = sb.length();
        while (start < end && (sb.charAt(start) == '_' || sb.charAt(start) == '.')) {
            start++;
        }
        while (end > start && (sb.charAt(end - 1) == '_' || sb.charAt(end - 1) == '.')) {
            end--;
        }
        return start == end ? "font" : sb.substring(start, end);
    }
    // Tracks ids used in this scan only, not the published map.
    private static String uniqueId(Set<String> taken, String base,
            Map<String, Integer> nextSuffix) {
        if (!taken.contains(base)) {
            return base;
        }
        for (int n = nextSuffix.getOrDefault(base, 2); ; n++) {
            String candidate = base + "_" + n;
            if (!taken.contains(candidate)) {
                nextSuffix.put(base, n + 1);
                return candidate;
            }
        }
    }
    private static String sharedId(String base, Path place) {
        StringBuilder spelled = new StringBuilder();
        for (Path part : place) {
            if (spelled.length() > 0) {
                spelled.append('/');
            }
            spelled.append(part);
        }
        CRC32 crc = new CRC32();
        crc.update(spelled.toString().getBytes(StandardCharsets.UTF_8));
        return base + "_" + Long.toHexString(crc.getValue());
    }
    public List<Face> faces() {
        return scan.listed();
    }
    public Face face(String id) {
        return scan.byId().get(id);
    }
    // Whether file resolves inside this library's folder (links and junctions resolved).
    public boolean contains(Path file) {
        return isWithin(directoryRealPath(), file);
    }
    // Resolves and caches the folder's real path on first success.
    private Path directoryRealPath() {
        Path known = directoryRealPath;
        return known != null ? known : rememberRealDirectory(realPath(directory));
    }
    private Path rememberRealDirectory(Path resolved) {
        if (resolved != null) {
            directoryRealPath = resolved;
        }
        return resolved;
    }
    private static Path realPath(Path path) {
        if (path == null) {
            return null;
        }
        try {
            return path.toRealPath();
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
    private static boolean isWithin(Path directory, Path file) {
        Path realFile = realPath(file);
        return directory != null && realFile != null && realFile.startsWith(directory);
    }
    // The same resource ttfFile names, as a path instead of an id.
    public static String ttfPath(Face face) {
        return TTF_DIRECTORY + face.id() + TTF_SUFFIX;
    }
    public static String ttfId(String path) {
        if (path == null || !path.startsWith(TTF_DIRECTORY) || !path.endsWith(TTF_SUFFIX)) {
            return null;
        }
        return path.substring(TTF_DIRECTORY.length(), path.length() - TTF_SUFFIX.length());
    }
    // Never include a font/ prefix here; the loader adds it, or the file is not found.
    public static String ttfFile(String namespace, Face face) {
        return namespace + ":" + face.id() + ".ttf";
    }
    // Diagnostic only, not a filter; unreadable files read as true.
    public static boolean looksLikeTrueType(Path file) {
        Boolean sniffed = readTrueTypeSniff(file);
        return sniffed == null || sniffed.booleanValue();
    }
    private static Boolean readTrueTypeSniff(Path file) {
        byte[] tag = new byte[4];
        try (InputStream in = Files.newInputStream(file)) {
            if (in.readNBytes(tag, 0, tag.length) < tag.length) {
                return Boolean.TRUE;
            }
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        int sfnt = ((tag[0] & 0xFF) << 24) | ((tag[1] & 0xFF) << 16)
                | ((tag[2] & 0xFF) << 8) | (tag[3] & 0xFF);
        // 0x00010000, true and ttcf mark TrueType; OTTO (CFF) is refused.
        return sfnt == 0x00010000 || sfnt == 0x74727565 || sfnt == 0x74746366;
    }
    public static boolean knownTrueType(Path file) {
        Revision current = file == null ? null : revisionOf(file);
        if (current == null) {
            return looksLikeTrueType(file);
        }
        Sniff known;
        synchronized (SNIFFS) {
            known = SNIFFS.get(file);
        }
        if (known != null && known.revision().equals(current)) {
            return known.trueType();
        }
        Boolean sniffed = readTrueTypeSniff(file);
        if (sniffed == null) {
            return true;
        }
        boolean trueType = sniffed.booleanValue();
        rememberSniff(file, current, trueType);
        return trueType;
    }
    private static void rememberSniff(Path file, Revision current, boolean trueType) {
        synchronized (SNIFFS) {
            if (SNIFFS.containsKey(file) || SNIFFS.size() < MAX_SNIFFS) {
                SNIFFS.put(file, new Sniff(current, trueType));
                return;
            }
            Iterator<Path> eldest = SNIFFS.keySet().iterator();
            if (!eldest.hasNext()) {
                return;
            }
            Path victim = eldest.next();
            if (revisionOf(victim) == null) {
                eldest.remove();
                SNIFFS.put(file, new Sniff(current, trueType));
                return;
            }
            Sniff live = SNIFFS.remove(victim);
            if (live != null) {
                SNIFFS.put(victim, live);
            }
        }
    }
    private record Sniff(Revision revision, boolean trueType) {
    }
    private static final int MAX_SNIFFS = 512;
    private static final Map<Path, Sniff> SNIFFS = new LinkedHashMap<>();
    // Provider order matters: ttf must precede fallback or fallback always wins.
    public static String definitionJson(String namespace, Face face, float size,
            float oversample, String fallback) {
        String reference = fallback == null || fallback.isBlank() ? "" : """
                ,
                    {
                      "type": "reference",
                      "id": "%s"
                    }""".formatted(fallback);
        return """
                {
                  "providers": [
                    {
                      "type": "ttf",
                      "file": "%s",
                      "shift": [0, 0],
                      "size": %s,
                      "oversample": %s
                    }%s
                  ]
                }
                """.formatted(ttfFile(namespace, face), trim(size), trim(oversample), reference);
    }
    public static String packMetaJson(int packFormat, String description) {
        return """
                {
                  "pack": {
                    "pack_format": %d,
                    "min_format": %d,
                    "max_format": %d,
                    "description": "%s"
                  }
                }
                """.formatted(packFormat, packFormat, packFormat, description.replace("\"", "'"));
    }
    private static String trim(float value) {
        return value == Math.rint(value)
                ? Integer.toString((int) value)
                : Float.toString(value);
    }
}
