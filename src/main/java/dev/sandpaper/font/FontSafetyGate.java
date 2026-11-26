package dev.sandpaper.font;

/** Checks a font's bytes before FreeType reads them. */
public final class FontSafetyGate {
    public enum Policy {
        /** Refuses faces with unproven bytecode. */
        REJECT_UNSAFE,
        /** Returns a copy with all bytecode removed. */
        STRIP_ALL_BYTECODE
    }

    public enum Verdict {
        OK, OK_STRIPPED, NOT_SFNT, TOO_LARGE, MALFORMED, VARIABLE_FONT, FORBIDDEN_OPCODE,
        UNVERIFIABLE_BYTECODE, PROGRAM_TOO_LARGE
    }

    /** sanitized is null unless the verdict is OK_STRIPPED. */
    public record Result(Verdict verdict, String detail, byte[] sanitized) {
        public boolean allowed() {
            return verdict == Verdict.OK || verdict == Verdict.OK_STRIPPED;
        }
    }

    public static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
    public static final int MAX_TABLES = 64;
    public static final int MAX_PROGRAM_BYTES = 256 * 1024;
    public static final int MAX_TOTAL_PROGRAM_BYTES = 8 * 1024 * 1024;
    public static final int MAX_COMPONENTS_PER_GLYPH = 1024;

    private static final int OP_JMPR = 0x1C;
    private static final int OP_FDEF_IDEF = 0x89;
    private static final int OP_SHZ_A = 0x36;
    private static final int OP_SHZ_B = 0x37;
    private static final int OP_JROT = 0x78;
    private static final int OP_JROF = 0x79;
    private static final int OP_NPUSHB = 0x40;
    private static final int OP_NPUSHW = 0x41;

    private static final class Bad extends Exception {
        private static final long serialVersionUID = 1L;
        private final Verdict verdict;
        Bad(Verdict verdict, String detail) {
            super(detail, null, false, false);
            this.verdict = verdict;
        }
    }

    private record Table(int tagValue, int offset, int length, int recordOffset) {
    }

    private final byte[] f;
    private final Policy policy;
    private Table[] tables;
    private int totalProgramBytes;
    private boolean sawBytecode;
    private final java.util.ArrayList<int[]> strips = new java.util.ArrayList<>();
    private final java.util.ArrayList<int[]> cuts = new java.util.ArrayList<>();
    private int[] flagEdits = new int[16];
    private int flagEditCount;
    private int glyphCount;
    private int locaFormat;
    private int glyfLength = -1;

    private FontSafetyGate(byte[] font, Policy policy) {
        this.f = font;
        this.policy = policy;
    }

    /** Checks a buffer and writes back a sanitized face. */
    public static Result checkInPlace(java.nio.ByteBuffer buffer, Policy policy) {
        int length = buffer.remaining();
        if (length > MAX_FILE_BYTES) {
            return new Result(Verdict.TOO_LARGE, "file " + length + " bytes", null);
        }
        byte[] copy;
        try {
            copy = new byte[length];
        } catch (OutOfMemoryError tooBig) {
            return new Result(Verdict.TOO_LARGE, "out of memory while copying the face", null);
        }
        buffer.get(buffer.position(), copy);
        Result result = check(copy, policy);
        if (result.sanitized() != null) {
            byte[] face = result.sanitized();
            int start = buffer.position();
            buffer.put(start, face);
            for (int i = face.length; i < length; i++) {
                buffer.put(start + i, (byte) 0);
            }
            buffer.limit(start + face.length);
        }
        return result;
    }

    public static Result check(byte[] font, Policy policy) {
        if (font.length > MAX_FILE_BYTES) {
            return new Result(Verdict.TOO_LARGE, "file " + font.length + " bytes", null);
        }
        FontSafetyGate gate = new FontSafetyGate(font, policy);
        try {
            Result result = gate.run();
            return result.sanitized() == null ? result : requireClean(result);
        } catch (OutOfMemoryError tooBig) {
            return new Result(Verdict.TOO_LARGE, "out of memory while checking", null);
        } catch (Bad bad) {
            return new Result(bad.verdict, bad.getMessage(), null);
        } catch (RuntimeException unexpected) {
            return new Result(Verdict.MALFORMED, "reader fault " + unexpected.getClass().getSimpleName(), null);
        }
    }

