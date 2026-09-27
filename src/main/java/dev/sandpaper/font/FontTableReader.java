package dev.sandpaper.font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
final class FontTableReader {
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private record Table(int offset, int length) {
    }
    private FontTableReader() {
    }
    static Float autoSize(Path file) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return null;
            }
            return autoSize(Files.readAllBytes(file));
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
    private static Float autoSize(byte[] font) {
        if (font.length < 12) {
            return null;
        }
        int signature = int32(font, 0);
        boolean trueType = signature == 0x00010000 || signature == 0x74727565;
        if (!trueType && signature != 0x4f54544f) {
            return null;
        }
        int tableCount = unsigned16(font, 4);
        long recordsEnd = 12L + tableCount * 16L;
        if (recordsEnd > font.length) {
            return null;
        }
        Table head = table(font, tableCount, "head");
        if (head == null || head.length() < 52) {
            return null;
        }
        int unitsPerEm = unsigned16(font, head.offset() + 18);
        if (unitsPerEm == 0) {
            return null;
        }
        int capHeight = capHeight(font, table(font, tableCount, "OS/2"));
        if (capHeight <= 0 && trueType) {
            capHeight = hHeight(font, tableCount, head);
        }
        if (capHeight <= 0) {
            return null;
        }
        float size = 7f * unitsPerEm / capHeight;
        return size >= FontLibrary.MIN_AUTO_SIZE && size <= FontLibrary.MAX_AUTO_SIZE ? size : null;
    }
    private static int capHeight(byte[] font, Table os2) {
        if (os2 == null || os2.length() < 90 || unsigned16(font, os2.offset()) < 2) {
            return 0;
        }
        return signed16(font, os2.offset() + 88);
    }
    private static int hHeight(byte[] font, int tableCount, Table head) {
        Table maxp = table(font, tableCount, "maxp");
        Table loca = table(font, tableCount, "loca");
        Table glyf = table(font, tableCount, "glyf");
        Table cmap = table(font, tableCount, "cmap");
        if (maxp == null || maxp.length() < 6 || loca == null || glyf == null || cmap == null) {
            return 0;
        }
        int glyphCount = unsigned16(font, maxp.offset() + 4);
        int glyph = glyphForH(font, cmap);
        if (glyph <= 0 || glyph >= glyphCount) {
            return 0;
        }
        int format = signed16(font, head.offset() + 50);
        long first = locaOffset(font, loca, glyph, format);
        long last = locaOffset(font, loca, glyph + 1, format);
        if (first < 0 || last <= first || last > glyf.length()) {
            return 0;
        }
        long glyphStart = glyf.offset() + first;
        long glyphEnd = glyf.offset() + last;
        if (glyphEnd > font.length || glyphEnd - glyphStart < 10) {
            return 0;
        }
        return signed16(font, (int) glyphStart + 8);
    }
    private static long locaOffset(byte[] font, Table loca, int glyph, int format) {
        if (format == 0) {
            long position = loca.offset() + glyph * 2L;
            return position + 2 > (long) loca.offset() + loca.length()
                    ? -1 : unsigned16(font, (int) position) * 2L;
        }
        if (format == 1) {
            long position = loca.offset() + glyph * 4L;
            return position + 4 > (long) loca.offset() + loca.length()
                    ? -1 : unsigned32(font, (int) position);
        }
        return -1;
    }
    private static int glyphForH(byte[] font, Table cmap) {
        if (cmap.length() < 4) {
            return 0;
        }
        int records = unsigned16(font, cmap.offset() + 2);
        if (4L + records * 8L > cmap.length()) {
            return 0;
        }
        java.util.HashSet<Integer> scanned = new java.util.HashSet<>();
        for (int i = 0; i < records; i++) {
            int record = cmap.offset() + 4 + i * 8;
            int platform = unsigned16(font, record);
            int encoding = unsigned16(font, record + 2);
            long subtable = cmap.offset() + unsigned32(font, record + 4);
            if (platform != 0 && !(platform == 3 && (encoding == 1 || encoding == 10))) {
                continue;
            }
            if (subtable < cmap.offset() + cmap.length() && subtable <= Integer.MAX_VALUE
                    && scanned.add((int) subtable)) {
                int glyph = glyphForHSubtable(font, (int) subtable,
                        cmap.offset() + cmap.length());
                if (glyph != 0) {
                    return glyph;
                }
            }
        }
        return 0;
    }
    private static int glyphForHSubtable(byte[] font, int start, int limit) {
        if (start + 2 > limit) {
            return 0;
        }
        int format = unsigned16(font, start);
        if (format == 4) {
            return glyphForHFormat4(font, start, limit);
        }
        if (format == 12) {
            return glyphForHFormat12(font, start, limit);
        }
        return 0;
    }
    private static int glyphForHFormat4(byte[] font, int start, int limit) {
        if (start + 8 > limit) {
            return 0;
        }
        int length = unsigned16(font, start + 2);
        int end = start + length;
        int segments = unsigned16(font, start + 6) / 2;
        if (length < 16 || end < start || end > limit || segments == 0
                || start + 16L + segments * 8L > end) {
            return 0;
        }
        int endCodes = start + 14;
        int startCodes = endCodes + segments * 2 + 2;
        int deltas = startCodes + segments * 2;
        int ranges = deltas + segments * 2;
        for (int i = 0; i < segments; i++) {
            int segmentEnd = unsigned16(font, endCodes + i * 2);
            int segmentStart = unsigned16(font, startCodes + i * 2);
            if (segmentStart > 0x48 || segmentEnd < 0x48) {
                continue;
            }
            int delta = signed16(font, deltas + i * 2);
            int rangeOffset = unsigned16(font, ranges + i * 2);
            if (rangeOffset == 0) {
                return (0x48 + delta) & 0xffff;
            }
            long glyphAddress = ranges + i * 2L + rangeOffset
                    + (0x48 - segmentStart) * 2L;
            if (glyphAddress + 2 > end) {
                return 0;
            }
            int glyph = unsigned16(font, (int) glyphAddress);
            return glyph == 0 ? 0 : (glyph + delta) & 0xffff;
        }
        return 0;
    }
    private static int glyphForHFormat12(byte[] font, int start, int limit) {
        if (start + 16 > limit) {
            return 0;
        }
        long end = start + unsigned32(font, start + 4);
        long groups = unsigned32(font, start + 12);
        if (end > limit || groups > Integer.MAX_VALUE || start + 16L + groups * 12L > end) {
            return 0;
        }
        for (int i = 0; i < groups; i++) {
            int group = start + 16 + i * 12;
            long first = unsigned32(font, group);
            long last = unsigned32(font, group + 4);
            if (first <= 0x48 && last >= 0x48) {
                long glyph = unsigned32(font, group + 8) + 0x48 - first;
                return glyph > 0 && glyph <= 0xffff ? (int) glyph : 0;
            }
        }
        return 0;
    }
    private static Table table(byte[] font, int count, String wanted) {
        for (int i = 0; i < count; i++) {
            int record = 12 + i * 16;
            if (wanted.equals(new String(font, record, 4, java.nio.charset.StandardCharsets.US_ASCII))) {
                long offset = unsigned32(font, record + 8);
                long length = unsigned32(font, record + 12);
                if (offset <= Integer.MAX_VALUE && length <= Integer.MAX_VALUE
                        && offset + length <= font.length) {
                    return new Table((int) offset, (int) length);
                }
                return null;
            }
        }
        return null;
    }
    private static int unsigned16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }
    private static int signed16(byte[] data, int offset) {
        return (short) unsigned16(data, offset);
    }
    private static long unsigned32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xff) << 24)
                | ((long) (data[offset + 1] & 0xff) << 16)
                | ((long) (data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xffL);
    }
    private static int int32(byte[] data, int offset) {
        return (int) unsigned32(data, offset);
    }
}
