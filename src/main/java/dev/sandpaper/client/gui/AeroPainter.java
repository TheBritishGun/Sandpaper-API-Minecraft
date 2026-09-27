package dev.sandpaper.client.gui;
import dev.sandpaper.client.gui.options.Opt;
import dev.sandpaper.client.gui.options.Opt.Kind;
import dev.sandpaper.client.gui.options.OptPage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
public final class AeroPainter {
    private static Supplier<AeroTheme.Treatment> treatmentSupplier =
            () -> AeroTheme.Treatment.AERO;
    private static final int KNOB_ON_TOP = 0xFFF2FEFF;
    private static final int KNOB_ON_BOTTOM = 0xFFB9DDE4;
    private static final int KNOB_OFF_TOP = 0xFF8FA0A6;
    private static final int KNOB_OFF_BOTTOM = 0xFF5F6E74;
    public static final int HEADER_H = 24;
    public static final int FOOTER_MIN = 42;
    public static final int FOOTER_MAX = 74;
    public static final int MARGIN = 6;
    public static final int LIST_W = 104;
    public static final int ROW_H = 18;
    public static final int GROUP_H = 15;
    public static final int PAGE_ROW_H = 16;
    public static final int CONTROL_W = 58;
    public static final int FIELD_W = 128;
    public static final int GUTTER = 8;
    // Narrowest an option name shrinks to before a cycling control stops growing
    public static final int NAME_MIN = 64;
    // What a shortened value ends in, to show it was cut
    public static final String CUT = "...";
    public static final int SECTION_H = 27;
    public static final int SECTION_GAP = 6;
    public static final int GROUP_GAP = 3;
    // Reserved whether or not the scrollbar shows
    public static final int SCROLLBAR_OFFSET = 5;
    public static final int SCROLLBAR_WIDTH = 7;
    // Below FULL_BLEED the menu fills the viewport; above FLOATING it holds at MENU_MAX
    public static final int MENU_MIN = 505;
    public static final int MENU_MAX = 555;
    public static final int FULL_BLEED = 605;
    public static final int FLOATING = 655;
    private static final long HOVER_NANOS = 110_000_000L;
    private static final long PAGE_NANOS = 165_000_000L;
    private static final int PAGE_ROW_PITCH = PAGE_ROW_H + 1;
    // The highlighted section is read 3 rows below the top, capped at half the view
    public static final int FOCUS_LEAD_ROWS = 3;
    public static final class Entry {
        final OptPage.Group group;
        final Opt option;
        final Kind kind;
        final String header;
        final int section;
        final boolean sectionBreak;
        String name;
        int y;
        int h;
        float hover;
        Entry(OptPage.Group group, Opt option, Kind kind, String header,
                int section, boolean sectionBreak) {
            this.group = group;
            this.option = option;
            this.kind = kind;
            this.header = header;
            this.section = section;
            this.sectionBreak = sectionBreak;
        }
        public boolean isHeader() {
            return option == null;
        }
        public boolean isSection() {
            return sectionBreak;
        }
        public int section() {
            return section;
        }
        public String header() {
            return header;
        }
        public int y() {
            return y;
        }
        public int h() {
            return h;
        }
        public Opt option() {
            return option;
        }
    }
    private static final class Hover {
        float value;
        Hover(float value) {
            this.value = value;
        }
    }
    private String title;
    private final List<OptPage> pages;
    private final Map<Opt, Hover> hovers = new IdentityHashMap<>();
    private List<Entry> hoverEntries = new ArrayList<>();
    private List<Entry> hoverNext = new ArrayList<>();
    private static final int UPPER_HEADERS_MAX = 128;
    private final Map<String, String> upperHeaders = new HashMap<>();
    private final int[] countLabelKeys = {-1, 0, 0, 0, 0, 0, 0, 0};
    private final String[] countLabelValues = new String[8];
    // Caches the last measured width per option; read by click()
    private final Map<Opt, Integer> enumWidths = new IdentityHashMap<>();
    private final Map<Opt, EnumLabelWidth> enumLabelWidths = new IdentityHashMap<>();
    private record EnumLabelWidth(long revision, int width) { }
    private static final int ROW_NAMES_MAX = 256;
    private final Map<Opt, String> rowNames = new IdentityHashMap<>();
    private int lastChevronRight;
    private final float[] pageHover;
    private int width;
    private int height;
    private int geometryMenuW;
    private int geometryMenuX;
    private int geometryMenuRight;
    private int geometryContentX;
    private int geometryContentRight;
    private int geometryRowRight;
    private int geometryBarX;
    private int geometryFooterHeight;
    private int geometryContentBottom;
    private int geometryViewHeight;
    private int geometryControlX;
    private int geometryValueRight;
    private int geometryFieldX;
    private int geometryNameX;
    private int geometryEnumRoom;
    private int geometryValueRoom;
    private int geometrySearchX;
    private int geometrySearchRight;
    private int scroll;
    private String query = "";
    private List<Entry> entries = List.of();
    private boolean pendingRebuild;
    private int contentHeight;
    private String emptyMessage = "";
    // Rebuilt together with the list
    private int[] sectionY;
    private int[] sectionEnd;
    private String[] pageNames;
    // Counts are one frame behind the current search
    private int[] matchCounts;
    // Recomputed from scroll on every scroll change; only a name click writes it directly
    private int current;
    private Entry hovered;
    private Entry focused;
    private int focusZone = 1;
    private int hoveredPage = -1;
    private boolean draggingSlider;
    private Opt dragOption;
    private boolean draggingBar;
    private int barGrab;
    private boolean searchFocused;
    private String editing = "";
    private int caret;
    private int editScroll;
    private Opt editingOption;
    private String caretText;
    private int caretIndex = -1;
    private int caretWidth;
    private float pagePhase = 1.0f;
    private long lastNanos;
    private boolean refusalCacheActive;
    private boolean refusalCached;
    private Opt refusalOption;
    private String refusalText;
    private String refusalResult;
    // Caches the footer's last wrap; footerLines owns what it holds
    private String wrapText;
    private int wrapWidth;
    private int wrapLines;
    private Object wrapFace;
    private List<String> wrapped = List.of();
    private boolean footerBodyCached;
    private Opt footerDescribedOption;
    private OptPage.Group footerDescribedGroup;
    private Opt footerEditingOption;
    private String footerEditingText;
    private String footerRefusal;
    private String footerBody;
    private Consumer<Opt.Action> buttonAction = opt -> { };
    private Runnable applyAction = () -> { };
    private Runnable doneAction = () -> { };
    public AeroPainter(String title, List<OptPage> pages) {
        this.title = title;
        this.pages = List.copyOf(pages);
        this.pageHover = new float[this.pages.size()];
        geometry();
        rebuild();
    }
    public static void setTreatmentSupplier(Supplier<AeroTheme.Treatment> supplier) {
        treatmentSupplier = Objects.requireNonNull(supplier);
    }
    // The treatment the next frame draws in; read once and held for the whole frame
    public static AeroTheme.Treatment treatment() {
        return treatmentSupplier.get();
    }
    public void onButton(Consumer<Opt.Action> action) {
        this.buttonAction = action;
    }
    public void onApply(Runnable action) {
        this.applyAction = action;
    }
    public void onDone(Runnable action) {
        this.doneAction = action;
    }
    public void refreshTitle(net.minecraft.network.chat.Component title) {
        this.title = plain(title);
    }
    public void layout(int width, int height) {
        this.width = width;
        this.height = height;
        geometry();
        footerBodyCached = false;
        enumLabelWidths.clear();
        enumWidths.clear();
        upperHeaders.clear();
        rowNames.clear();
        // Do not guard this on width or height; rebuild depends on values and query, not size
        rebuild();
    }
    public void layoutFrame(int width, int height) {
        if (width != this.width || height != this.height) {
            this.width = width;
            this.height = height;
            geometry();
        }
        if (!query.isEmpty()) {
            rebuild();
            return;
        }
        sync();
        clampScroll();
        current = settle(current);
    }
    public int pageCount() {
        return pages.size();
    }
    public int page() {
        sync();
        return current;
    }
    public int scrollOffset() {
        sync();
        return scroll;
    }
    public int contentHeight() {
        sync();
        return contentHeight;
    }
    public int maxScroll() {
        sync();
        return Math.max(0, contentHeight - viewHeight());
    }
    public List<Entry> entries() {
        sync();
        return entries;
    }
    public Entry focused() {
        sync();
        return focused;
    }
    public String query() {
        return query;
    }
    public boolean searchFocused() {
        return searchFocused;
    }
    public String editingText() {
        return editing;
    }
    public Opt editingOption() {
        return editingOption;
    }
    private static String plain(net.minecraft.network.chat.Component component) {
        return component == null ? "" : component.getString();
    }
    private static String valueText(Opt option) {
        return option.format();
    }
    // Rows of a section the search kept; -1 while the search box is empty
    public int matchCount(int index) {
        sync();
        if (query.isEmpty()) {
            return -1;
        }
        return matchCounts[index];
    }
    // One list for every section; a search that empties a section leaves no entry for it
    private void rebuild() {
        pendingRebuild = false;
        int capacity = 0;
        for (OptPage category : pages) {
            capacity++;
            for (OptPage.Group group : category.groups()) {
                capacity += 1 + group.options().size();
            }
        }
        List<Entry> built = new ArrayList<>(capacity);
        List<Entry> moving = new ArrayList<>();
        int[] starts = new int[pages.size()];
        int[] ends = new int[pages.size()];
        String[] names = new String[pages.size()];
        // Read by the left column
        int[] kept = new int[pages.size()];
        int y = 0;
        String q = query.toLowerCase(Locale.ROOT);
        boolean searching = !q.isEmpty();
        for (int p = 0; p < pages.size(); p++) {
            OptPage category = pages.get(p);
            int sectionTop = y + (built.isEmpty() ? 0 : SECTION_GAP);
            int cursor = sectionTop + SECTION_H;
            boolean pageHasContent = false;
            for (OptPage.Group group : category.groups()) {
                String groupName = null;
                boolean groupHit = false;
                // Flattened once per group, not per row
                if (searching) {
                    groupName = plain(group.name());
                    groupHit = groupName.toLowerCase(Locale.ROOT).contains(q);
                }
                boolean headerBuilt = false;
                for (Opt option : group.options()) {
                    String optionName = null;
                    if (searching && !groupHit) {
                        optionName = plain(option.name());
                        if (!optionName.toLowerCase(Locale.ROOT).contains(q)
                                && !valueText(option).toLowerCase(Locale.ROOT).contains(q)) {
                            continue;
                        }
                    }
                    if (!headerBuilt) {
                        if (groupName == null) {
                            groupName = plain(group.name());
                        }
                        if (pageHasContent) {
                            cursor += GROUP_GAP;
                        } else {
                            names[p] = plain(category.name());
                            Entry brk = new Entry(null, null, Kind.PLAIN,
                                    names[p], p, true);
                            brk.y = sectionTop;
                            brk.h = SECTION_H;
                            built.add(brk);
                            pageHasContent = true;
                        }
                        Entry header = new Entry(group, null, Kind.PLAIN, groupName,
                                p, false);
                        header.y = cursor;
                        header.h = GROUP_H;
                        cursor += GROUP_H;
                        built.add(header);
                        headerBuilt = true;
                    }
                    kept[p]++;
                    Entry row = new Entry(group, option, option.kind(),
                            groupName, p, false);
                    row.y = cursor;
                    row.h = ROW_H;
                    row.name = optionName;
                    Hover hover = hovers.get(option);
                    row.hover = hover == null ? 0.0f : hover.value;
                    if (row.hover != 0.0f) {
                        moving.add(row);
                    }
                    cursor += ROW_H;
                    built.add(row);
                }
            }
            if (!pageHasContent) {
                starts[p] = -1;
                ends[p] = -1;
                continue;
            }
            starts[p] = sectionTop;
            y = cursor;
        }
        // Every offset belongs to exactly one section
        int tail = -1;
        for (int p = pages.size() - 1; p >= 0; p--) {
            if (starts[p] < 0) {
                continue;
            }
            ends[p] = tail < 0 ? y : tail;
            tail = starts[p];
        }
        this.entries = Collections.unmodifiableList(built);
        this.sectionY = starts;
        this.sectionEnd = ends;
        this.pageNames = names;
        hoverEntries.clear();
        hoverEntries.addAll(moving);
        hoverNext.clear();
        this.matchCounts = kept;
        this.contentHeight = y;
        this.emptyMessage = built.isEmpty() ? "Nothing matches \"" + query + "\"." : "";
        clampScroll();
        // Tracked by option identity
        focused = focused == null ? null : rowFor(built, focused.option);
        current = settle(current);
    }
    private void queryChanged() {
        if (query.isEmpty()) {
            rebuild();
            return;
        }
        pendingRebuild = true;
    }
    private void sync() {
        if (pendingRebuild) {
            rebuild();
        }
    }
    // Returns null for a null option
    private static Entry rowFor(List<Entry> built, Opt option) {
        if (option == null) {
            return null;
        }
        for (Entry row : built) {
            if (row.option == option) {
                return row;
            }
        }
        return null;
    }
    // Resolves the highlight against the current list; never an emptied section
    private int settle(int wanted) {
        if (entries.isEmpty()) {
            return -1;
        }
        if (wanted >= 0 && wanted < sectionY.length && sectionY[wanted] >= 0) {
            return wanted;
        }
        return sectionAt(scroll);
    }
    public int focusLine(int offset) {
        return offset + Math.min(ROW_H * FOCUS_LEAD_ROWS, viewHeight() / 2);
    }
    // Total: every offset maps to a section, with no separate fallback
    public int sectionAt(int offset) {
        sync();
        int probe = focusLine(offset);
        int lastShown = -1;
        for (int i = 0; i < sectionY.length; i++) {
            if (sectionY[i] < 0) {
                continue;
            }
            lastShown = i;
            if (probe >= sectionY[i] && probe < sectionEnd[i]) {
                return i;
            }
        }
        return lastShown < 0 ? firstShown()
                : probe < 0 ? firstShown() : lastShown;
    }
    private int firstShown() {
        return entries.isEmpty() ? -1 : entries.get(0).section;
    }
    public int sectionStart(int index) {
        sync();
        if (index < 0 || index >= sectionY.length) {
            return 0;
        }
        if (sectionY[index] < 0) {
            throw new IllegalStateException("section " + index + " is hidden");
        }
        return sectionY[index];
    }
    public boolean sectionShown(int index) {
        sync();
        return index >= 0 && index < sectionY.length && sectionY[index] >= 0;
    }
    // Skips sections a search emptied
    private int nextShown(int from, int by) {
        int n = pages.size();
        for (int step = 1; step <= n; step++) {
            int candidate = Math.floorMod(from + by * step, n);
            if (sectionShown(candidate)) {
                return candidate;
            }
        }
        return from;
    }
    private void clampScroll() {
        scroll = clampOffset(scroll, maxScroll());
    }
    private static int clampOffset(int offset, int max) {
        return Math.max(0, Math.min(offset, max));
    }
    private void geometry() {
        geometryMenuW = width <= FULL_BLEED ? width
                : width >= FLOATING ? MENU_MAX
                : MENU_MIN + (int) ((float) (width - FULL_BLEED)
                        / (FLOATING - FULL_BLEED) * (MENU_MAX - MENU_MIN));
        geometryMenuX = menuX(geometryMenuW);
        geometryMenuRight = geometryMenuX + geometryMenuW;
        geometryContentX = geometryMenuX + MARGIN + LIST_W + MARGIN;
        geometryContentRight = geometryMenuRight - MARGIN;
        geometryRowRight = geometryContentRight - SCROLLBAR_OFFSET - SCROLLBAR_WIDTH;
        geometryBarX = geometryContentRight - 2 - SCROLLBAR_WIDTH;
        geometryFooterHeight = Math.max(FOOTER_MIN, Math.min(FOOTER_MAX, height / 9));
        geometryContentBottom = height - MARGIN - geometryFooterHeight - MARGIN;
        geometryViewHeight = Math.max(ROW_H, geometryContentBottom - contentTop() - 5);
        geometryControlX = geometryRowRight - GUTTER - CONTROL_W;
        geometryValueRight = geometryControlX - GUTTER;
        geometryFieldX = geometryRowRight - GUTTER - FIELD_W;
        geometryNameX = geometryContentX + 4 + 8;
        geometryEnumRoom = Math.max(CONTROL_W,
                geometryRowRight - GUTTER - (geometryNameX + NAME_MIN + GUTTER));
        geometryValueRoom = Math.max(0,
                geometryValueRight - (geometryNameX + NAME_MIN + GUTTER));
        geometrySearchX = Math.max(geometryContentX, geometryMenuX + geometryMenuW / 2);
        geometrySearchRight = geometryMenuRight - MARGIN - 2;
    }
    public int menuW() {
        return geometryMenuW;
    }
    public int menuX() {
        return geometryMenuX;
    }
    private int menuX(int w) {
        return (width - w) / 2;
    }
    public int menuRight() {
        return geometryMenuRight;
    }
    public int contentX() {
        return geometryContentX;
    }
    public int contentRight() {
        return geometryContentRight;
    }
    // Pane width minus the scrollbar's reserved gutter
    public int rowRight() {
        return geometryRowRight;
    }
    public int barX() {
        return geometryBarX;
    }
    public int contentTop() {
        return MARGIN + HEADER_H;
    }
    public int footerHeight() {
        return geometryFooterHeight;
    }
    public int contentBottom() {
        return geometryContentBottom;
    }
    // Panel height minus 3px top margin and 2px bottom clip
    public int viewHeight() {
        return geometryViewHeight;
    }
    public int controlX() {
        return geometryControlX;
    }
    public int valueRight() {
        return geometryValueRight;
    }
    public int fieldX() {
        return geometryFieldX;
    }
    // Where a row's name starts; other widths are measured from this same point
    public int nameX() {
        return geometryNameX;
    }
    // Padding at each end of a cycling control, sized for the chosen font's glyph width
    public int chevronRoom(AeroSurface s) {
        int left = s.textWidth("<");
        int right = s.textWidth(">");
        lastChevronRight = right;
        return 4 + Math.max(left, right);
    }
    // Max width for a cycling control; never narrower than CONTROL_W
    public int enumRoom() {
        return geometryEnumRoom;
    }
    // Width of a cycling control, sized to its widest choice
    public int enumWidth(AeroSurface s, Entry e) {
        return enumWidthCached(s, e, chevronRoom(s));
    }
    private int enumWidthCached(AeroSurface s, Entry e, int chevronRoom) {
        int widest = 0;
        if (e != null && e.option instanceof Opt.Cycle<?> cycle) {
            long revision = cycle.labelRevision();
            EnumLabelWidth cached = enumLabelWidths.get(e.option);
            if (cached == null || cached.revision() != revision) {
                for (int i = 0; i < cycle.count(); i++) {
                    widest = Math.max(widest, s.textWidth(plain(cycle.labelAt(i))));
                }
                enumLabelWidths.put(e.option, new EnumLabelWidth(revision, widest));
            } else {
                widest = cached.width();
            }
        }
        int want = e != null && e.option instanceof Opt.Cycle<?> ? widest + chevronRoom * 2
                : CONTROL_W;
        return Math.max(CONTROL_W, Math.min(enumRoom(), want));
    }
    public int enumX(AeroSurface s, Entry e) {
        return enumLeft(enumWidth(s, e));
    }
    private int enumLeft(int width) {
        return rowRight() - GUTTER - width;
    }
    public int valueRoom() {
        return geometryValueRoom;
    }
    // Takes an already formatted value; not formatted again here
    private Fitted valueInColumn(AeroSurface s, String value) {
        return fitMeasured(s, value, valueRoom());
    }
    // Shortens text to fit room; ends in three dots
    public String fit(AeroSurface s, String text, int room) {
        return fitMeasured(s, text, room).text();
    }
    private record Fitted(String text, int width) {
    }
    private Fitted fitMeasured(AeroSurface s, String text, int room) {
        if (text == null || text.isEmpty() || room <= 0) {
            return new Fitted("", 0);
        }
        int width = s.textWidth(text);
        if (width <= room) {
            return new Fitted(text, width);
        }
        return switch (shaveRoute(text)) {
            case 1 -> shaveLinear(s, text, room);
            case 2 -> shaveCodePointSearch(s, text, room);
            default -> shaveSearch(s, text, room);
        };
    }
    private static int shaveRoute(String text) {
        boolean surrogate = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00A7') {
                return 1;
            }
            if (Character.isSurrogate(c)) {
                surrogate = true;
            }
        }
        return surrogate ? 2 : 0;
    }
    private Fitted shaveLinear(AeroSurface s, String text, int room) {
        return shave(s, text, room, CUT);
    }
    private Fitted shave(AeroSurface s, String kept, int room, String mark) {
        String candidate = kept.isEmpty() ? "" : kept + mark;
        int width = kept.isEmpty() ? 0 : s.textWidth(candidate);
        while (!kept.isEmpty() && width > room) {
            kept = kept.substring(0, formatUnitStart(kept));
            candidate = kept.isEmpty() ? "" : kept + mark;
            width = kept.isEmpty() ? 0 : s.textWidth(candidate);
        }
        // Returns empty rather than a bare cut mark when there is no room
        return hasVisibleText(kept, kept.length()) ? new Fitted(candidate, width) : new Fitted("", 0);
    }
    private static boolean hasVisibleText(String text, int end) {
        for (int at = 0; at < end; at++) {
            char c = text.charAt(at);
            if (c == '\u00A7') {
                if (at + 1 < end) {
                    at++;
                }
            } else if (!Character.isWhitespace(c)) {
                return true;
            }
        }
        return false;
    }
    private Fitted shaveCodePointSearch(AeroSurface s, String text, int room) {
        int low = 0;
        int high = text.codePointCount(0, text.length());
        String candidate = "";
        int width = 0;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            String attempt = text.substring(0, text.offsetByCodePoints(0, mid)) + CUT;
            int attemptWidth = s.textWidth(attempt);
            if (attemptWidth <= room) {
                low = mid;
                candidate = attempt;
                width = attemptWidth;
            } else {
                high = mid - 1;
            }
        }
        return candidate.isEmpty() || !hasVisibleText(candidate, candidate.length() - CUT.length())
                ? new Fitted("", 0) : new Fitted(candidate, width);
    }
    private Fitted shaveSearch(AeroSurface s, String text, int room) {
        int length = text.length();
        int failing = length;
        int step = 1;
        int n = length - step;
        String fitCandidate = "";
        int fitWidth = 0;
        while (n > 0) {
            String candidate = text.substring(0, n) + CUT;
            int candidateWidth = s.textWidth(candidate);
            if (candidateWidth <= room) {
                fitCandidate = candidate;
                fitWidth = candidateWidth;
                break;
            }
            failing = n;
            step *= 2;
            n = length - step;
        }
        int low = fitCandidate.isEmpty() ? 0 : n;
        int high = failing;
        while (high - low > 1) {
            int mid = (low + high) >>> 1;
            String candidate = text.substring(0, mid) + CUT;
            int candidateWidth = s.textWidth(candidate);
            if (candidateWidth <= room) {
                low = mid;
                fitCandidate = candidate;
                fitWidth = candidateWidth;
            } else {
                high = mid;
            }
        }
        return fitCandidate.isEmpty() || !hasVisibleText(fitCandidate, fitCandidate.length() - CUT.length())
                ? new Fitted("", 0) : new Fitted(fitCandidate, fitWidth);
    }
    public int searchX() {
        return geometrySearchX;
    }
    public int searchRight() {
        return geometrySearchRight;
    }
    private int searchTop() {
        return MARGIN;
    }
    private int searchHeight() {
        return HEADER_H - 4;
    }
    // Last row needs no trailing gap below it
    public int pageRows() {
        int room = contentBottom() - contentTop() - 8;
        if (room < PAGE_ROW_H) {
            return 0;
        }
        return Math.min(pages.size(), (room - PAGE_ROW_H) / PAGE_ROW_PITCH + 1);
    }
    // A function of current and the row count; keeps no offset of its own
    public int pageTop(int rows) {
        sync();
        if (rows <= 0 || pages.size() <= rows) {
            return 0;
        }
        return Math.max(0, Math.min(pages.size() - rows, current - rows / 2));
    }
    private int pageRowTop(int slot) {
        return contentTop() + 4 + slot * PAGE_ROW_PITCH;
    }
    // The only place list coords become screen coords; paint and hit test both use it
    public int listOrigin() {
        sync();
        int slide = Math.round((1.0f - AeroTheme.easeOut(pagePhase)) * 7.0f);
        return contentTop() + 3 - scroll + slide;
    }
    public int rowTop(Entry e) {
        sync();
        return listOrigin() + e.y;
    }
    // Read once per frame in paint() below; every painted colour comes from this, not a constant
    private AeroTheme.Palette palette = AeroTheme.colours();
    public void paint(AeroSurface.Backdrop s, int mouseX, int mouseY, long nowNanos) {
        sync();
        AeroTheme.Treatment treatment = treatment();
        palette = AeroTheme.colours(treatment);
        refusalCacheActive = true;
        refusalCached = false;
        advance(nowNanos, mouseX, mouseY);
        AeroTheme.backdrop(s);
        paintHeader(s, mouseX, mouseY, treatment);
        paintPageList(s, treatment);
        paintContent(s, mouseX, mouseY, treatment);
        paintFooter(s, mouseX, mouseY, treatment);
        refusalCacheActive = false;
    }
    private void advance(long nowNanos, int mouseX, int mouseY) {
        long delta = lastNanos == 0 ? 16_000_000L : Math.max(0, nowNanos - lastNanos);
        lastNanos = nowNanos;
        float hoverStep = (float) delta / HOVER_NANOS;
        float pageStep = (float) delta / PAGE_NANOS;
        if (pagePhase < 1.0f) {
            pagePhase = AeroTheme.clamp01(pagePhase + pageStep);
        }
        Entry underPointer = entryUnderPointer(mouseX, mouseY);
        hovered = underPointer != null && !underPointer.isHeader() ? underPointer
                : underPointer != null && !underPointer.isSection() ? underPointer : null;
        hoveredPage = pageAt(mouseX, mouseY);
        if (hovered != null && hovered.option != null) {
            float was = hovered.hover;
            hovered.hover = approach(was, 1.0f, hoverStep);
            if (hovered.hover != was) {
                rememberHover(hovered);
            }
        }
        hoverNext.clear();
        if (hovered != null && hovered.hover != 0.0f) {
            hoverNext.add(hovered);
        }
        for (Entry e : hoverEntries) {
            if (e == hovered) {
                continue;
            }
            float target = 0.0f;
            float was = e.hover;
            e.hover = approach(was, target, hoverStep);
            // Compares the value before and after, not against zero
            if (e.option != null && e.hover != was) {
                rememberHover(e);
            }
            if (e.hover != 0.0f) {
                hoverNext.add(e);
            }
        }
        List<Entry> swapped = hoverEntries;
        hoverEntries = hoverNext;
        hoverNext = swapped;
        hoverNext.clear();
        for (int i = 0; i < pageHover.length; i++) {
            pageHover[i] = approach(pageHover[i], i == hoveredPage ? 1.0f : 0.0f, hoverStep);
        }
    }
    private static float approach(float current, float target, float step) {
        if (current < target) {
            return Math.min(target, current + step);
        }
        return Math.max(target, current - step);
    }
    private void rememberHover(Entry e) {
        Hover hover = hovers.get(e.option);
        if (hover == null) {
            hovers.put(e.option, new Hover(e.hover));
        } else {
            hover.value = e.hover;
        }
    }
    private void paintHeader(AeroSurface s, int mouseX, int mouseY, AeroTheme.Treatment treatment) {
        int y = searchTop();
        int h = searchHeight();
        s.text(title, menuX() + MARGIN + 6, y + (h - s.lineHeight()) / 2 + 1,
                palette.text, true);
        int sx = searchX();
        int sw = searchRight() - sx;
        boolean over = inside(mouseX, mouseY, sx, y, sw, h);
        AeroTheme.well(s, treatment, sx, y, sw, h,
                searchFocused ? 1.0f : over ? 0.75f : 0.45f);
        if (searchFocused || over) {
            AeroTheme.innerGlow(s, sx, y, sw, h, searchFocused ? 1.0f : 0.45f, palette.glow);
        }
        String shown = query.isEmpty() ? "Search every section" : query;
        int colour = query.isEmpty() ? palette.textOff : palette.text;
        int queryWidth = searchFocused ? caretWidth(s, query, query.length()) : 0;
        int shift = searchFocused ? followCaret(queryWidth, 0, true, sx + 6, sx + sw - 2) : 0;
        s.clip(sx + 2, y, sx + sw - 2, y + h);
        s.text(shown, sx + 6 - shift, y + (h - s.lineHeight()) / 2 + 1, colour, false);
        if (searchFocused) {
            int cx = sx + 6 + queryWidth - shift;
            s.fill(cx, y + 3, cx + 1, y + h - 3, palette.accent);
        }
        s.unclip();
    }
    private void paintPageList(AeroSurface s, AeroTheme.Treatment treatment) {
        int x = menuX() + MARGIN;
        int y = contentTop();
        int h = contentBottom() - y;
        AeroTheme.panel(s, treatment, x, y, LIST_W, h,
                0.75f + 0.25f * (hoveredPage >= 0 ? pageHover[hoveredPage] : 0.0f));
        s.clip(x, y, x + LIST_W, y + h);
        int rows = pageRows();
        int first = pageTop(rows);
        int ry = pageRowTop(0);
        for (int i = first; i < first + rows; i++) {
            boolean active = i == current && sectionShown(i);
            int rx = x + 3;
            int rw = LIST_W - 6;
            float lift = pageHover[i];
            if (active) {
                AeroTheme.rounded(s, rx, ry, rw, PAGE_ROW_H, AeroTheme.RADIUS_ROW,
                        palette.accentDeep, AeroTheme.mix(palette.accentDeep,
                                palette.glassBottom, 0.55f));
                AeroTheme.gloss(s, rx, ry, rw, PAGE_ROW_H, 0.85f);
                s.fill(rx, ry, rx + 2, ry + PAGE_ROW_H, palette.accent);
            } else if (lift > 0.01f) {
                AeroTheme.flatRounded(s, rx, ry, rw, PAGE_ROW_H, AeroTheme.RADIUS_ROW,
                        AeroTheme.scaleAlpha(palette.rowHover, lift * 0.55f));
            }
            int count = matchCount(i);
            boolean dead = count == 0;
            int colour = active ? palette.text
                    : dead ? palette.textOff
                    : AeroTheme.mix(palette.textDim, palette.text, lift);
            String name = pageNames[i] != null ? pageNames[i] : plain(pages.get(i).name());
            String icon = pages.get(i).icon();
            String tag = count > 0 ? countLabel(count) : "";
            // Badge reserves its column even when hidden
            int tagWidth = tag.isEmpty() ? 0 : s.textWidth(tag);
            int badge = tag.isEmpty() ? 5 : 8 + tagWidth;
            s.clip(rx, ry, rx + rw - badge, ry + PAGE_ROW_H);
            boolean iconDrawn = false;
            if (icon != null) {
                iconDrawn = s.trySprite(icon, rx + 5, ry + (PAGE_ROW_H - 12) / 2, 12, 12, colour);
            }
            s.text(name, rx + (iconDrawn ? 21 : 7),
                    ry + (PAGE_ROW_H - s.lineHeight()) / 2 + 1, colour, false);
            s.unclip();
            if (!tag.isEmpty()) {
                s.text(tag, rx + rw - 5 - tagWidth,
                        ry + (PAGE_ROW_H - s.lineHeight()) / 2 + 1, palette.accent, false);
            }
            ry += PAGE_ROW_PITCH;
        }
        s.unclip();
    }
    private void paintContent(AeroSurface s, int mouseX, int mouseY,
            AeroTheme.Treatment treatment) {
        int x = contentX();
        int y = contentTop();
        int w = contentRight() - x;
        int h = contentBottom() - y;
        AeroTheme.panel(s, treatment, x, y, w, h, 1.0f);
        int top = listOrigin();
        s.clip(x + 2, y + 2, x + w - 2, y + h - 2);
        if (entries.isEmpty()) {
            s.text(emptyMessage, x + 12, y + 12, palette.textDim, false);
            s.text("Clear the box to bring every section back.", x + 12,
                    y + 12 + s.lineHeight() + 3, palette.textOff, false);
        }
        int first = firstEntryReaching(y - top, true);
        for (int i = first; i < entries.size(); i++) {
            Entry e = entries.get(i);
            int ey = top + e.y;
            if (ey > y + h) {
                break;
            }
            if (e.isSection()) {
                paintSectionBreak(s, e, x, ey);
            } else if (e.isHeader()) {
                paintGroupHeader(s, e, x, ey);
            } else {
                paintRow(s, e, x, ey, treatment);
            }
        }
        s.unclip();
        paintScrollbar(s, mouseX, mouseY);
    }
    private void paintScrollbar(AeroSurface s, int mouseX, int mouseY) {
        int max = maxScroll();
        int trackX = barX();
        int trackY = scrollTrackTop();
        int trackH = scrollTrackHeight();
        s.fill(trackX, trackY, trackX + SCROLLBAR_WIDTH, trackY + trackH, 0x33051014);
        if (max <= 0) {
            return;
        }
        int barH = barHeight(trackH);
        int barY = thumbTop(trackY, trackH, barH, scroll, max);
        boolean over = draggingBar || overBar(mouseX, mouseY, trackY, trackH);
        AeroTheme.rounded(s, trackX, barY, SCROLLBAR_WIDTH, barH, AeroTheme.RADIUS_ROW,
                AeroTheme.scaleAlpha(palette.accent, over ? 1.0f : 0.72f),
                AeroTheme.scaleAlpha(palette.accentDeep, over ? 1.0f : 0.72f));
        AeroTheme.lipped(s, trackX, barY, SCROLLBAR_WIDTH, barH, AeroTheme.RADIUS_ROW,
                AeroTheme.scaleAlpha(palette.accentLight, 0.55f), palette.edgeDark);
    }
    private int barHeight(int trackH) {
        return Math.min(trackH,
                Math.max(16, trackH * viewHeight() / Math.max(1, contentHeight)));
    }
    private int scrollTrackTop() {
        return contentTop() + 4;
    }
    private int scrollTrackHeight() {
        return contentBottom() - contentTop() - 8;
    }
    private static int thumbTop(int trackY, int trackH, int barH, int scroll, int max) {
        return trackY + (int) ((long) (trackH - barH) * scroll / max);
    }
    private void paintSectionBreak(AeroSurface s, Entry e, int x, int y) {
        int left = x + 7;
        int right = rowRight() - 4;
        if (e.y > 0) {
            int ruleY = y - SECTION_GAP / 2;
            s.fill(left, ruleY, right, ruleY + 1, palette.ruleStrong);
        }
        int ty = y + (SECTION_H - s.lineHeight()) / 2 + 1;
        int mid = ty + s.lineHeight() / 2 - 1;
        s.fill(left + 2, mid - 2, left + 3, mid + 3, palette.accent);
        s.fill(left + 1, mid - 1, left + 4, mid + 2, palette.accent);
        s.fill(left, mid, left + 5, mid + 1, palette.accent);
        s.clip(left, y, right, y + SECTION_H);
        s.text(e.header, left + 9, ty, palette.text, true);
        s.unclip();
    }
    private void paintGroupHeader(AeroSurface s, Entry e, int x, int y) {
        // Do not hoist to the constructor
        String name = upperHeader(e.header);
        int tx = x + 12;
        int ty = y + GROUP_H - s.lineHeight() - 1;
        s.text(name, tx, ty, palette.textHead, false);
        int lineX = tx + s.textWidth(name) + 6;
        int lineY = ty + s.lineHeight() / 2;
        int right = rowRight() - 4;
        if (lineX < right) {
            s.fill(lineX, lineY, right, lineY + 1, palette.rule);
        }
    }
    private String nameOf(Opt option) {
        String name = rowNames.get(option);
        if (name == null) {
            name = plain(option.name());
            if (rowNames.size() >= ROW_NAMES_MAX) {
                rowNames.clear();
            }
            rowNames.put(option, name);
        }
        return name;
    }
    private String upperHeader(String header) {
        String upper = upperHeaders.get(header);
        if (upper == null) {
            upper = header.toUpperCase(Locale.ROOT);
            if (upperHeaders.size() >= UPPER_HEADERS_MAX) {
                upperHeaders.clear();
            }
            upperHeaders.put(header, upper);
        }
        return upper;
    }
    private String countLabel(int count) {
        int slot = count & (countLabelKeys.length - 1);
        if (countLabelKeys[slot] != count) {
            countLabelKeys[slot] = count;
            countLabelValues[slot] = Integer.toString(count);
        }
        return countLabelValues[slot];
    }
    private void paintRow(AeroSurface s, Entry e, int x, int y, AeroTheme.Treatment treatment) {
        Opt option = e.option;
        boolean on = option.available();
        boolean isFocus = e == focused;
        float lift = AeroTheme.easeOut(e.hover);
        // Only STRING, COLOUR or NUMBER rows use the editing buffer; other kinds never see it.
        String value = option == editingOption ? editing : valueText(option);
        int rx = x + 4;
        int rw = rowRight() - rx;
        if (isFocus) {
            AeroTheme.rounded(s, rx, y, rw, e.h - 1, AeroTheme.RADIUS_ROW,
                    AeroTheme.scaleAlpha(palette.accentDeep, 0.85f),
                    AeroTheme.scaleAlpha(palette.accentDeep, 0.35f));
            AeroTheme.gloss(s, rx, y, rw, e.h - 1, 0.7f);
            s.fill(rx, y, rx + 2, y + e.h - 1, palette.accent);
        } else if (lift > 0.01f) {
            AeroTheme.flatRounded(s, rx, y, rw, e.h - 1, AeroTheme.RADIUS_ROW,
                    AeroTheme.scaleAlpha(palette.rowHover, lift * 0.5f));
        }
        boolean changed = option.changed();
        if (changed) {
            s.fill(rx, y + 2, rx + 2, y + e.h - 3, palette.valueChanged);
        }
        int textY = y + (e.h - 1 - s.lineHeight()) / 2 + 1;
        int nameColour = !on ? palette.textOff
                : AeroTheme.mix(palette.textDim, palette.text,
                        isFocus ? 1.0f : lift * 0.8f);
        // Not cached per surface
        int inset = 0;
        int rightChevron = 0;
        int enumW = 0;
        if (e.kind == Kind.ENUM) {
            inset = chevronRoom(s);
            rightChevron = lastChevronRight;
            enumW = enumWidthCached(s, e, inset);
            // Read by click(), which has no surface
            Integer cached = enumWidths.get(option);
            if (cached == null || cached.intValue() != enumW) {
                enumWidths.put(option, enumW);
            }
        }
        // Column holds the value only for kinds drawn beside the control; others leave it empty.
        String column = "";
        int columnWidth = 0;
        int nameRight = switch (e.kind) {
            case STRING, COLOUR, NUMBER, BUTTON -> fieldX() - GUTTER;
            // Name is measured against where the enum control actually starts, not a fixed constant.
            case ENUM -> enumLeft(enumW) - 4;
            // Value starts where it actually begins, not where its column ends.
            default -> {
                Fitted fitted = valueInColumn(s, value);
                column = fitted.text();
                columnWidth = fitted.width();
                yield valueRight() - fitted.width() - GUTTER;
            }
        };
        s.clip(x + 6, y, nameRight, y + e.h);
        if (e.name == null) {
            e.name = nameOf(option);
        }
        // Shortened, not clipped
        s.text(fit(s, e.name, nameRight - nameX()), nameX(), textY,
                nameColour, false);
        s.unclip();
        switch (e.kind) {
            case TOGGLE -> paintToggle(s, e, y, textY, on, lift, column, columnWidth,
                    changed);
            case SLIDER -> paintSlider(s, e, y, textY, on, lift, column, columnWidth,
                    changed);
            case ENUM -> paintEnum(s, e, y, textY, on, lift, value, enumW, inset,
                    rightChevron);
            case STRING, COLOUR, NUMBER -> paintField(s, e, y, textY, on, lift, value,
                    treatment);
            case BUTTON -> paintButton(s, e, y, textY, on, lift, value);
        }
    }
    private int valueColour(boolean on, boolean changed) {
        if (!on) {
            return palette.textOff;
        }
        return changed ? palette.valueChanged : palette.value;
    }
    private void paintToggle(AeroSurface s, Entry e, int y, int textY, boolean on,
            float lift, String value, int valueWidth, boolean changed) {
        Opt option = e.option;
        boolean state = option instanceof Opt.Bool flag
                && Boolean.TRUE.equals(flag.pending());
        s.text(value, valueRight() - valueWidth, textY, valueColour(on, changed), false);
        int tw = 28;
        int th = 12;
        int tx = controlX() + (CONTROL_W - tw) / 2;
        int ty = y + (e.h - 1 - th) / 2;
        if (state && on) {
            AeroTheme.rounded(s, tx, ty, tw, th, AeroTheme.RADIUS_ROW,
                    palette.accent, palette.accentDeep);
            AeroTheme.gloss(s, tx, ty, tw, th, 0.9f + lift * 0.1f);
        } else {
            AeroTheme.rounded(s, tx, ty, tw, th, AeroTheme.RADIUS_ROW,
                    palette.wellTop, palette.wellBottom);
        }
        AeroTheme.lipped(s, tx, ty, tw, th, AeroTheme.RADIUS_ROW,
                state ? AeroTheme.scaleAlpha(palette.accentLight, 0.6f) : palette.edgeDark,
                state ? palette.edgeDark : AeroTheme.scaleAlpha(palette.edgeLight, 0.4f));
        int kw = 10;
        int kx = state ? tx + tw - kw - 1 : tx + 1;
        int knobTop = on ? KNOB_ON_TOP : KNOB_OFF_TOP;
        int knobBottom = on ? KNOB_ON_BOTTOM : KNOB_OFF_BOTTOM;
        AeroTheme.rounded(s, kx, ty + 1, kw, th - 2, 1, knobTop, knobBottom);
        AeroTheme.gloss(s, kx, ty + 1, kw, th - 2, 1.0f);
    }
    private void paintSlider(AeroSurface s, Entry e, int y, int textY, boolean on,
            float lift, String value, int valueWidth, boolean changed) {
        Opt option = e.option;
        Opt.Ranged slider = (Opt.Ranged) option;
        s.text(value, valueRight() - valueWidth, textY, valueColour(on, changed), false);
        int tx = controlX();
        int tw = CONTROL_W;
        int th = 5;
        int ty = y + (e.h - 1 - th) / 2;
        AeroTheme.rounded(s, tx, ty, tw, th, 1, palette.wellTop, palette.wellBottom);
        double span = slider.max() - slider.min();
        float t = span <= 0 ? 0.0f
                : (float) ((slider.number() - slider.min()) / span);
        t = AeroTheme.clamp01(t);
        int filled = Math.round(tw * t);
        if (filled > 1 && on) {
            AeroTheme.rounded(s, tx, ty, filled, th, 1,
                    palette.accent, palette.accentDeep);
            AeroTheme.gloss(s, tx, ty, filled, th, 0.8f + lift * 0.2f);
        }
        int kx = tx + Math.max(0, Math.min(tw - 5, filled - 2));
        int kh = th + 6;
        int ky = ty - 3;
        AeroTheme.rounded(s, kx, ky, 5, kh, 1,
                on ? KNOB_ON_TOP : KNOB_OFF_TOP, on ? KNOB_ON_BOTTOM : KNOB_OFF_BOTTOM);
        AeroTheme.gloss(s, kx, ky, 5, kh, 1.0f);
    }
    private void paintEnum(AeroSurface s, Entry e, int y, int textY, boolean on,
            float lift, String value, int cw, int inset, int rightChevron) {
        int cx = enumLeft(cw);
        int ch = e.h - 5;
        int cy = y + 2;
        AeroTheme.rounded(s, cx, cy, cw, ch, AeroTheme.RADIUS_ROW,
                AeroTheme.mix(palette.buttonTop, palette.accentDeep, 0.18f + lift * 0.25f),
                palette.buttonBottom);
        AeroTheme.lipped(s, cx, cy, cw, ch, AeroTheme.RADIUS_ROW,
                palette.edgeLight, palette.edgeDark);
        AeroTheme.gloss(s, cx, cy, cw, ch, 0.7f + lift * 0.3f);
        int room = cw - inset * 2;
        // Shortened before centring, not after; the clip only guards a rounding error
        Fitted fitted = fitMeasured(s, value, room);
        String shown = fitted.text();
        int colour = on ? palette.text : palette.textOff;
        s.clip(cx + inset, cy, cx + cw - inset, cy + ch);
        s.text(shown, cx + inset + (room - fitted.width()) / 2, textY, colour, false);
        s.unclip();
        int chevron = AeroTheme.scaleAlpha(palette.accentLight, on ? 0.85f : 0.3f);
        s.text("<", cx + 3, textY, chevron, false);
        s.text(">", cx + cw - 3 - rightChevron, textY, chevron, false);
    }
    private void paintField(AeroSurface s, Entry e, int y, int textY, boolean on,
            float lift, String shown, AeroTheme.Treatment treatment) {
        Opt option = e.option;
        boolean editingThis = option == editingOption;
        int fx = fieldX();
        int fw = FIELD_W;
        int fh = e.h - 5;
        int fy = y + 2;
        if (e.kind == Kind.COLOUR) {
            int swatch = 14;
            AeroTheme.well(s, treatment, fx, fy, fw, fh,
                    editingThis ? 1.0f : 0.45f + lift * 0.55f);
            boolean invalid = editingThis && refusalFor(option, shown) != null;
            if (invalid) {
                AeroTheme.innerGlow(s, fx, fy, fw, fh, 1.0f, palette.glowInvalid);
            }
            int argb = colourOf(option);
            int shipped = option instanceof Opt.Colour colour ? colour.shipped() : 0xFF000000;
            int shippedX = fx + swatch + 5;
            // Each swatch is drawn over both white and near-black
            drawSwatch(s, fx + 2, fy + 2, swatch, fh - 4, argb, palette);
            drawSwatch(s, shippedX, fy + 2, swatch, fh - 4, shipped, palette);
            int left = shippedX + swatch + 7;
            s.clip(left - 1, fy, fx + fw - 3, fy + fh);
            if (editingThis) {
                int caretPx = caretWidth(s, shown, caret);
                editScroll = followCaret(caretPx, editScroll, caret >= shown.length(), left,
                        fx + fw - 3);
                s.text(shown, left - editScroll, textY,
                        !on ? palette.textOff
                                : invalid ? palette.valueInvalid : palette.value, false);
                int cx = left + caretPx - editScroll;
                s.fill(cx, fy + 2, cx + 1, fy + fh - 2,
                        invalid ? palette.valueInvalid : palette.accent);
            } else {
                s.text(shown, left, textY, on ? palette.value : palette.textOff, false);
            }
            s.unclip();
            return;
        }
        AeroTheme.well(s, treatment, fx, fy, fw, fh,
                editingThis ? 1.0f : 0.45f + lift * 0.55f);
        // Keystrokes are not filtered; the warm glow is the only invalid feedback while typing.
        boolean invalid = editingThis && refusalFor(option, shown) != null;
        if (invalid) {
            AeroTheme.innerGlow(s, fx, fy, fw, fh, 1.0f, palette.glowInvalid);
        } else if (editingThis) {
            AeroTheme.innerGlow(s, fx, fy, fw, fh, 1.0f, palette.glow);
        } else if (lift > 0.01f) {
            AeroTheme.innerGlow(s, fx, fy, fw, fh, lift * 0.5f, palette.glow);
        }
        s.clip(fx + 3, fy, fx + fw - 3, fy + fh);
        int colour = !on ? palette.textOff
                : invalid ? palette.valueInvalid
                : shown.isEmpty() ? palette.textOff : palette.value;
        String display = shown.isEmpty() && !editingThis ? "not set" : shown;
        int left = fx + 5;
        if (editingThis) {
            int caretPx = caretWidth(s, shown, caret);
            editScroll = followCaret(caretPx, editScroll, caret >= shown.length(), left,
                    fx + fw - 3);
            s.text(display, left - editScroll, textY, colour, false);
            int cx = left + caretPx - editScroll;
            s.fill(cx, fy + 2, cx + 1, fy + fh - 2,
                    invalid ? palette.valueInvalid : palette.accent);
        } else {
            s.text(display, left, textY, colour, false);
        }
        s.unclip();
    }
    private int caretWidth(AeroSurface s, String text, int index) {
        if (text != caretText || index != caretIndex) {
            caretText = text;
            caretIndex = index;
            caretWidth = s.textWidth(text.substring(0, Math.min(index, text.length())));
        }
        return caretWidth;
    }
    private static int followCaret(int caretPx, int editScroll, boolean atEnd, int left, int right) {
        int inner = right - left;
        if (caretPx - editScroll > inner - 1) {
            editScroll = caretPx - (inner - 1);
        }
        if (inner > 0 && caretPx < editScroll) {
            editScroll = caretPx;
        }
        if (atEnd) {
            editScroll = Math.min(editScroll, Math.max(0, caretPx - (inner - 1)));
        }
        return editScroll;
    }
    private static void drawSwatch(AeroSurface s, int x, int y, int w, int h, int argb,
            AeroTheme.Palette palette) {
        s.fill(x, y, x + w / 2, y + h, 0xFFE8F4F6);
        s.fill(x + w / 2, y, x + w, y + h, 0xFF101A1E);
        AeroTheme.flatRounded(s, x, y, w, h, 1, argb);
        AeroTheme.lipped(s, x, y, w, h, 1, palette.edgeLight, palette.edgeDark);
        AeroTheme.gloss(s, x, y, w, h, 0.9f);
    }
    // Use argb(); pending() here returns a preset name or number, not the packed colour.
    private static int colourOf(Opt option) {
        return option instanceof Opt.Colour colour ? colour.argb() : 0xFF000000;
    }
    private void paintButton(AeroSurface s, Entry e, int y, int textY, boolean on,
            float lift, String label) {
        int bw = FIELD_W;
        int bx = fieldX();
        int bh = e.h - 5;
        int by = y + 2;
        AeroTheme.rounded(s, bx, by, bw, bh, AeroTheme.RADIUS_ROW,
                AeroTheme.mix(palette.buttonTop, palette.accentDeep, 0.2f + lift * 0.35f),
                palette.buttonBottom);
        AeroTheme.lipped(s, bx, by, bw, bh, AeroTheme.RADIUS_ROW,
                palette.edgeLight, palette.edgeDark);
        AeroTheme.gloss(s, bx, by, bw, bh, 0.75f + lift * 0.25f);
        int colour = on ? palette.text : palette.textOff;
        int room = bw - 8;
        Fitted fitted = fitMeasured(s, label, room);
        String shown = fitted.text();
        int sw = fitted.width();
        s.text(shown, bx + (bw - sw) / 2, textY, colour, false);
    }
    // Shared by paintFooter and click()
    private static final int FOOTER_BTN_W = 54;
    private static final int FOOTER_BTN_H = 16;
    private int footerButtonY() {
        return height - MARGIN - FOOTER_BTN_H - 5;
    }
    private int footerDoneX() {
        return menuRight() - MARGIN - 6 - FOOTER_BTN_W;
    }
    private int footerApplyX() {
        return footerDoneX() - 6 - FOOTER_BTN_W;
    }
    private void paintFooter(AeroSurface s, int mouseX, int mouseY, AeroTheme.Treatment treatment) {
        int fh = footerHeight();
        int y = height - MARGIN - fh;
        int w = menuW() - MARGIN * 2;
        int fx = menuX() + MARGIN;
        AeroTheme.panel(s, treatment, fx, y, w, fh, 0.75f);
        int btnW = FOOTER_BTN_W;
        int btnH = FOOTER_BTN_H;
        int btnY = footerButtonY();
        int doneX = footerDoneX();
        int applyX = footerApplyX();
        paintFooterButton(s, "Done", doneX, btnY, btnW, btnH,
                inside(mouseX, mouseY, doneX, btnY, btnW, btnH), true);
        paintFooterButton(s, "Apply", applyX, btnY, btnW, btnH,
                inside(mouseX, mouseY, applyX, btnY, btnW, btnH), false);
        Entry described = hovered != null ? hovered : focused;
        Opt describedOption = described == null ? null : described.option;
        OptPage.Group describedGroup = describedOption == null && described != null
                ? described.group : null;
        // Refusal message takes priority over whatever the mouse is hovering.
        String refused = editingOption == null ? null : refusalFor(editingOption, editing);
        if (!footerBodyCached || footerDescribedOption != describedOption
                || footerDescribedGroup != describedGroup
                || footerEditingOption != editingOption
                || !Objects.equals(footerEditingText, editing)
                || !Objects.equals(footerRefusal, refused)) {
            if (refused != null) {
                footerBody = refused;
            } else if (describedOption != null) {
                String text = plain(describedOption.description());
                footerBody = text.isEmpty() ? plain(describedOption.name()) : text;
            } else if (describedGroup != null) {
                String text = plain(describedGroup.description());
                footerBody = text.isEmpty() ? described.header : text;
            } else {
                footerBody = "Hover a setting to read what it does. The column on the left scrolls "
                        + "this one list to a section rather than swapping it for another. "
                        + "Right-click a row to put it back to its default. Nothing is "
                        + "written until you press Apply or Done - Escape leaves without "
                        + "changing anything.";
            }
            footerBodyCached = true;
            footerDescribedOption = describedOption;
            footerDescribedGroup = describedGroup;
            footerEditingOption = editingOption;
            footerEditingText = editing;
            footerRefusal = refused;
        }
        int tx = fx + 7;
        int tw = applyX - 10 - tx;
        int ty = y + 6;
        int lineHeight = s.lineHeight();
        int lines = Math.max(1, (fh - 10) / lineHeight);
        s.clip(fx + 2, y + 2, tx + tw, y + fh - 2);
        for (String line : footerLines(s, footerBody, tw, lines)) {
            s.text(line, tx, ty, palette.textDim, false);
            ty += lineHeight;
        }
        s.unclip();
    }
    // Invalidated by a change in body, width, line count, or font face
    private List<String> footerLines(AeroSurface s, String body, int width, int lines) {
        Object face = wrapFace(s);
        if (!body.equals(wrapText) || width != wrapWidth || lines != wrapLines || face != wrapFace) {
            wrapText = body;
            wrapWidth = width;
            wrapLines = lines;
            wrapFace = face;
            wrapped = wrap(s, body, width, lines);
        }
        return wrapped;
    }
    public void forgetFooterWrap() {
        wrapText = null;
        wrapFace = null;
        enumLabelWidths.clear();
        enumWidths.clear();
        caretText = null;
        caretIndex = 0;
        caretWidth = 0;
    }
    private void paintFooterButton(AeroSurface s, String label, int x, int y, int w, int h,
            boolean over, boolean primary) {
        int top = primary
                ? AeroTheme.mix(palette.accentDeep, palette.accent, over ? 0.55f : 0.25f)
                : AeroTheme.mix(palette.buttonTop, palette.accentDeep, over ? 0.4f : 0.1f);
        AeroTheme.rounded(s, x, y, w, h, AeroTheme.RADIUS_ROW, top, palette.buttonBottom);
        AeroTheme.lipped(s, x, y, w, h, AeroTheme.RADIUS_ROW,
                palette.edgeLight, palette.edgeDark);
        AeroTheme.gloss(s, x, y, w, h, over ? 1.0f : 0.75f);
        s.text(label, x + (w - s.textWidth(label)) / 2, y + (h - s.lineHeight()) / 2 + 1,
                palette.text, false);
    }
    public List<String> wrap(AeroSurface s, String text, int maxWidth, int maxLines) {
        List<String> out = new ArrayList<>();
        if (maxWidth <= 8) {
            return out;
        }
        if (maxLines <= 0) {
            return out;
        }
        // True when a word is left undrawn
        boolean dropped = false;
        int marked = -1;
        StringBuilder line = new StringBuilder();
        boolean formatted = text.indexOf('\u00A7') >= 0;
        int end = text.length();
        String carried = "";
        while (end > 0 && text.charAt(end - 1) == ' ') {
            end--;
        }
        int spaceWidth = formatted ? 0 : s.textWidth(" ");
        int lineBound = 0;
        for (int from = 0; from < end;) {
            int b = text.indexOf(' ', from);
            if (b < 0 || b > end) {
                b = end;
            }
            String word = text.substring(from, b);
            from = b + 1;
            int wordWidth = s.textWidth(word);
            if (wordWidth > maxWidth) {
                if (!line.isEmpty()) {
                    lineBound = 0;
                    String finished = line.toString();
                    if (formatOnly(finished)) {
                        line.setLength(0);
                        carried = activeFormat(finished);
                    } else {
                        if (pushLine(out, line, finished, maxLines)) {
                            dropped = textLeftUndrawn(word, b, end);
                            break;
                        }
                        carried = activeFormat(finished);
                    }
                }
                marked = out.size();
                String carriedBeforeWord = carried;
                boolean prefixAfterShave = !carriedBeforeWord.isEmpty()
                        && word.indexOf('\u00A7') < 0;
                String finished = shaveToColumn(s, prefixAfterShave ? word : carriedBeforeWord + word,
                        maxWidth);
                if (prefixAfterShave && !finished.isEmpty()) {
                    finished = carriedBeforeWord + finished;
                }
                if (pushLine(out, line, finished, maxLines)) {
                    dropped = b < end;
                    break;
                }
                carried = activeFormat(carriedBeforeWord + word);
                continue;
            }
            if (line.isEmpty()) {
                if (!word.isEmpty()) {
                    line.append(carried).append(word);
                    carried = "";
                    if (!formatted) {
                        lineBound = wordWidth;
                    }
                }
                continue;
            }
            int before = line.length();
            line.append(' ').append(word);
            int bound = lineBound + spaceWidth + wordWidth;
            boolean over = formatted ? s.textWidth(line.toString()) > maxWidth
                    : bound >= maxWidth && s.textWidth(line.toString()) > maxWidth;
            if (over) {
                line.setLength(before);
                String finished = line.toString();
                if (formatOnly(finished)) {
                    line.setLength(0);
                } else if (pushLine(out, line, finished, maxLines)) {
                    // An empty word here means consecutive spaces, not real text
                    dropped = textLeftUndrawn(word, b, end);
                    break;
                }
                carried = activeFormat(finished);
                if (!word.isEmpty()) {
                    line.append(carried).append(word);
                    carried = "";
                }
                lineBound = wordWidth;
            } else {
                lineBound = bound;
            }
        }
        if (out.size() < maxLines && !line.isEmpty()) {
            out.add(line.toString());
        }
        // Runs only when text was shortened; if shaving empties the line, no mark is added.
        if (dropped && marked != out.size() - 1) {
            out.set(out.size() - 1,
                    shaveToColumn(s, out.get(out.size() - 1), maxWidth));
        }
        return out;
    }
    private static boolean pushLine(List<String> out, StringBuilder line, String finished,
            int maxLines) {
        out.add(finished);
        line.setLength(0);
        return out.size() == maxLines;
    }
    private static boolean textLeftUndrawn(String word, int breakAt, int end) {
        return !word.isEmpty() || breakAt < end;
    }
    private static String activeFormat(CharSequence text) {
        char colour = 0;
        int styles = 0;
        for (int at = 0; at + 1 < text.length(); at++) {
            if (text.charAt(at) == '\u00A7') {
                char code = Character.toLowerCase(text.charAt(++at));
                if (code >= '0' && code <= '9' || code >= 'a' && code <= 'f') {
                    colour = code;
                    styles = 0;
                } else if (code >= 'k' && code <= 'o') {
                    styles |= 1 << (code - 'k');
                } else if (code == 'r') {
                    colour = 0;
                    styles = 0;
                }
            }
        }
        if (colour == 0 && styles == 0) {
            return "";
        }
        StringBuilder active = new StringBuilder(12);
        if (colour != 0) {
            active.append('\u00A7').append(colour);
        }
        for (int style = 0; style < 5; style++) {
            if ((styles & 1 << style) != 0) {
                active.append('\u00A7').append((char) ('k' + style));
            }
        }
        return active.toString();
    }
    private static Object wrapFace(AeroSurface s) {
        return s.faceKey();
    }
    private String shaveToColumn(AeroSurface s, String last, int maxWidth) {
        if (last.indexOf(' ') < 0 && last.indexOf('\u00A7') < 0
                && s.textWidth(last) > maxWidth) {
            int low = 0;
            int high = last.codePointCount(0, last.length());
            while (low < high) {
                int mid = (low + high + 1) >>> 1;
                String prefix = last.substring(0, last.offsetByCodePoints(0, mid));
                if (s.textWidth(prefix) <= maxWidth) {
                    low = mid;
                } else {
                    high = mid - 1;
                }
            }
            last = last.substring(0, last.offsetByCodePoints(0, low));
        } else if (last.indexOf(' ') < 0 && s.textWidth(last) > maxWidth) {
            int kept = 0;
            while (kept < last.length()) {
                int next = formatUnitEnd(last, kept);
                if (next == kept || s.textWidth(last.substring(0, next)) > maxWidth) {
                    break;
                }
                kept = next;
            }
            last = last.substring(0, kept);
        }
        return shave(s, last, maxWidth, " " + CUT).text();
    }
    private static int formatUnitEnd(String text, int at) {
        int next = text.offsetByCodePoints(at, 1);
        if (text.codePointAt(at) == '\u00A7') {
            return next == text.length() ? at : text.offsetByCodePoints(next, 1);
        }
        return next;
    }
    private static boolean formatOnly(String text) {
        return !text.isEmpty() && !hasVisibleText(text, text.length());
    }
    private static int formatUnitStart(String text) {
        int end = text.length();
        int signs = signRunBefore(text, end);
        if (signs > 0) {
            return (signs & 1) == 0 ? end - 2 : end - 1;
        }
        int cut = text.offsetByCodePoints(end, -1);
        return (signRunBefore(text, cut) & 1) == 1 ? cut - 1 : cut;
    }
    private static int signRunBefore(String text, int at) {
        int run = 0;
        while (run < at && text.charAt(at - run - 1) == '\u00A7') {
            run++;
        }
        return run;
    }
    public static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
    private int firstEntryReaching(int line, boolean bottoms) {
        int low = 0;
        int high = entries.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            Entry e = entries.get(middle);
            if ((bottoms ? e.y + e.h : e.y) < line) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }
    // Half-open, like inside(): rejects right at the edge
    public Entry entryAt(int mx, int my) {
        sync();
        Entry e = entryUnderPointer(mx, my);
        return e != null && !e.isHeader() ? e : null;
    }
    private Entry entryUnderPointer(int mx, int my) {
        if (mx < contentX() || mx >= rowRight()
                || my < contentTop() || my >= contentBottom()) {
            return null;
        }
        int top = listOrigin();
        int want = my - top;
        int low = firstEntryReaching(want + 1, false);
        if (low == 0) {
            return null;
        }
        Entry e = entries.get(low - 1);
        return want < e.y + e.h ? e : null;
    }
    public int pageAt(int mx, int my) {
        sync();
        int x = menuX() + MARGIN;
        // Half-open, like inside() and entryAt()
        if (mx < x || mx >= x + LIST_W) {
            return -1;
        }
        // Bounds match the window paintPageList draws
        int rows = pageRows();
        int slot = (my - pageRowTop(0)) / PAGE_ROW_PITCH;
        if (slot < 0 || slot >= rows) {
            return -1;
        }
        int ry = pageRowTop(slot);
        if (my < ry || my >= ry + PAGE_ROW_H) {
            return -1;
        }
        return pageTop(rows) + slot;
    }
    // Highlight is stored directly, not derived from scroll each time
    public void selectPage(int index) {
        sync();
        if (index < 0 || index >= pages.size() || !sectionShown(index)) {
            return;
        }
        focused = null;
        current = index;
        scroll = clampOffset(sectionStart(index), maxScroll());
        pagePhase = 0.0f;
        // Runs after index and scroll are updated
        commitEditing();
    }
    void restorePage(int index) {
        selectPage(index);
        pagePhase = 1.0f;
    }
    // The click region covers the whole gutter
    private boolean overBar(int mx, int my, int trackY, int trackH) {
        return inside(mx, my, geometryRowRight, trackY,
                geometryContentRight - geometryRowRight, trackH);
    }
    private void grabBar(int my) {
        int y = scrollTrackTop();
        int trackH = scrollTrackHeight();
        int barH = barHeight(trackH);
        int barY = thumbTop(y, trackH, barH, scroll, Math.max(1, maxScroll()));
        draggingBar = true;
        barGrab = my >= barY && my < barY + barH ? my - barY : barH / 2;
        dragBar(my);
    }
    private void dragBar(int my) {
        int y = scrollTrackTop();
        int trackH = scrollTrackHeight();
        int barH = barHeight(trackH);
        int room = Math.max(1, trackH - barH);
        int at = Math.max(0, Math.min(room, my - barGrab - y));
        scroll = (int) ((long) at * maxScroll() / room);
        clampScroll();
        current = sectionAt(scroll);
    }
    public boolean click(int mx, int my, int button) {
        sync();
        int p = pageAt(mx, my);
        if (p >= 0) {
            if (!sectionShown(p)) {
                return true;
            }
            selectPage(p);
            // Invariant: searchFocused must equal (focusZone == 2); every exit must keep that true.
            searchFocused = false;
            focusZone = 0;
            return true;
        }
        int sx = searchX();
        int sw = searchRight() - sx;
        if (inside(mx, my, sx, searchTop(), sw, searchHeight())) {
            searchFocused = true;
            // Moving focus to search commits the field, same as Done and Apply do.
            commitEditing();
            focusZone = 2;
            return true;
        }
        searchFocused = false;
        int btnW = FOOTER_BTN_W;
        int btnH = FOOTER_BTN_H;
        int btnY = footerButtonY();
        int doneX = footerDoneX();
        int applyX = footerApplyX();
        if (inside(mx, my, doneX, btnY, btnW, btnH)) {
            commitEditing();
            doneAction.run();
            return true;
        }
        if (inside(mx, my, applyX, btnY, btnW, btnH)) {
            commitEditing();
            applyAction.run();
            return true;
        }
        if (maxScroll() > 0 && overBar(mx, my, scrollTrackTop(), scrollTrackHeight())) {
            commitEditing();
            grabBar(my);
            return true;
        }
        Entry e = entryAt(mx, my);
        if (e == null) {
            // A click on no row still commits the field, same as clicking a different row.
            commitEditing();
            return false;
        }
        focusZone = 1;
        if (focused != e) {
            commitEditing();
        }
        focused = e;
        Opt option = e.option;
        if (!option.available()) {
            return true;
        }
        if (button == 1) {
            // Only case that discards, not commits
            if (editingOption == option) {
                stopEditing();
            }
            if (option instanceof Opt.Text field
                    && field.refusal(field.defaultValue()) != null) {
                openEditor(option, field.defaultValue());
            } else {
                option.reset();
            }
            return true;
        }
        switch (e.kind) {
            case TOGGLE -> toggle(option);
            case ENUM -> {
                // Splits at the enum control's own centre
                int cw = enumWidths.getOrDefault(option, CONTROL_W);
                cycle(option, mx < enumLeft(cw) + cw / 2 ? -1 : 1);
            }
            case SLIDER -> {
                if (mx >= controlX()) {
                    draggingSlider = true;
                    dragOption = option;
                    dragSlider(mx);
                }
            }
            case BUTTON -> {
                if (option instanceof Opt.Action b) {
                    buttonAction.accept(b);
                }
            }
            case STRING, COLOUR, NUMBER -> startEditing(option);
        }
        return true;
    }
    public boolean dragging() {
        return draggingSlider || draggingBar;
    }
    public void drag(int mx, int my) {
        sync();
        if (draggingBar) {
            dragBar(my);
        } else if (draggingSlider) {
            dragSlider(mx);
        }
    }
    public void release() {
        draggingSlider = false;
        dragOption = null;
        draggingBar = false;
    }
    // Snaps to a whole number of steps from the row's minimum; interval 0 means no snapping.
    private static double snapToInterval(double raw, double min, double interval) {
        if (!(interval > 0)) {
            return raw;
        }
        double steps = (raw - min) / interval;
        if (Math.abs(steps) >= 0x1p52) {
            return raw;
        }
        long stop = Math.round(steps);
        double half = interval / 2.0;
        if (Math.abs(stop) > (1L << 29)) {
            double residual = Math.fma(-(double) stop, interval, raw - min);
            while (residual >= half) {
                stop++;
                residual -= interval;
            }
            while (residual < -half) {
                stop--;
                residual += interval;
            }
            return Math.fma((double) stop, interval, min);
        }
        double answer = min + stop * interval;
        if (answer != 0.0 && Math.abs(answer) <= half
                && (float) min + (float) ((double) stop * (float) interval) == 0.0f) {
            return 0.0;
        }
        return answer;
    }
    // Takes the last stop at or below max, not a clamp to max
    private static void setSnapped(Opt.Ranged slider, double raw) {
        double min = slider.min();
        double max = slider.max();
        double interval = slider.interval();
        double bounded = raw > max ? max : (raw < min ? min : raw);
        double snapped = snapToInterval(bounded, min, interval);
        if (interval > 0 && snapped > max) {
            // Guards a stop that overshoots max by float rounding
            snapped = (float) snapped == (float) max ? max : Math.max(min, snapped - interval);
        }
        slider.setNumber(snapped);
    }
    private void dragSlider(int mx) {
        if (focused == null || focused.kind != Kind.SLIDER || focused.option != dragOption
                || !focused.option.available()) {
            return;
        }
        Opt.Ranged slider = (Opt.Ranged) focused.option;
        float t = AeroTheme.clamp01((float) (mx - controlX()) / Math.max(1, CONTROL_W));
        double raw = slider.min() + t * (slider.max() - slider.min());
        setSnapped(slider, raw);
    }
    // Scroll and click both read the section through sectionAt
    public boolean scroll(double amount) {
        sync();
        int max = maxScroll();
        if (max == 0) {
            return false;
        }
        scroll = clampOffset(scroll - (int) Math.round(amount * ROW_H), max);
        current = sectionAt(scroll);
        return true;
    }
    private static void toggle(Opt option) {
        if (option instanceof Opt.Bool flag) {
            flag.set(!Boolean.TRUE.equals(flag.pending()));
        }
    }
    private static void setBoolean(Opt option, boolean value) {
        if (option instanceof Opt.Bool flag) {
            flag.set(value);
        }
    }
    private static void cycle(Opt option, int by) {
        if (option instanceof Opt.Cycle<?> cycling) {
            int length = Math.max(1, cycling.count());
            int at = cycling.index();
            int from = at < 0 ? (by < 0 ? length : -1) : at;
            cycling.setIndex(Math.floorMod(from + by, length));
        }
    }
    private void nudgeSlider(Opt option, int by) {
        Opt.Ranged slider = (Opt.Ranged) option;
        double interval = slider.interval() > 0 ? slider.interval() : 1.0;
        double raw = slider.number() + by * interval;
        setSnapped(slider, raw);
    }
    // Longest a field may hold, in UTF-16 units; typing and pasting both refuse a split pair whole
    public static final int FIELD_MAX = 256;
    // Accepts any char >= space, including section symbol and DEL; not vanilla's chat filter
    private static boolean typeable(int c) {
        return c >= ' ';
    }
    private void startEditing(Opt option) {
        if (option == editingOption) {
            return;
        }
        openEditor(option, valueText(option));
    }
    private void openEditor(Opt option, String text) {
        editingOption = option;
        editing = text;
        caret = editing.length();
        editScroll = 0;
    }
    public void stopEditing() {
        editingOption = null;
        editing = "";
        caret = 0;
        editScroll = 0;
    }
    // Accepts digits with optional sign, checked against the option's own range only.
    private static boolean numberAccepted(Opt option, String text) {
        boolean negative = text.startsWith("-");
        int from = negative ? 1 : 0;
        while (from < text.length() - 1 && text.charAt(from) == '0') {
            from++;
        }
        if (text.length() == from || text.length() - from > 10) {
            return false;
        }
        long value = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            value = value * 10 + c - '0';
        }
        if (negative) {
            value = -value;
        }
        Opt.Ranged bounds = (Opt.Ranged) option;
        return value >= bounds.min() && value <= bounds.max();
    }
    public boolean editingInvalid() {
        return editingOption != null && refusalFor(editingOption, editing) != null;
    }
    // Returns null when nothing is refused; colour rows never warn while typing, only at commit
    private String refusalFor(Opt option, String text) {
        if (refusalCacheActive && refusalCached && option == refusalOption
                && text == refusalText) {
            return refusalResult;
        }
        String result;
        if (option.kind() == Kind.NUMBER) {
            result = numberAccepted(option, text) ? null : refusal(option, text);
        } else if (option instanceof Opt.Colour colour) {
            result = colour.accepts(text) ? null : colourRefusal(text);
        } else {
            result = option instanceof Opt.Text field ? field.refusal(text) : null;
        }
        if (refusalCacheActive) {
            refusalCached = true;
            refusalOption = option;
            refusalText = text;
            refusalResult = result;
        }
        return result;
    }
    // Fallback text must stay byte-identical to the string in en_us.json
    private static String refusal(Opt option, String text) {
        Opt.Ranged bounds = (Opt.Ranged) option;
        return plain(net.minecraft.network.chat.Component.translatableWithFallback(
                "sandpaper.config.numberRefused",
                "%s is not a whole number between %s and %s. Leave this field to "
                        + "restore the set value.",
                text.isEmpty() ? "An empty box" : "\"" + text + "\"",
                Long.toString((long) bounds.min()),
                Long.toString((long) bounds.max())));
    }
    private static String colourRefusal(String text) {
        return plain(net.minecraft.network.chat.Component.translatableWithFallback(
                "sandpaper.config.colourRefused",
                "%s is not a colour. Use a name, or six or eight hex digits. "
                        + "Leave this field to restore the set colour.",
                text.isEmpty() ? "An empty box" : "\"" + text + "\""));
    }
    // Reaches the binding only on leaving the field
    public void commitEditing() {
        if (editingOption == null) {
            return;
        }
        if (!editingOption.available()) {
            stopEditing();
            return;
        }
        if (editingOption.kind() == Kind.NUMBER) {
            // Invalid input is refused, not clamped; a bad value never overwrites what was there.
            if (numberAccepted(editingOption, editing)) {
                ((Opt.Ranged) editingOption).setNumber(Long.parseLong(editing));
            }
            stopEditing();
            return;
        }
        Opt.Editable field = (Opt.Editable) editingOption;
        if (field.accepts(editing)) {
            field.setFromString(editing);
        }
        stopEditing();
    }
    public boolean charTyped(char c) {
        return codepointTyped(c);
    }
    public boolean codepointTyped(int codepoint) {
        int units = Character.charCount(codepoint);
        if (searchFocused) {
            // Returning true here, even when full, stops the key from also reaching Screen.charTyped.
            if (!typeable(codepoint)) {
                return true;
            }
            if (query.length() + units > FIELD_MAX) {
                return true;
            }
            query = query + Character.toString(codepoint);
            queryChanged();
            return true;
        }
        if (editingOption == null || !typeable(codepoint)) {
            return false;
        }
        if (editing.length() + units > FIELD_MAX) {
            return true;
        }
        editing = editing.substring(0, caret) + Character.toString(codepoint)
                + editing.substring(caret);
        caret += units;
        return true;
    }
    public boolean backspace() {
        if (searchFocused) {
            if (!query.isEmpty()) {
                query = query.substring(0, query.offsetByCodePoints(query.length(), -1));
                queryChanged();
            }
            return true;
        }
        if (editingOption == null || caret == 0) {
            return false;
        }
        int from = editing.offsetByCodePoints(caret, -1);
        editing = editing.substring(0, from) + editing.substring(caret);
        caret = from;
        return true;
    }
    public boolean delete() {
        if (editingOption == null || caret >= editing.length()) {
            return false;
        }
        editing = editing.substring(0, caret)
                + editing.substring(editing.offsetByCodePoints(caret, 1));
        return true;
    }
    // Must not reference Minecraft client types
    // Puts clipboard text into the field; drops newlines and tabs, same validation as typing
    public boolean paste(String text) {
        if (!pasteTarget()) {
            return false;
        }
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (searchFocused) {
            String room = typeableRun(text, FIELD_MAX - query.length());
            if (!room.isEmpty()) {
                query = query + room;
                queryChanged();
            }
            return true;
        }
        String room = typeableRun(text, FIELD_MAX - editing.length());
        if (!room.isEmpty()) {
            editing = editing.substring(0, caret) + room + editing.substring(caret);
            caret += room.length();
        }
        return true;
    }
    public boolean pasteTarget() {
        return searchFocused || editingOption != null;
    }
    // Cuts to fit rather than dropping the paste; length is counted after filtering, not before
    private static String typeableRun(String text, int room) {
        if (room <= 0) {
            return "";
        }
        if (text.length() <= room) {
            boolean allTypeable = true;
            for (int i = 0; i < text.length(); ) {
                int c = text.codePointAt(i);
                if (!typeable(c)) {
                    allTypeable = false;
                    break;
                }
                i += Character.charCount(c);
            }
            if (allTypeable) {
                return text;
            }
        }
        StringBuilder out = new StringBuilder(Math.min(text.length(), room));
        for (int i = 0; i < text.length() && out.length() < room; ) {
            int c = text.codePointAt(i);
            int units = Character.charCount(c);
            i += units;
            if (!typeable(c)) {
                continue;
            }
            if (out.length() + units > room) {
                break;
            }
            out.appendCodePoint(c);
        }
        return out.toString();
    }
    // Copies the whole field's text; there is no selection to copy part of it
    public String copy() {
        if (searchFocused) {
            return query.isEmpty() ? null : query;
        }
        return editingOption == null || editing.isEmpty() ? null : editing;
    }
    // Copies the field then clears it; does not commit
    public String cut() {
        String taken = copy();
        if (taken == null) {
            return null;
        }
        if (searchFocused) {
            query = "";
            queryChanged();
        } else {
            editing = "";
            caret = 0;
        }
        return taken;
    }
    public void setQuery(String text) {
        this.query = text == null ? "" : typeableRun(text, FIELD_MAX);
        queryChanged();
    }
    public void moveCaret(int by) {
        int at = Math.max(0, Math.min(editing.length(), caret));
        for (int step = by; step > 0 && at < editing.length(); step--) {
            at += Character.charCount(editing.codePointAt(at));
        }
        for (int step = by; step < 0 && at > 0; step++) {
            at -= Character.charCount(editing.codePointBefore(at));
        }
        caret = at;
    }
    public boolean caretHome() {
        if (editingOption == null) {
            return false;
        }
        caret = 0;
        return true;
    }
    public boolean caretEnd() {
        if (editingOption == null) {
            return false;
        }
        caret = editing.length();
        return true;
    }
    public boolean arrow(int dx, int dy) {
        sync();
        if (editingOption != null && dx != 0) {
            moveCaret(dx);
            return true;
        }
        if (dy != 0) {
            if (focusZone == 0) {
                selectPage(nextShown(current, dy));
                return true;
            }
            moveFocus(dy);
            return true;
        }
        if (searchFocused || focusZone == 0 || focused == null
                || !focused.option.available()) {
            return false;
        }
        switch (focused.kind) {
            case SLIDER -> nudgeSlider(focused.option, dx);
            case ENUM -> cycle(focused.option, dx);
            case TOGGLE -> setBoolean(focused.option, dx > 0);
            default -> {
                return false;
            }
        }
        return true;
    }
    // Moving focus also scrolls to keep the row visible
    private void moveFocus(int dy) {
        int count = 0;
        int at = -1;
        for (Entry e : entries) {
            if (e.isHeader()) {
                continue;
            }
            if (e == focused) {
                at = count;
            }
            count++;
        }
        if (count == 0) {
            return;
        }
        int index = at < 0 ? (dy > 0 ? 0 : count - 1) : Math.floorMod(at + dy, count);
        Entry target = null;
        int ordinal = 0;
        for (Entry e : entries) {
            if (e.isHeader()) {
                continue;
            }
            if (ordinal == index) {
                target = e;
                break;
            }
            ordinal++;
        }
        Entry previous = focused;
        commitEditing();
        sync();
        target = rowFor(entries, target.option);
        if (target == null && previous != null) {
            target = rowFor(entries, previous.option);
        }
        if (target == null) {
            focused = null;
            focusZone = 0;
            searchFocused = false;
            current = settle(current);
            return;
        }
        focused = target;
        focusZone = 1;
        searchFocused = false;
        scrollTo(focused);
    }
    private void scrollTo(Entry e) {
        int view = viewHeight();
        if (e.y < scroll) {
            scroll = e.y;
        } else if (e.y + e.h > scroll + view) {
            scroll = e.y + e.h - view;
        }
        clampScroll();
        // Keeps the left column's section in sync with the focused row's own section.
        if (sectionAt(scroll) != e.section) {
            scroll = clampOffset(sectionStart(e.section), maxScroll());
            if (e.y + e.h > scroll + view) {
                scroll = clampOffset(e.y + e.h - view, maxScroll());
            }
        }
        current = e.section;
    }
    public boolean activate() {
        sync();
        if (searchFocused) {
            searchFocused = false;
            return true;
        }
        if (editingOption != null) {
            // Enter on an invalid value keeps the field open, instead of discarding the typing.
            if (editingInvalid()) {
                return true;
            }
            commitEditing();
            return true;
        }
        if (focusZone == 0 || focused == null || !focused.option.available()) {
            return false;
        }
        switch (focused.kind) {
            case TOGGLE -> toggle(focused.option);
            case ENUM -> cycle(focused.option, 1);
            case STRING, COLOUR, NUMBER -> startEditing(focused.option);
            case BUTTON -> {
                if (focused.option instanceof Opt.Action b) {
                    buttonAction.accept(b);
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }
    public void cycleZone(int direction) {
        focusZone = Math.floorMod(focusZone + (direction < 0 ? -1 : 1), 3);
        searchFocused = focusZone == 2;
        if (focusZone != 1) {
            commitEditing();
        }
    }
    public String narration() {
        sync();
        if (searchFocused) {
            return "Search every section. " + query;
        }
        if (pages.isEmpty()) {
            return "No settings on this screen";
        }
        if (focused == null || focusZone == 0) {
            if (current < 0) {
                return emptyMessage;
            }
            return plain(pages.get(current).name()) + " section, "
                    + (current + 1) + " of " + pages.size();
        }
        Opt option = focused.option;
        String value = valueText(option);
        return plain(pages.get(focused.section).name()) + ", "
                + plain(option.name()) + ", " + value
                + (option.available() ? "" : ", unavailable");
    }
}