    /** Verifies the stripped face has no program left. */
    static Result requireClean(Result stripped) {
        Result again = check(stripped.sanitized(), Policy.REJECT_UNSAFE);
        if (again.verdict() != Verdict.OK || !"no bytecode".equals(again.detail())) {
            return new Result(Verdict.MALFORMED, "strip left a program or changed the face ("
                    + again.verdict() + ")", null);
        }
        return stripped;
    }

    private Result run() throws Bad {
        if (f.length < 12) {
            throw new Bad(Verdict.NOT_SFNT, "shorter than an sfnt header");
        }
        long sig = u32(0);
        if (sig != 0x00010000L && sig != 0x74727565L) {
            throw new Bad(Verdict.NOT_SFNT, "signature " + Long.toHexString(sig));
        }
        int count = u16(4);
        if (count == 0 || count > MAX_TABLES || 12L + count * 16L > f.length) {
            throw new Bad(Verdict.MALFORMED, "table count " + count);
        }
        tables = new Table[count];
        for (int i = 0; i < count; i++) {
            int rec = 12 + i * 16;
            long offset = u32(rec + 8);
            long length = u32(rec + 12);
            if (offset + length > f.length) {
                throw new Bad(Verdict.MALFORMED, "table " + tag(rec) + " outside the file");
            }
            for (int j = 0; j < i; j++) {
                if (tables[j].tagValue() == (int) u32(rec)) {
                    throw new Bad(Verdict.MALFORMED, "duplicate table " + tag(rec));
                }
            }
            tables[i] = new Table((int) u32(rec), (int) offset, (int) length, rec);
        }
        refuseOverlaps(count);
        for (String variation : new String[] {"fvar", "gvar", "avar", "cvar", "HVAR", "VVAR", "MVAR", "CFF2"}) {
            if (find(variation) != null) {
                throw new Bad(Verdict.VARIABLE_FONT, "table " + variation);
            }
        }
        requireCore(count);
        program("fpgm");
        program("prep");
        glyphPrograms();
        if (policy == Policy.STRIP_ALL_BYTECODE) {
            return new Result(Verdict.OK_STRIPPED, "canonical face, "
                    + (strips.size() + cuts.size() + flagEditCount) + " program(s) removed", stripped());
        }
        return new Result(Verdict.OK, sawBytecode ? "bytecode verified" : "no bytecode", null);
    }

    /** Refuses tables that overlap or start in the directory. */
    private void refuseOverlaps(int count) throws Bad {
        long directoryEnd = 12L + count * 16L;
        for (int i = 0; i < count; i++) {
            if (tables[i].length() == 0) {
                continue;
            }
            if (tables[i].offset() < directoryEnd) {
                throw new Bad(Verdict.MALFORMED, "table " + tag(tables[i].recordOffset()) + " starts inside the directory");
            }
            for (int j = i + 1; j < count; j++) {
                if (tables[j].length() == 0) {
                    continue;
                }
                long a0 = tables[i].offset();
                long a1 = a0 + tables[i].length();
                long b0 = tables[j].offset();
                long b1 = b0 + tables[j].length();
                if (a0 < b1 && b0 < a1) {
                    throw new Bad(Verdict.MALFORMED, "tables " + tag(tables[i].recordOffset()) + " and "
                            + tag(tables[j].recordOffset()) + " overlap");
                }
            }
        }
    }

