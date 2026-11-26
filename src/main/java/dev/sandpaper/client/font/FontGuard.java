package dev.sandpaper.client.font;

import dev.sandpaper.font.FontSafetyGate;
import dev.sandpaper.font.FontSafetyGate.Policy;
import dev.sandpaper.font.FontSafetyGate.Result;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Admits a TrueType face to FreeType. */
public final class FontGuard {
    static final String SWITCH = "sandpaper.fontGate";
    static final String SAFE_FONT = "/assets/sandpaper/font/sandpaper-safe.ttf";
    private static final Logger LOGGER = LoggerFactory.getLogger("sandpaper");

    interface Memory {
        ByteBuffer alloc(int bytes);

        void free(ByteBuffer buffer);
    }

    private static final class Lwjgl implements Memory {
        private final java.lang.invoke.MethodHandle allocate;
        private final java.lang.invoke.MethodHandle release;

        Lwjgl() {
            try {
                Class<?> memoryUtil = Class.forName("org.lwjgl.system.MemoryUtil");
                java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.publicLookup();
                allocate = lookup.findStatic(memoryUtil, "memAlloc",
                        java.lang.invoke.MethodType.methodType(ByteBuffer.class, int.class));
                java.lang.invoke.MethodHandle found;
                try {
                    found = lookup.findStatic(memoryUtil, "memFree",
                            java.lang.invoke.MethodType.methodType(void.class, ByteBuffer.class));
                } catch (NoSuchMethodException other) {
                    found = lookup.findStatic(memoryUtil, "memFree",
                            java.lang.invoke.MethodType.methodType(void.class, java.nio.Buffer.class));
                }
                release = found;
            } catch (ReflectiveOperationException missing) {
                throw new IllegalStateException("LWJGL memory is not available", missing);
            }
        }

        @Override
        public ByteBuffer alloc(int bytes) {
            try {
                return (ByteBuffer) allocate.invoke(bytes);
            } catch (Throwable failed) {
                throw new IllegalStateException("could not allocate " + bytes + " bytes", failed);
            }
        }

        @Override
        public void free(ByteBuffer buffer) {
            try {
                release.invoke(buffer);
            } catch (Throwable failed) {
                throw new IllegalStateException("could not free a font buffer", failed);
            }
        }
    }

    private static volatile byte[] safeFont;

    private FontGuard() {
    }

    private static volatile Memory lwjgl;

    private static Memory lwjgl() {
        Memory known = lwjgl;
        if (known == null) {
            known = new Lwjgl();
            lwjgl = known;
        }
        return known;
    }

    /** Returns the buffer FreeType may take. */
    public static ByteBuffer admit(ByteBuffer loaded) throws IOException {
        return admit(loaded, FontGuard::lwjgl, FontGuard::safeFont, System.getProperty(SWITCH, ""));
    }

    static ByteBuffer admit(ByteBuffer loaded, Memory memory, byte[] safe, String mode) throws IOException {
        return admit(loaded, () -> memory, () -> safe, mode);
    }

    static ByteBuffer admit(ByteBuffer loaded, Supplier<Memory> memories, Supplier<byte[]> safes, String mode)
            throws IOException {
        return admit(loaded, memories, safes, mode, FontSafetyGate::checkInPlace);
    }

    static ByteBuffer admit(ByteBuffer loaded, Supplier<Memory> memories, Supplier<byte[]> safes, String mode,
            BiFunction<ByteBuffer, Policy, Result> checker) throws IOException {
        boolean owned = true;
        Memory memory = null;
        ByteBuffer replacement = null;
        try {
            if ("off".equals(mode)) {
                return loaded;
            }
            Result result;
            try {
                result = checker.apply(loaded, Policy.STRIP_ALL_BYTECODE);
            } catch (RuntimeException unexpected) {
                result = new Result(FontSafetyGate.Verdict.MALFORMED,
                        "gate fault " + unexpected.getClass().getSimpleName(), null);
            }
            if (result.allowed()) {
                if (result.verdict() == FontSafetyGate.Verdict.OK_STRIPPED) {
                    LOGGER.info("Sandpaper removed the hinting programs of a font ({}).", result.detail());
                }
                return loaded;
            }
            memory = memories.get();
            byte[] safe = safes.get();
            owned = false;
            memory.free(loaded);
            if (safe == null) {
                throw new IOException("Sandpaper refused a font (" + result.verdict() + ": " + result.detail()
                        + ") and has no safe typeface to use instead");
            }
            LOGGER.warn("Sandpaper refused a font ({}: {}) and used its own safe typeface instead.",
                    result.verdict(), result.detail());
            replacement = memory.alloc(safe.length);
            replacement.put(0, safe);
            replacement.position(0).limit(safe.length);
            ByteBuffer handedOver = replacement;
            replacement = null;
            return handedOver;
        } catch (Throwable failed) {
            if (memory == null && owned) {
                try {
                    memory = memories.get();
                } catch (Throwable unresolved) {
                    failed.addSuppressed(unresolved);
                }
            }
            if (memory != null) {
                if (owned) {
                    freeQuietly(memory, loaded, failed);
                }
                if (replacement != null) {
                    freeQuietly(memory, replacement, failed);
                }
            }
            throw failed;
        }
    }

    private static void freeQuietly(Memory memory, ByteBuffer buffer, Throwable failed) {
        try {
            memory.free(buffer);
        } catch (Throwable alsoFailed) {
            failed.addSuppressed(alsoFailed);
        }
    }

    private static byte[] safeFont() {
        byte[] known = safeFont;
        if (known == null) {
            known = loadSafeFont(FontGuard.class.getResourceAsStream(SAFE_FONT));
            known = known == null ? new byte[0] : known;
            safeFont = known;
        }
        return known.length == 0 ? null : known;
    }

    static byte[] loadSafeFont(InputStream in) {
        if (in == null) {
            LOGGER.error("Sandpaper's safe typeface is missing from the mod.");
            return null;
        }
        try (in) {
            byte[] bytes = in.readNBytes(FontSafetyGate.MAX_FILE_BYTES);
            Result own = FontSafetyGate.check(bytes, Policy.REJECT_UNSAFE);
            if (own.verdict() != FontSafetyGate.Verdict.OK || !"no bytecode".equals(own.detail())) {
                LOGGER.error("Sandpaper's safe typeface failed its own check ({}).", own.verdict());
                return null;
            }
            return bytes;
        } catch (IOException unreadable) {
            LOGGER.error("Sandpaper's safe typeface could not be read.", unreadable);
            return null;
        }
    }
}
