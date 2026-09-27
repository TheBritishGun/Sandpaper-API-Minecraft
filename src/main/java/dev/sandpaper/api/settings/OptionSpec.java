package dev.sandpaper.api.settings;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
// One row on a settings page: what it is called, what it explains, and its bound value
public sealed interface OptionSpec
        permits OptionSpec.Toggle, OptionSpec.IntSlider, OptionSpec.FloatSlider,
                OptionSpec.IntField, OptionSpec.Choice, OptionSpec.Text,
                OptionSpec.Colour, OptionSpec.Button {
    // What the row is called, as translated text, not a raw string
    Component name();
    // What the row explains, or null
    Component description();
    // Whether the reader may touch this row now; asked on the client thread, must not block
    BooleanSupplier available();
    // Sink runs once at Apply; listener runs on every edit; must not block; client thread only
    record Toggle(Component name, Component description, boolean defaultValue,
            boolean current, Consumer<Boolean> sink, BooleanSupplier available,
            Consumer<Boolean> onChange, Supplier<Boolean> showing) implements OptionSpec {
        public Toggle {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(sink, "sink");
            available = defaultAvailable(available);
            onChange = defaultOnChange(onChange);
        }
        // Same switch, following nothing
        public Toggle(Component name, Component description, boolean defaultValue,
                boolean current, Consumer<Boolean> sink, BooleanSupplier available,
                Consumer<Boolean> onChange) {
            this(name, description, defaultValue, current, sink, available, onChange,
                    null);
        }
        // Same switch, always available and watched by nobody
        public Toggle(Component name, Component description, boolean defaultValue,
                boolean current, Consumer<Boolean> sink) {
            this(name, description, defaultValue, current, sink, null, null, null);
        }
    }
    // A whole number the reader drags between two bounds
    record IntSlider(Component name, Component description, int defaultValue, int current,
            Consumer<Integer> sink, int min, int max, int step, BooleanSupplier available,
            Consumer<Integer> onChange, Function<Integer, Component> label,
            Supplier<Integer> showing) implements OptionSpec {
        // Throws IllegalArgumentException if min > max, step < 1, or default is out of bounds
        public IntSlider {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(sink, "sink");
            current = checkedIntBounds("slider", "offers", "the slider cannot show", min,
                    max, defaultValue, current);
            checkedStep(step);
            available = defaultAvailable(available);
            onChange = defaultOnChange(onChange);
        }
        // Same slider, writing its own number and following nothing
        public IntSlider(Component name, Component description, int defaultValue,
                int current, Consumer<Integer> sink, int min, int max, int step,
                BooleanSupplier available, Consumer<Integer> onChange) {
            this(name, description, defaultValue, current, sink, min, max, step,
                    available, onChange, null, null);
        }
        // Same slider, always available and watched by nobody
        public IntSlider(Component name, Component description, int defaultValue,
                int current, Consumer<Integer> sink, int min, int max, int step) {
            this(name, description, defaultValue, current, sink, min, max, step, null,
                    null, null, null);
        }
    }
    // A fraction the reader drags between two bounds
    record FloatSlider(Component name, Component description, float defaultValue,
            float current, Consumer<Float> sink, float min, float max, float step,
            BooleanSupplier available, Consumer<Float> onChange,
            Function<Float, Component> label, Supplier<Float> showing)
            implements OptionSpec {
        // Rejects non-finite bounds, min above max, non-positive step, or default outside bounds
        public FloatSlider {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(sink, "sink");
            // Comparisons against NaN are always false.
            if (!Float.isFinite(min) || !Float.isFinite(max) || !Float.isFinite(step)
                    || !Float.isFinite(defaultValue)) {
                throw new IllegalArgumentException("a slider " + min + " to " + max
                        + " by " + step + " shipping " + defaultValue + ": "
                        + "not finite.");
            }
            checkedBounds("slider", "offers", "the slider cannot show", min, max,
                    defaultValue);
            checkedStep(step);
            current = Float.isNaN(current) ? defaultValue : current < min ? min : (current > max ? max : current);
            available = defaultAvailable(available);
            onChange = defaultOnChange(onChange);
        }
        // Same slider, writing its own number and following nothing
        public FloatSlider(Component name, Component description, float defaultValue,
                float current, Consumer<Float> sink, float min, float max, float step,
                BooleanSupplier available, Consumer<Float> onChange) {
            this(name, description, defaultValue, current, sink, min, max, step,
                    available, onChange, null, null);
        }
        // Same slider, always available and watched by nobody
        public FloatSlider(Component name, Component description, float defaultValue,
                float current, Consumer<Float> sink, float min, float max, float step) {
            this(name, description, defaultValue, current, sink, min, max, step, null,
                    null, null, null);
        }
    }
    // A whole number the reader types, which refuses what is out of range
    record IntField(Component name, Component description, int defaultValue, int current,
            Consumer<Integer> sink, int min, int max, BooleanSupplier available,
            Consumer<Integer> onChange, Supplier<Integer> showing) implements OptionSpec {
        // Throws IllegalArgumentException if min is above max or default is outside the bounds
        public IntField {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(sink, "sink");
            current = checkedIntBounds("field", "accepts", "the field would refuse", min,
                    max, defaultValue, current);
            available = defaultAvailable(available);
            onChange = defaultOnChange(onChange);
        }
        // Same field, always available, watched by nobody, following nothing
        public IntField(Component name, Component description, int defaultValue,
                int current, Consumer<Integer> sink, int min, int max) {
            this(name, description, defaultValue, current, sink, min, max, null, null,
                    null);
        }
    }
    // One of several named choices, bound by position rather than by type
    record Choice(Component name, Component description, List<Component> labels,
            int defaultIndex, int current, Consumer<Integer> sink,
            BooleanSupplier available, Consumer<Integer> onChange,
            Supplier<Integer> showing) implements OptionSpec {
        // Throws IllegalArgumentException if there are no labels or defaultIndex is out of range
        public Choice {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(sink, "sink");
            labels = List.copyOf(labels);
            if (labels.isEmpty()) {
                throw new IllegalArgumentException("a choice with no choices.");
            }
            if (defaultIndex < 0 || defaultIndex >= labels.size()) {
                throw new IllegalArgumentException("the shipped choice is index "
                        + defaultIndex + " of " + labels.size() + " labels; not on "
                        + "the list.");
            }
            if (current < 0 || current >= labels.size()) {
                current = defaultIndex;
            }
            available = defaultAvailable(available);
            onChange = defaultOnChange(onChange);
        }
        // Same choice, following nothing
        public Choice(Component name, Component description, List<Component> labels,
                int defaultIndex, int current, Consumer<Integer> sink,
                BooleanSupplier available, Consumer<Integer> onChange) {
            this(name, description, labels, defaultIndex, current, sink, available,
                    onChange, null);
        }
        // Same choice, always available and watched by nobody
        public Choice(Component name, Component description, List<Component> labels,
                int defaultIndex, int current, Consumer<Integer> sink) {
            this(name, description, labels, defaultIndex, current, sink, null, null,
                    null);
        }
    }
    // Text the reader types; sink must still refuse what a hand-edited config could contain
    record Text(Component name, Component description, String defaultValue, String current,
            Consumer<String> sink, BooleanSupplier available, Consumer<String> onChange,
            Supplier<String> showing, Function<String, Component> refusing)
            implements OptionSpec {
        public Text {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(sink, "sink");
            Objects.requireNonNull(defaultValue, "defaultValue");
            current = current == null ? defaultValue : current;
            available = defaultAvailable(available);
            onChange = defaultOnChange(onChange);
        }
        // Same field, refusing nothing
        public Text(Component name, Component description, String defaultValue,
                String current, Consumer<String> sink, BooleanSupplier available,
                Consumer<String> onChange, Supplier<String> showing) {
            this(name, description, defaultValue, current, sink, available, onChange,
                    showing, null);
        }
        // Same field, following nothing
        public Text(Component name, Component description, String defaultValue,
                String current, Consumer<String> sink, BooleanSupplier available,
                Consumer<String> onChange) {
            this(name, description, defaultValue, current, sink, available, onChange,
                    null);
        }
        // Same field, always available and watched by nobody
        public Text(Component name, Component description, String defaultValue,
                String current, Consumer<String> sink) {
            this(name, description, defaultValue, current, sink, null, null, null);
        }
    }
    // A colour as text (name or hex); store exactly what is handed
    record Colour(Component name, Component description, int shippedArgb, String current,
            Consumer<String> sink, BooleanSupplier available, Consumer<String> onChange,
            Supplier<String> showing) implements OptionSpec {
        public Colour {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(sink, "sink");
            available = defaultAvailable(available);
            onChange = defaultOnChange(onChange);
        }
        // Same swatch, following nothing
        public Colour(Component name, Component description, int shippedArgb,
                String current, Consumer<String> sink, BooleanSupplier available,
                Consumer<String> onChange) {
            this(name, description, shippedArgb, current, sink, available, onChange,
                    null);
        }
        // Same swatch, always available and watched by nobody
        public Colour(Component name, Component description, int shippedArgb,
                String current, Consumer<String> sink) {
            this(name, description, shippedArgb, current, sink, null, null, null);
        }
    }
    // Runs on click before anything is staged; opening a screen drops every pending edit
    record Button(Component name, Component description, Component label, Runnable action,
            BooleanSupplier available) implements OptionSpec {
        public Button {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(action, "action");
            available = defaultAvailable(available);
        }
        // Same button, always available
        public Button(Component name, Component description, Component label,
                Runnable action) {
            this(name, description, label, action, null);
        }
    }
    private static BooleanSupplier defaultAvailable(BooleanSupplier available) {
        return available == null ? () -> true : available;
    }
    private static <T> Consumer<T> defaultOnChange(Consumer<T> onChange) {
        return onChange == null ? value -> { } : onChange;
    }
    // Bounds check shared by IntSlider and IntField: current is clamped, not refused
    private static void checkedBounds(String kind, String rejects, String cannot, Number min,
            Number max, Number defaultValue) {
        if (min.doubleValue() > max.doubleValue()) {
            throw new IllegalArgumentException("a " + kind + " from " + min + " to "
                    + max + " " + rejects + " nothing at all.");
        }
        if (defaultValue.doubleValue() < min.doubleValue()
                || defaultValue.doubleValue() > max.doubleValue()) {
            throw new IllegalArgumentException("the shipped value " + defaultValue
                    + " is outside the " + kind + "'s bounds of " + min + " to "
                    + max + "; " + cannot
                    + ".");
        }
    }
    private static void checkedStep(Number step) {
        if (step.doubleValue() <= 0) {
            throw new IllegalArgumentException("a step of " + step + " leaves the "
                    + "slider with no position to snap to.");
        }
    }
    private static int checkedIntBounds(String kind, String rejects, String cannot,
            int min, int max, int defaultValue, int current) {
        checkedBounds(kind, rejects, cannot, min, max, defaultValue);
        return Math.clamp(current, min, max);
    }
}