    /** Checks the TrueType core tables are present and valid. */
    private void requireCore(int count) throws Bad {
        for (int i = 0; i < count; i++) {
            for (int k = 0; k < 4; k++) {
                int c = f[12 + 16 * i + k] & 0xFF;
                if (c < 0x20 || c > 0x7E) {
                    throw new Bad(Verdict.MALFORMED, "table tag with a non printable byte");
                }
            }
        }
        Table head = find("head");
        if (head == null || head.length() < 54 || u32(head.offset()) != 0x00010000L
                || u32(head.offset() + 12) != 0x5F0F3CF5L
                || u16(head.offset() + 18) < 16 || u16(head.offset() + 18) > 16384
                || u16(head.offset() + 52) != 0) {
            throw new Bad(Verdict.MALFORMED, "head is missing or not valid");
        }
        Table maxp = find("maxp");
        if (maxp == null || maxp.length() < 32 || u32(maxp.offset()) != 0x00010000L
                || u16(maxp.offset() + 4) == 0) {
            throw new Bad(Verdict.MALFORMED, "maxp is missing or not a TrueType maxp with glyphs");
        }
        Table hhea = find("hhea");
        if (hhea == null || hhea.length() < 36 || u32(hhea.offset()) != 0x00010000L) {
            throw new Bad(Verdict.MALFORMED, "hhea is missing or not valid");
        }
        int glyphs = u16(maxp.offset() + 4);
        int metrics = u16(hhea.offset() + 34);
        Table hmtx = find("hmtx");
        if (metrics < 1 || metrics > glyphs || hmtx == null
                || hmtx.length() < metrics * 4L + (glyphs - metrics) * 2L) {
            throw new Bad(Verdict.MALFORMED, "hmtx does not match hhea and maxp");
        }
        for (String name : new String[] {"cmap", "loca", "glyf"}) {
            Table table = find(name);
            if (table == null || table.length() == 0) {
                throw new Bad(Verdict.MALFORMED, name + " is missing or empty");
            }
        }
    }

    /** A table program: fpgm or prep. */
    private void program(String name) throws Bad {
        Table table = find(name);
        if (table == null || table.length() == 0) {
            return;
        }
        sawBytecode = true;
        if (policy == Policy.STRIP_ALL_BYTECODE) {
            strips.add(new int[] {table.recordOffset() + 12, 4, 0});
            return;
        }
        if (table.length() > MAX_PROGRAM_BYTES) {
            throw new Bad(Verdict.PROGRAM_TOO_LARGE, name + " " + table.length() + " bytes");
        }
        walk(table.offset(), table.length(), name);
    }

    private void glyphPrograms() throws Bad {
        Table glyf = find("glyf");
        Table loca = find("loca");
        Table maxp = find("maxp");
        Table head = find("head");
        if (glyf == null || loca == null || maxp == null || head == null) {
            throw new Bad(Verdict.MALFORMED, "a glyph table is missing");
        }
        if (maxp.length() < 6 || head.length() < 54) {
            throw new Bad(Verdict.MALFORMED, "maxp or head too short");
        }
        int glyphs = u16(maxp.offset() + 4);
        int format = (short) u16(head.offset() + 50);
        if (format != 0 && format != 1) {
            throw new Bad(Verdict.MALFORMED, "indexToLocFormat " + format);
        }
        glyphCount = glyphs;
        locaFormat = format;
        int entry = format == 0 ? 2 : 4;
        if ((long) (glyphs + 1) * entry > loca.length()) {
            throw new Bad(Verdict.MALFORMED, "loca shorter than glyph count");
        }
        for (int g = 0; g < glyphs; g++) {
            long a = locaAt(loca, g, format);
            long b = locaAt(loca, g + 1, format);
            if (b < a || b > glyf.length()) {
                throw new Bad(Verdict.MALFORMED, "loca entry " + g + " out of order");
            }
            if (b - a < 10) {
                continue;
            }
            glyph(glyf.offset() + (int) a, glyf.offset() + (int) b, g);
        }
    }

