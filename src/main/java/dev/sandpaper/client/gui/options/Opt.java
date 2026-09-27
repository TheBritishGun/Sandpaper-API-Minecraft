package dev.sandpaper.client.gui.options;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
// One row on the settings screen: a name, description, and a changeable value.
public abstract class Opt {
    private static final Logger LOGGER = Logger.getLogger(Opt.class.getName());
    public enum Kind { TOGGLE, SLIDER, NUMBER, ENUM, STRING, COLOUR, BUTTON, PLAIN }
    // A row the painter can drag, nudge with an arrow key, or bound a typed number by.
    public interface Ranged {
        double min();
        double max();
        // The step the value snaps to, or 0 for a value that does not snap.
        double interval();
        double number();
        // Refuses NaN and keeps the current value; clamps everything else, including infinity.
        void setNumber(double value);
    }
    // A row whose value is typed rather than dragged or cycled.
    public interface Editable {
        boolean accepts(String text);
        void setFromString(String text);
    }
    private static final char GROUPING_GAP = (char) 0x00a0;
    private static final char NARROW_GROUPING_GAP = (char) 0x202f;
    private final Component name;
    private final Component description;
    private boolean available = true;
    protected Opt(Component name, Component description) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description == null ? Component.empty() : description;
    }
    public final Component name() {
        return name;
    }
    // The prose for the tooltip pane, empty for the rows that carry none.
    public final Component description() {
        return description;
    }
    // False for a row that is shown but cannot be changed yet, and drawn greyed.
    public final boolean available() {
        return available;
    }
    public final void setAvailable(boolean value) {
        if (available != value) {
            this.available = value;
        }
    }
    public abstract Kind kind();
    // The value as it is written on the right of the row.
    public abstract String format();
    // Whether the pending value differs from the one the mod ships with.
    public boolean changed() {
        return false;
    }
    // Put the row back to the shipped default. Right-click does this.
    public void reset() {
    }
    // Write the pending value into the config. Apply and Done, and nothing else.
    public void apply() {
    }
    // Never throws.
    private static String named(Component name) {
        if (name == null) {
            return "an unnamed settings row";
        }
        try {
            return "the settings row \"" + name.getString() + "\"";
        } catch (Throwable unreadable) {
            return "a settings row whose name could not be read";
        }
    }
    private static <T extends Number> T checkedBoundDefault(Component name, T defaultValue,
            Number low, Number high, String bounds) {
        if (low.doubleValue() > high.doubleValue()) {
            throw new IllegalArgumentException(named(name) + " has a low bound "
                    + low + " above high bound " + high + "; values clamp to "
                    + low + ".");
        }
        if (defaultValue.doubleValue() < low.doubleValue()
                || defaultValue.doubleValue() > high.doubleValue()) {
            throw new IllegalArgumentException(named(name) + " has a default "
                    + defaultValue + " outside its " + bounds + " " + low
                    + " to " + high + ".");
        }
        return defaultValue;
    }
    private static <T> Consumer<T> checkedSink(Component name, Consumer<T> sink) {
        return Objects.requireNonNull(sink, () -> named(name)
                + " has no sink.");
    }
    private static <T> Component checkedNameAndSink(Component name, Consumer<T> sink) {
        Objects.requireNonNull(name, "name");
        checkedSink(name, sink);
        return name;
    }
    // Strips only the two grouping gap chars; must never touch the decimal separator.
    private static String withoutGroupingGaps(String written) {
        int first = -1;
        for (int i = 0; i < written.length(); i++) {
            char c = written.charAt(i);
            if (c == GROUPING_GAP || c == NARROW_GROUPING_GAP) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return written;
        }
        StringBuilder kept = new StringBuilder(written.length() - 1);
        kept.append(written, 0, first);
        for (int i = first + 1; i < written.length(); i++) {
            char c = written.charAt(i);
            if (c != GROUPING_GAP && c != NARROW_GROUPING_GAP) {
                kept.append(c);
            }
        }
        return kept.toString();
    }
    // A row bound to a config value; one option can back two fields via its sink.
    public abstract static class Value<T> extends Opt {
        private final T defaultValue;
        private final Consumer<T> sink;
        private final List<Consumer<T>> listeners = new ArrayList<>();
        private T pending;
        private int stagings;
        protected Value(Component name, Component description,
                T defaultValue, T current, Consumer<T> sink) {
            super(name, description);
            this.defaultValue = defaultValue;
            this.sink = checkedSink(name, sink);
            this.pending = current;
        }
        public final T pending() {
            return pending;
        }
        public final T defaultValue() {
            return defaultValue;
        }
        protected final String labelledOverride(Function<T, String> text) {
            if (text != null) {
                String written = text.apply(pending());
                if (written != null && !written.isBlank()) {
                    return written;
                }
            }
            return null;
        }
        // A listener that stages a newer value ends this dispatch; one that throws costs only itself.
        public final void set(T value) {
            if (Objects.equals(pending, value)) {
                return;
            }
            pending = value;
            int staging = ++stagings;
            Throwable first = null;
            for (int at = 0, told = listeners.size(); at < told; at++) {
                try {
                    listeners.get(at).accept(value);
                } catch (VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable fault) {
                    if (first == null) {
                        first = fault;
                    } else if (fault != first) {
                        first.addSuppressed(fault);
                    }
                }
                if (stagings != staging) {
                    break;
                }
            }
            if (first instanceof RuntimeException unchecked) {
                throw unchecked;
            }
            if (first instanceof Error error) {
                throw error;
            }
            if (first != null) {
                throw new IllegalStateException(first);
            }
        }
        public final void onChange(Consumer<T> listener) {
            listeners.add(listener);
        }
        @Override
        public boolean changed() {
            return !Objects.equals(pending, defaultValue);
        }
        @Override
        public final void reset() {
            pending = defaultValue;
            int staging = ++stagings;
            Throwable first = null;
            for (int at = 0, told = listeners.size(); at < told; at++) {
                try {
                    listeners.get(at).accept(defaultValue);
                } catch (VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable fault) {
                    if (first == null) {
                        first = fault;
                    } else if (fault != first) {
                        first.addSuppressed(fault);
                    }
                }
                if (stagings != staging) {
                    break;
                }
            }
            if (first instanceof RuntimeException unchecked) {
                throw unchecked;
            }
            if (first instanceof Error error) {
                throw error;
            }
            if (first != null) {
                throw new IllegalStateException(first);
            }
        }
        @Override
        public void apply() {
            sink.accept(pending);
        }
    }
    // A switch.
    public static final class Bool extends Value<Boolean> {
        private static Object shownIn = new Object();
        private static String on;
        private static String off;
        public Bool(Component name, Component description,
                boolean defaultValue, boolean current, Consumer<Boolean> sink) {
            super(name, description, defaultValue, current, sink);
        }
        @Override
        public Kind kind() {
            return Kind.TOGGLE;
        }
        // Uses vanilla's own On/Off strings, refreshed when the Language object changes.
        @Override
        public String format() {
            Language language = Language.getInstance();
            if (language != shownIn) {
                String onWord = CommonComponents.OPTION_ON.getString();
                String offWord = CommonComponents.OPTION_OFF.getString();
                on = onWord;
                off = offWord;
                shownIn = language;
            }
            return Boolean.TRUE.equals(pending()) ? on : off;
        }
    }
    // A whole number: a slider, or a typed field that refuses an out of range value.
    public static final class Ints extends Value<Integer> implements Ranged {
        private final int low;
        private final int high;
        private final int step;
        private final boolean typed;
        private final Function<Integer, String> text;
        private Integer shownFor;
        private Locale shownIn;
        // Caches the last formatted text, valid for the same value and locale.
        private String shown;
        public Ints(Component name, Component description, int defaultValue, int current,
                Consumer<Integer> sink, int low, int high, int step) {
            this(name, description, defaultValue, current, sink, low, high, step,
                    false, null);
        }
        private Ints(Component name, Component description, int defaultValue, int current,
                Consumer<Integer> sink, int low, int high, int step, boolean typed,
                Function<Integer, String> text) {
            super(checkedNameAndSink(name, sink), description,
                    checkedBoundDefault(name, defaultValue, low, high, "bounds"),
                    Math.clamp(current, low, high), sink);
            this.low = low;
            this.high = high;
            this.step = checkedStep(name, step);
            this.typed = typed;
            this.text = text;
        }
        private static int checkedStep(Component name, int step) {
            if (step <= 0) {
                throw new IllegalArgumentException(named(name) + " has a step "
                        + step + " not above zero.");
            }
            return step;
        }
        // The same field, typed rather than dragged.
        public static Ints typed(Component name, Component description, int defaultValue,
                int current, Consumer<Integer> sink, int low, int high) {
            return new Ints(name, description, defaultValue, current, sink, low, high,
                    1, true, null);
        }
        // Labels the value instead of a bare number.
        public static Ints labelled(Component name, Component description,
                int defaultValue, int current, Consumer<Integer> sink,
                int low, int high, int step, Function<Integer, String> text) {
            return new Ints(name, description, defaultValue, current, sink, low, high,
                    step, false, text);
        }
        @Override
        public Kind kind() {
            return typed ? Kind.NUMBER : Kind.SLIDER;
        }
        // Checks isBlank, not just null.
        @Override
        public String format() {
            String written = labelledOverride(text);
            if (written != null) {
                return written;
            }
            if (typed) {
                return Integer.toString(pending());
            }
            Integer value = pending();
            // The FORMAT category locale, the same one String.format would use by default.
            Locale locale = Locale.getDefault(Locale.Category.FORMAT);
            if (shown == null || value != shownFor || locale != shownIn) {
                shown = withoutGroupingGaps(String.format("%,d", pending()));
                shownFor = value;
                shownIn = locale;
            }
            return shown;
        }
        @Override
        public double min() {
            return low;
        }
        @Override
        public double max() {
            return high;
        }
        @Override
        public double interval() {
            return typed ? 0 : step;
        }
        @Override
        public double number() {
            return pending();
        }
        // Math.round(NaN) is 0.
        @Override
        public void setNumber(double value) {
            if (Double.isNaN(value)) {
                return;
            }
            int clamped = (int) Math.max(low, Math.min(high, Math.round(value)));
            Integer current = pending();
            if (current != null && current == clamped) {
                return;
            }
            set(clamped);
        }
    }
    // A fractional number, always a slider.
    public static final class Floats extends Value<Float> implements Ranged {
        private final float low;
        private final float high;
        private final float step;
        private final Function<Float, String> text;
        private Float shownFor;
        private Locale shownIn;
        // Caches the last formatted text, valid for the same value and locale.
        private String shown;
        public Floats(Component name, Component description, float defaultValue,
                float current, Consumer<Float> sink, float low, float high, float step) {
            this(name, description, defaultValue, current, sink, low, high, step, null);
        }
        private Floats(Component name, Component description, float defaultValue,
                float current, Consumer<Float> sink, float low, float high, float step,
                Function<Float, String> text) {
            super(checkedNameAndSink(name, sink), description,
                    checkedDefault(name, zeroWithoutSign(defaultValue), low, high),
                    checkedCurrent(current, defaultValue, low, high), sink);
            this.low = low;
            this.high = high;
            this.step = checkedStep(name, step);
            this.text = text;
        }
        private static float checkedDefault(Component name, float defaultValue, float low,
                float high) {
            if (!Float.isFinite(low) || !Float.isFinite(high)) {
                throw new IllegalArgumentException(named(name) + " has non-finite bounds "
                        + low + " and " + high + ".");
            }
            if (!Float.isFinite(defaultValue)) {
                throw new IllegalArgumentException(named(name) + " has a non-finite default "
                        + defaultValue + " outside " + low
                        + " and " + high + ".");
            }
            return checkedBoundDefault(name, defaultValue, low, high, "finite bounds");
        }
        private static float checkedStep(Component name, float step) {
            if (!Float.isFinite(step) || step <= 0.0f) {
                throw new IllegalArgumentException(named(name) + " has a step "
                        + step + " not a finite number above zero.");
            }
            return step;
        }
        private static float checkedCurrent(float current, float defaultValue, float low,
                float high) {
            if (Float.isNaN(current)) {
                return defaultValue;
            }
            return clamp(current, low, high);
        }
        private static float clamp(double value, float low, float high) {
            return value < low ? low : (value > high ? high : (float) value);
        }
        private static float zeroWithoutSign(float value) {
            return value == 0.0f ? 0.0f : value;
        }
        // Labels the value instead of a bare fraction, replacing the one decimal place format.
        public static Floats labelled(Component name, Component description,
                float defaultValue, float current, Consumer<Float> sink,
                float low, float high, float step, Function<Float, String> text) {
            return new Floats(name, description, defaultValue, current, sink, low, high,
                    step, text);
        }
        @Override
        public Kind kind() {
            return Kind.SLIDER;
        }
        // Falls through like Ints#format, including its null vs blank distinction.
        @Override
        public String format() {
            String written = labelledOverride(text);
            if (written != null) {
                return written;
            }
            Float value = pending();
            // The FORMAT category locale, the same one String.format would use by default.
            Locale locale = Locale.getDefault(Locale.Category.FORMAT);
            if (shown == null || value != shownFor || locale != shownIn) {
                shown = withoutGroupingGaps(String.format("%,.1f", pending()));
                shownFor = value;
                shownIn = locale;
            }
            return shown;
        }
        @Override
        public double min() {
            return low;
        }
        @Override
        public double max() {
            return high;
        }
        @Override
        public double interval() {
            return step;
        }
        @Override
        public double number() {
            return pending();
        }
        @Override
        public boolean changed() {
            Float held = pending();
            if (held != null && held == 0.0f && defaultValue() == 0.0f) {
                return false;
            }
            return super.changed();
        }
        // Drops NaN rather than replacing it with the default.
        @Override
        public void setNumber(double value) {
            if (Double.isNaN(value)) {
                return;
            }
            float clamped = clamp(value, low, high);
            Float current = pending();
            // Compares bit patterns; positive and negative zero count as different.
            if (current != null && Float.floatToRawIntBits(current)
                    == Float.floatToRawIntBits(clamped)) {
                return;
            }
            set(zeroWithoutSign(clamped));
        }
    }
    // One of a list, stepped with arrows; refuses any value not on that list at construction.
    public static final class Cycle<T> extends Value<T> {
        private static final Component MISSING_LABEL = Component.empty();
        private final List<T> values;
        private final Function<T, Component> label;
        private final LongSupplier labelRevision;
        private int position;
        private T shownFor;
        private Language shownIn;
        private long shownAtRevision;
        private String shown;
        private boolean toldMissingLabel;
        public Cycle(Component name, Component description, List<T> choices,
                T defaultValue, T current, Consumer<T> sink,
                Function<T, Component> label) {
            this(name, description, choices, defaultValue, current, sink, label, null);
        }
        private Cycle(Component name, Component description, List<T> choices,
                T defaultValue, T current, Consumer<T> sink,
                Function<T, Component> label, LongSupplier labelRevision) {
            super(name, description, defaultValue, current, sink);
            // List.copyOf refuses a null element before any guard below runs.
            List<T> all = fixed(choices);
            if (current == null || !all.contains(current)) {
                throw new IllegalArgumentException("the stored choice of " + named(name)
                        + " is not on its list.");
            }
            if (defaultValue == null || !all.contains(defaultValue)) {
                throw new IllegalArgumentException("the shipped choice of " + named(name)
                        + " is not on its list.");
            }
            if (repeats(all)) {
                throw new IllegalArgumentException(named(name) + " has duplicate choices.");
            }
            this.values = all;
            if (label == null) {
                throw new NullPointerException(named(name) + " has no label function.");
            }
            for (int index = 0; index < values.size(); index++) {
                if (label.apply(values.get(index)) == null) {
                    throw new IllegalArgumentException("choice " + index + " of "
                            + named(name) + " has no label.");
                }
            }
            this.label = label;
            this.labelRevision = labelRevision;
        }
        public static Cycle<Integer> overIndices(Component name, Component description,
                int count, int defaultValue, int current, Consumer<Integer> sink,
                Function<Integer, Component> label) {
            return new Cycle<>(name, description, new Indices(count), defaultValue,
                    current, sink, label);
        }
        public static Cycle<Integer> overIndices(Component name, Component description,
                int count, int defaultValue, int current, Consumer<Integer> sink,
                Function<Integer, Component> label, LongSupplier labelRevision) {
            return new Cycle<>(name, description, new Indices(count), defaultValue,
                    current, sink, label, labelRevision);
        }
        public long labelRevision() {
            return labelRevision == null ? 0 : labelRevision.getAsLong();
        }
        private static <T> List<T> fixed(List<T> choices) {
            return choices instanceof Indices ? choices : List.copyOf(choices);
        }
        // Indices cannot repeat.
        private static boolean repeats(List<?> all) {
            return !(all instanceof Indices)
                    && new HashSet<>(all).size() != all.size();
        }
        // A virtual list of 0 to count minus 1; one object, no boxed positions.
        private static final class Indices extends AbstractList<Integer>
                implements RandomAccess {
            private final int count;
            Indices(int count) {
                this.count = count;
            }
            @Override
            public Integer get(int index) {
                return Objects.checkIndex(index, count);
            }
            @Override
            public int size() {
                return count;
            }
            @Override
            public boolean contains(Object value) {
                return indexOf(value) >= 0;
            }
            @Override
            public int indexOf(Object value) {
                return value instanceof Integer index && index >= 0 && index < count
                        ? index
                        : -1;
            }
        }
        @Override
        public Kind kind() {
            return Kind.ENUM;
        }
        // Cached by value identity, language and label revision.
        @Override
        public String format() {
            T value = pending();
            Language language = Language.getInstance();
            long revision = labelRevision();
            if (shown == null || value != shownFor || language != shownIn
                    || revision != shownAtRevision) {
                shown = label().getString();
                shownFor = value;
                shownIn = language;
                shownAtRevision = revision;
            }
            return shown;
        }
        // Label of the staged choice, before it is flattened to text like format().
        public Component label() {
            int current = index();
            return current < 0 ? missingLabel() : labelFor(values.get(current));
        }
        // Label of one choice, read without staging it or changing the current value.
        public Component labelAt(int index) {
            return labelFor(values.get(Math.max(0, Math.min(values.size() - 1, index))));
        }
        private Component labelFor(T value) {
            Component written = label.apply(value);
            if (written != null) {
                return written;
            }
            return missingLabel();
        }
        private Component missingLabel() {
            if (!toldMissingLabel) {
                toldMissingLabel = true;
                LOGGER.warning("a cycle row has no label; showing a placeholder.");
            }
            return MISSING_LABEL;
        }
        public int count() {
            return values.size();
        }
        // Cached index, revalidated against pending().
        public int index() {
            T current = pending();
            if (current == null) {
                position = -1;
            } else if (position < 0 || !Objects.equals(current, values.get(position))) {
                position = values.indexOf(current);
            }
            return position;
        }
        public void setIndex(int index) {
            if (index >= 0 && index < values.size()) {
                position = index;
                set(values.get(index));
            }
        }
    }
    // Free text option, either plain or with a rule that can refuse it.
    public static final class Text extends Value<String> implements Editable {
        private final Function<String, String> refusing;
        private final String given;
        public Text(Component name, Component description, String defaultValue,
                String current, Consumer<String> sink) {
            this(name, description, defaultValue, current, sink, null);
        }
        private Text(Component name, Component description, String defaultValue,
                String current, Consumer<String> sink,
                Function<String, String> refusing) {
            super(name, description, defaultValue,
                    current == null ? defaultValue : current, sink);
            if (defaultValue == null) {
                throw new NullPointerException(named(name) + " has no shipped value.");
            }
            this.refusing = refusing;
            this.given = pending();
        }
        // Refuses values for which refusing returns non-null; refusing must not be null.
        public static Text refusing(Component name, Component description,
                String defaultValue, String current, Consumer<String> sink,
                Function<String, String> refusing) {
            return new Text(name, description, defaultValue, current, sink,
                    Objects.requireNonNull(refusing, () -> named(name)
                            + " has no refusal rule."));
        }
        @Override
        public Kind kind() {
            return Kind.STRING;
        }
        @Override
        public String format() {
            return pending();
        }
        // Why the given text would be refused, or null if it would be accepted.
        public String refusal(String value) {
            return refusing == null ? null : refusing.apply(value == null ? "" : value);
        }
        @Override
        public boolean accepts(String value) {
            return refusal(value) == null;
        }
        // Re-checks refusal here too; a caller may skip accepts() first.
        @Override
        public void setFromString(String value) {
            String typed = value == null ? "" : value;
            if (refusal(typed) == null) {
                set(typed);
            }
        }
        @Override
        public void apply() {
            String held = pending();
            String refused = refusal(held);
            if (refused == null) {
                super.apply();
                return;
            }
            if (Objects.equals(held, given)) {
                return;
            }
            throw new IllegalStateException(refused);
        }
    }
    // A named colour preset, or CUSTOM with a packed ARGB; custom is 0 otherwise.
    public record Swatch(Preset preset, int custom) {
        // Colour presets; a duplicate in dev.landnav.config.ColourSwatch must be kept in sync.
        public enum Preset {
            AMBER(rgba(0xE0A33A, 0xDD), "Amber"),
            RED(rgba(0xD62A2A, 0xDD), "Red"),
            // The hue the settings screens are visually built around.
            TEAL(rgba(0x46DCC6, 0xDD), "Teal"),
            // Draws nothing; the only preset with alpha 0.
            CLEAR(rgba(0x000000, 0x00), "Clear"),
            // Near black, not pure black.
            BLACK(rgba(0x1A1A1A, 0xDD), "Black"),
            // Off-white, not pure white; #FFFFFFFF is still reachable via hex.
            WHITE(rgba(0xEAF6F8, 0xDD), "White"),
            // Must stay a true neutral; keep red, green and blue equal.
            GREY(rgba(0x808080, 0xDD), "Grey"),
            // argb here is unused; the real default comes from the row's own shipped value.
            DEFAULT(0, "Default"),
            // CUSTOM holds no colour itself; the real value is Swatch's custom field.
            CUSTOM(0, "Custom");
            private final int argb;
            private final String label;
            Preset(int argb, String label) {
                this.argb = argb;
                this.label = label;
            }
            // values() clones its backing array on every call.
            private static final Preset[] VALUES = values();
            private static int rgba(int rgb, int alpha) {
                return (alpha << 24) | (rgb & 0xFFFFFF);
            }
            // What the row's colour field writes and Swatch.parse reads back.
            public String label() {
                return label;
            }
        }
        private static final String HEX_DIGITS = "0123456789ABCDEF";
        // The value an untouched row holds: the field's own shipped colour.
        public static final Swatch DEFAULT = new Swatch(Preset.DEFAULT, 0);
        public Swatch {
            preset = preset == null ? Preset.DEFAULT : preset;
            custom = preset == Preset.CUSTOM ? custom : 0;
        }
        public static Swatch of(Preset preset) {
            return new Swatch(preset, 0);
        }
        public static Swatch custom(int argb) {
            return new Swatch(Preset.CUSTOM, argb);
        }
        // The packed ARGB to draw; shipped is what DEFAULT resolves to on this row.
        public int resolve(int shipped) {
            if (preset == Preset.DEFAULT) {
                return shipped;
            }
            if (preset == Preset.CUSTOM) {
                return custom;
            }
            return preset.argb;
        }
        // The preset's label, or #RRGGBBAA when custom; read back by parse().
        public String format() {
            if (preset != Preset.CUSTOM) {
                return preset.label();
            }
            char[] digits = new char[9];
            digits[0] = '#';
            // custom is ARGB; rotated left 8 to write it out as RGBA hex.
            int rgba = Integer.rotateLeft(custom, 8);
            for (int i = 8; i > 0; i--) {
                digits[i] = HEX_DIGITS.charAt(rgba & 0xF);
                rgba >>>= 4;
            }
            return new String(digits);
        }
        // Parses a preset name or hex text into a Swatch; names are tried before hex digits.
        public static Swatch parse(String text, int alphaWhenAbsent) {
            if (text == null) {
                return null;
            }
            String value = text.trim();
            // Case-insensitive on name(); also matches label().
            for (Preset candidate : Preset.VALUES) {
                if (candidate != Preset.CUSTOM
                        && value.equalsIgnoreCase(candidate.name())) {
                    return of(candidate);
                }
            }
            int start = value.startsWith("#") ? 1 : 0;
            int length = value.length() - start;
            if (length != 6 && length != 8) {
                return null;
            }
            int rgb = 0;
            int alpha = 0;
            for (int i = 0; i < length; i++) {
                int digit = Character.digit(value.charAt(start + i), 16);
                if (digit < 0) {
                    return null;
                }
                if (i < 6) {
                    rgb = (rgb << 4) | digit;
                } else {
                    alpha = (alpha << 4) | digit;
                }
            }
            if (length == 6) {
                // Zero alphaWhenAbsent means prior swatch was CLEAR; treated as opaque, not invisible.
                int kept = alphaWhenAbsent & 0xFF;
                alpha = kept == 0 ? 0xFF : kept;
            }
            return custom((alpha << 24) | rgb);
        }
    }
    // An option row for a colour, held as a Swatch (a preset or a custom ARGB).
    public static final class Colour extends Value<Swatch> implements Editable {
        private final int shipped;
        private Swatch formattedFor;
        // Cached by Swatch reference; Value.set only restages a changed one.
        private String formatted;
        // shipped is the field's packed ARGB default; current is what the config holds now.
        public Colour(Component name, Component description, int shipped,
                Swatch current, Consumer<Swatch> sink) {
            super(name, description, Swatch.DEFAULT,
                    current == null ? Swatch.DEFAULT : current, sink);
            this.shipped = shipped;
        }
        // The field's own shipped colour, as packed ARGB.
        public int shipped() {
            return shipped;
        }
        // The staged colour as packed ARGB; use this, not pending(), where a number is wanted.
        public int argb() {
            return pending().resolve(shipped);
        }
        @Override
        public Kind kind() {
            return Kind.COLOUR;
        }
        // The preset's label, or #RRGGBBAA; must round-trip through parse().
        @Override
        public String format() {
            Swatch value = pending();
            if (formatted == null || value != formattedFor) {
                formatted = value.format();
                formattedFor = value;
            }
            return formatted;
        }
        @Override
        public boolean accepts(String value) {
            return Swatch.parse(value, argb() >>> 24) != null;
        }
        @Override
        public void setFromString(String value) {
            Swatch swatch = Swatch.parse(value, argb() >>> 24);
            if (swatch != null) {
                set(swatch);
            }
        }
    }
    // A row that runs an action when pressed and holds no value.
    public static final class Action extends Opt {
        private final Component text;
        private final Runnable action;
        public Action(Component name, Component description, Component text,
                Runnable action) {
            super(name, description);
            // text must never be null.
            this.text = Objects.requireNonNull(text, () -> named(name)
                    + " has no label.");
            // action must not be null.
            this.action = Objects.requireNonNull(action, () -> named(name)
                    + " has no action.");
        }
        @Override
        public Kind kind() {
            return Kind.BUTTON;
        }
        @Override
        public String format() {
            return text.getString();
        }
        public void run() {
            action.run();
        }
    }
}
