package dev.sandpaper.client.font;
import dev.sandpaper.core.CoreLog;
import dev.sandpaper.font.FontLibrary;
import dev.sandpaper.font.FontLibrary.Face;
import dev.sandpaper.font.FontLibrary.Snapshot;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
// Native font-file dialog via reflection; must run on a worker thread, it blocks.
public final class FontPicker {
    private static final String DIALOGS = "org.lwjgl.util.tinyfd.TinyFileDialogs";
    private static final String STACK = "org.lwjgl.system.MemoryStack";
    private static final String POINTERS = "org.lwjgl.PointerBuffer";
    private static final List<String> PATTERNS = FontLibrary.FONT_FILE_PATTERNS;
    private static final String DESCRIPTION = FontLibrary.FONT_FILE_DESCRIPTION;
    // Guards against a second dialog opening at once.
    private static final AtomicBoolean OPEN = new AtomicBoolean();
    private FontPicker() {
    }
    // Whether the dialog can open; false greys the row instead of throwing.
    public static boolean available() {
        return dialogs() != null;
    }
    private static Class<?> dialogs() {
        try {
            return Class.forName(DIALOGS);
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }
    // Opens the dialog and installs the font; the callback runs on the client thread.
    public static void pick(FontLibrary library, Runnable fontAdded,
            Consumer<String> installed) {
        if (!OPEN.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                run(library, fontAdded, installed);
            } finally {
                OPEN.set(false);
            }
        }, "sandpaper-font-picker");
        worker.setDaemon(true);
        worker.start();
    }
    private static void run(FontLibrary library, Runnable fontAdded, Consumer<String> installed) {
        Path fontDirectory = library.directory();
        String chosen;
        try {
            chosen = open(fontDirectory);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            System.err.println(CoreLog.STANDARD_ERROR_PREFIX + "could not open the font picker: " + e);
            return;
        }
        if (chosen == null || chosen.isBlank()) {
            return;
        }
        Path source = Path.of(chosen.trim());
        if (!isFont(source)) {
            System.err.println(CoreLog.STANDARD_ERROR_PREFIX + "not a font file: " + source);
            return;
        }
        boolean tagged;
        try {
            tagged = holdsATag(source);
        } catch (IOException | RuntimeException e) {
            System.err.println(CoreLog.STANDARD_ERROR_PREFIX + "could not read " + source + ": " + e);
            return;
        }
        if (!tagged || !FontLibrary.looksLikeTrueType(source)) {
            System.err.println(CoreLog.STANDARD_ERROR_PREFIX + "not a TrueType font: "
                    + source);
            return;
        }
        Path copied;
        try {
            copied = copyIn(fontDirectory, source);
        } catch (IOException | RuntimeException e) {
            System.err.println(CoreLog.STANDARD_ERROR_PREFIX + "could not copy " + source + ": " + e);
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        refreshAndFind(library, fontAdded, copied, client, client::reloadResourcePacks, installed);
    }
    private static boolean holdsATag(Path file) throws IOException {
        byte[] tag = new byte[4];
        try (var in = Files.newInputStream(file)) {
            return in.readNBytes(tag, 0, tag.length) == tag.length;
        }
    }
    // A null filter still opens the dialog, just unfiltered.
    private static String open(Path fontDirectory) throws ReflectiveOperationException {
        Class<?> tinyfd = dialogs();
        if (tinyfd == null) {
            return null;
        }
        Class<?> pointerClass = Class.forName(POINTERS);
        Method openDialog = tinyfd.getMethod("tinyfd_openFileDialog",
                CharSequence.class, CharSequence.class, pointerClass,
                CharSequence.class, boolean.class);
        String start = fontDirectory.toAbsolutePath() + File.separator;
        // Stack must stay pushed for the whole call; the pattern buffer lives on it
        Object stack = null;
        Object filters = null;
        try {
            Class<?> stackClass = Class.forName(STACK);
            stack = stackClass.getMethod("stackPush").invoke(null);
            filters = filters(stackClass, pointerClass, stack);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError bare) {
            System.err.println(CoreLog.STANDARD_ERROR_PREFIX + "font picker has no file-type filter: " + bare);
            filters = null;
        }
        try {
            return (String) openDialog.invoke(null,
                    "Choose a font", start, filters, DESCRIPTION, false);
        } finally {
            pop(stack);
        }
    }
    private static Object filters(Class<?> stackClass, Class<?> pointerClass, Object stack)
            throws ReflectiveOperationException {
        Object buffer = stackClass.getMethod("mallocPointer", int.class)
                .invoke(stack, PATTERNS.size());
        Method utf8 = stackClass.getMethod("UTF8", CharSequence.class);
        Method put = pointerClass.getMethod("put", ByteBuffer.class);
        for (String pattern : PATTERNS) {
            put.invoke(buffer, utf8.invoke(stack, pattern));
        }
        pointerClass.getMethod("flip").invoke(buffer);
        return buffer;
    }
    private static void pop(Object stack) {
        if (stack instanceof AutoCloseable closing) {
            try {
                closing.close();
            } catch (Exception ignored) {
            }
        }
    }
    static boolean isFont(Path file) {
        Path name = file.getFileName();
        return name != null && FontLibrary.isFontFile(name.toString());
    }
    static Path copyIn(Path folder, Path source) throws IOException {
        Files.createDirectories(folder);
        String name = source.getFileName().toString();
        Path destination = folder.resolve(name);
        if (Files.exists(destination)) {
            if (sameFile(source, destination)) {
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                return destination;
            }
            destination = free(folder, name);
        }
        try {
            Files.copy(source, destination);
        } catch (FileAlreadyExistsException taken) {
            throw taken;
        } catch (IOException | RuntimeException failed) {
            try {
                Files.deleteIfExists(destination);
            } catch (IOException | RuntimeException leftover) {
                failed.addSuppressed(leftover);
            }
            throw failed;
        }
        return destination;
    }
    private static boolean sameFile(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException unreadable) {
            return false;
        }
    }
    private static Path free(Path folder, String name) {
        int dot = name.lastIndexOf('.');
        String stem = dot <= 0 ? name : name.substring(0, dot);
        String extension = dot <= 0 ? "" : name.substring(dot);
        for (int n = 2; n < 1000; n++) {
            Path candidate = folder.resolve(stem + "-" + n + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return folder.resolve(stem + "-" + System.currentTimeMillis() + extension);
    }
    // Reloads only if adopting the scan changes anything.
    static void refreshAndFind(FontLibrary library, Runnable fontAdded, Path copied,
            Executor client, Runnable reload, Consumer<String> installed) {
        Snapshot snapshot = library.scan();
        String found = null;
        for (Face face : snapshot.faces()) {
            if (sameFile(face.file(), copied)) {
                found = face.id();
                break;
            }
        }
        String id = found;
        client.execute(() -> {
            boolean arrived = library.adopt(snapshot);
            if (fontAdded != null) {
                fontAdded.run();
            }
            if (arrived) {
                reload.run();
            }
            if (id != null) {
                installed.accept(id);
            }
        });
    }
    // Opens the font folder in the file manager; creates it first if missing.
    public static void openFolder(Path folder) {
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            System.err.println(CoreLog.STANDARD_ERROR_PREFIX + "could not create " + folder + ": " + e);
            return;
        }
        Util.getPlatform().openPath(folder);
    }
}