    private void glyph(int start, int end, int id) throws Bad {
        int contours = (short) u16(start);
        if (contours >= 0) {
            int lenAt = start + 10 + 2 * contours;
            if (lenAt + 2 > end) {
                if (contours == 0) {
                    return;
                }
                throw new Bad(Verdict.MALFORMED, "glyph " + id + " header");
            }
            int len = u16(lenAt);
            if (len == 0) {
                return;
            }
            sawBytecode = true;
            if (lenAt + 2 + len > end) {
                throw new Bad(Verdict.MALFORMED, "glyph " + id + " instructions past its end");
            }
            if (policy == Policy.STRIP_ALL_BYTECODE) {
                cuts.add(new int[] {id, lenAt + 2, lenAt + 2 + len, lenAt});
                return;
            }
            walk(lenAt + 2, len, "glyph " + id);
            return;
        }
        int at = start + 10;
        int flags;
        int components = 0;
        boolean hasInstructions = false;
        do {
            if (++components > MAX_COMPONENTS_PER_GLYPH) {
                throw new Bad(Verdict.MALFORMED, "composite " + id + " has too many components");
            }
            if (at + 4 > end) {
                throw new Bad(Verdict.MALFORMED, "composite " + id + " truncated");
            }
            flags = u16(at);
            at += 4 + ((flags & 0x0001) != 0 ? 4 : 2);
            if ((flags & 0x0008) != 0) {
                at += 2;
            } else if ((flags & 0x0040) != 0) {
                at += 4;
            } else if ((flags & 0x0080) != 0) {
                at += 8;
            }
            if (at > end) {
                throw new Bad(Verdict.MALFORMED, "composite " + id + " truncated");
            }
            if ((flags & 0x0100) != 0) {
                hasInstructions = true;
                if (policy == Policy.STRIP_ALL_BYTECODE) {
                    sawBytecode = true;
                    addFlagEdit(compFlagsAt(at, flags));
                }
            }
        } while ((flags & 0x0020) != 0);
        if (hasInstructions && policy == Policy.STRIP_ALL_BYTECODE) {
            cuts.add(new int[] {id, at, end, -1});
        }
        if (hasInstructions && policy == Policy.REJECT_UNSAFE) {
            if (at + 2 > end) {
                throw new Bad(Verdict.MALFORMED, "composite " + id + " instruction count");
            }
            int len = u16(at);
            if (at + 2 + len > end) {
                throw new Bad(Verdict.MALFORMED, "composite " + id + " instructions past its end");
            }
            sawBytecode = true;
            if (len > 0) {
                walk(at + 2, len, "composite " + id);
            }
        }
    }

    private void addFlagEdit(int at) {
        if (flagEditCount == flagEdits.length) {
            flagEdits = java.util.Arrays.copyOf(flagEdits, flagEdits.length * 2);
        }
        flagEdits[flagEditCount++] = at;
    }

    /** Offset of the flags field of a component. */
    private int compFlagsAt(int after, int flags) {
        int size = 4 + ((flags & 0x0001) != 0 ? 4 : 2);
        if ((flags & 0x0008) != 0) {
            size += 2;
        } else if ((flags & 0x0040) != 0) {
            size += 4;
        } else if ((flags & 0x0080) != 0) {
            size += 8;
        }
        return after - size;
    }

    /** Walks one program linearly, refusing SHZ and IDEF. */
    private void walk(int start, int length, String where) throws Bad {
        totalProgramBytes += length;
        if (totalProgramBytes > MAX_TOTAL_PROGRAM_BYTES) {
            throw new Bad(Verdict.PROGRAM_TOO_LARGE, "all programs together");
        }
        int i = start;
        int end = start + length;
        boolean jumps = false;
        while (i < end) {
            int op = f[i] & 0xFF;
            if (op == OP_SHZ_A || op == OP_SHZ_B) {
                throw new Bad(Verdict.FORBIDDEN_OPCODE, "SHZ in " + where + " at " + (i - start));
            }
            if (op == OP_FDEF_IDEF) {
                throw new Bad(Verdict.FORBIDDEN_OPCODE, "IDEF in " + where + " at " + (i - start));
            }
            if (op == OP_JMPR || op == OP_JROT || op == OP_JROF) {
                jumps = true;
            }
            if (op == OP_NPUSHB) {
                if (i + 1 >= end) {
                    throw new Bad(Verdict.MALFORMED, "NPUSHB cut off in " + where);
                }
                i += 2 + (f[i + 1] & 0xFF);
            } else if (op == OP_NPUSHW) {
                if (i + 1 >= end) {
                    throw new Bad(Verdict.MALFORMED, "NPUSHW cut off in " + where);
                }
                i += 2 + 2 * (f[i + 1] & 0xFF);
            } else if (op >= 0xB0 && op <= 0xB7) {
                i += 1 + (op - 0xB0 + 1);
            } else if (op >= 0xB8 && op <= 0xBF) {
                i += 1 + 2 * (op - 0xB8 + 1);
            } else {
                i++;
            }
            if (i > end) {
                throw new Bad(Verdict.MALFORMED, "push data past the end of " + where);
            }
        }
        if (jumps) {
            throw new Bad(Verdict.UNVERIFIABLE_BYTECODE, "jump in " + where);
        }
    }

    /** Returns the face with every program removed. */
    private byte[] stripped() throws Bad {
        byte[] copy = f.clone();
        for (int[] edit : strips) {
            for (int k = 0; k < edit[1]; k++) {
                copy[edit[0] + k] = 0;
            }
        }
        for (int i = 0; i < flagEditCount; i++) {
            int at = flagEdits[i];
            int flags = ((copy[at] & 0xFF) << 8) | (copy[at + 1] & 0xFF);
            flags &= ~0x0100;
            copy[at] = (byte) (flags >> 8);
            copy[at + 1] = (byte) flags;
        }
        for (int[] cut : cuts) {
            if (cut[3] >= 0) {
                copy[cut[3]] = 0;
                copy[cut[3] + 1] = 0;
            }
        }
        if (!cuts.isEmpty()) {
            rebuildGlyf(copy);
        }
        return canonical(copy);
    }

    /** Tables kept in the stripped face. */
    private static final String[] KEPT_TABLES = {"cmap", "glyf", "head", "hhea", "hmtx", "loca", "maxp"};

    /** Builds a new sfnt from the kept tables only. */
    private byte[] canonical(byte[] src) throws Bad {
        Table[] use = new Table[KEPT_TABLES.length];
        int[] lengths = new int[KEPT_TABLES.length];
        int count = 0;
        int size = 0;
        for (String name : KEPT_TABLES) {
            Table table = find(name);
            if (table == null || table.length() == 0) {
                if (name.equals("cmap")) {
                    continue;
                }
                throw new Bad(Verdict.MALFORMED, name + " is missing");
            }
            use[count] = table;
            lengths[count] = name.equals("glyf") && glyfLength >= 0 ? glyfLength : table.length();
            size += lengths[count];
            count++;
        }
        int directory = 12 + 16 * count;
        byte[] out = new byte[directory + size];
        out[1] = 1;
        out[4] = (byte) (count >> 8);
        out[5] = (byte) count;
        int entry = 1;
        while (entry * 2 <= count) {
            entry *= 2;
        }
        int search = entry * 16;
        out[6] = (byte) (search >> 8);
        out[7] = (byte) search;
        out[8] = 0;
        out[9] = (byte) (31 - Integer.numberOfLeadingZeros(entry));
        int shift = count * 16 - search;
        out[10] = (byte) (shift >> 8);
        out[11] = (byte) shift;
        int at = directory;
        for (int i = 0; i < count; i++) {
            int record = 12 + 16 * i;
            System.arraycopy(src, use[i].recordOffset(), out, record, 4);
            System.arraycopy(src, use[i].offset(), out, at, lengths[i]);
            int sum = 0;
            for (int k = 0; k < lengths[i]; k += 4) {
                int word = 0;
                for (int b = 0; b < 4; b++) {
                    word = (word << 8) | (k + b < lengths[i] ? out[at + k + b] & 0xFF : 0);
                }
                sum += word;
            }
            putInt(out, record + 4, sum);
            putInt(out, record + 8, at);
            putInt(out, record + 12, lengths[i]);
            at += lengths[i];
        }
        if (out.length > src.length) {
            throw new Bad(Verdict.MALFORMED, "canonical face is longer than the original");
        }
        return out;
    }

    private static void putInt(byte[] a, int at, int value) {
        a[at] = (byte) (value >>> 24);
        a[at + 1] = (byte) (value >>> 16);
        a[at + 2] = (byte) (value >>> 8);
        a[at + 3] = (byte) value;
    }

    private void rebuildGlyf(byte[] copy) throws Bad {
        Table glyf = find("glyf");
        Table loca = find("loca");
        byte[] out = new byte[glyf.length()];
        int[] starts = new int[glyphCount + 1];
        int pos = 0;
        int next = 0;
        for (int g = 0; g < glyphCount; g++) {
            starts[g] = pos;
            int from = glyf.offset() + (int) locaAt(loca, g, locaFormat);
            int to = glyf.offset() + (int) locaAt(loca, g + 1, locaFormat);
            if (next < cuts.size() && cuts.get(next)[0] == g) {
                int[] cut = cuts.get(next++);
                pos = copyRange(copy, from, cut[1], out, pos);
                pos = copyRange(copy, cut[2], to, out, pos);
                if (locaFormat == 0 && (pos & 1) != 0) {
                    pos++;
                }
            } else {
                pos = copyRange(copy, from, to, out, pos);
            }
        }
        starts[glyphCount] = pos;
        glyfLength = pos;
        if (pos > out.length) {
            throw new Bad(Verdict.MALFORMED, "rebuilt glyf is longer than the original");
        }
        System.arraycopy(out, 0, copy, glyf.offset(), out.length);
        for (int g = 0; g <= glyphCount; g++) {
            int at = loca.offset() + g * (locaFormat == 0 ? 2 : 4);
            if (locaFormat == 0) {
                copy[at] = (byte) (starts[g] >> 9);
                copy[at + 1] = (byte) (starts[g] >> 1);
            } else {
                copy[at] = (byte) (starts[g] >>> 24);
                copy[at + 1] = (byte) (starts[g] >>> 16);
                copy[at + 2] = (byte) (starts[g] >>> 8);
                copy[at + 3] = (byte) starts[g];
            }
        }
    }

    private static int copyRange(byte[] from, int start, int end, byte[] to, int at) throws Bad {
        int length = end - start;
        if (length < 0 || at + length > to.length) {
            throw new Bad(Verdict.MALFORMED, "glyph copy out of range");
        }
        System.arraycopy(from, start, to, at, length);
        return at + length;
    }

    private Table find(String name) {
        int want = ((name.charAt(0) & 0xFF) << 24) | ((name.charAt(1) & 0xFF) << 16)
                | ((name.charAt(2) & 0xFF) << 8) | (name.charAt(3) & 0xFF);
        for (Table table : tables) {
            if (table.tagValue() == want) {
                return table;
            }
        }
        return null;
    }

    private long locaAt(Table loca, int index, int format) {
        int at = loca.offset() + index * (format == 0 ? 2 : 4);
        return format == 0 ? u16(at) * 2L : u32(at);
    }

    private String tag(int rec) {
        return new String(f, rec, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private int u16(int at) {
        if (at < 0 || at + 2 > f.length) {
            throw new IndexOutOfBoundsException();
        }
        return ((f[at] & 0xFF) << 8) | (f[at + 1] & 0xFF);
    }

    private long u32(int at) {
        if (at < 0 || at + 4 > f.length) {
            throw new IndexOutOfBoundsException();
        }
        return ((long) (f[at] & 0xFF) << 24) | ((f[at + 1] & 0xFF) << 16)
                | ((f[at + 2] & 0xFF) << 8) | (f[at + 3] & 0xFF);
    }
}
