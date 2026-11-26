package dev.sandpaper.api.settings;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
public sealed interface OptionSpec
        permits OptionSpec.Toggle, OptionSpec.IntSlider, OptionSpec.FloatSlider,
                OptionSpec.IntField, OptionSpec.Choice, OptionSpec.Text,
                OptionSpec.Colour, OptionSpec.Button {
    Component name();
    Component description();
    BooleanSupplier available();
    record Toggle(Component name, Component description, boolean defaultValue,
            boolean current, Consumer<Boolean> sink, BooleanSupplier available,
            Consumer<Boolean> onChange, Supplier<Boolean> showing) implements OptionSpec {
        public Toggle {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(sink, "sink");
            available = defaultAvailable(available);
            onChange = defaultOnChange(onChange);
        }
        public Toggle(Component name, Component description, boolean defaultValue,
                boolean current, Consumer<Boolean> sink, BooleanSupplier available,
                Consumer<Boolean> onChange) {
            this(name, description, defaultValue, current, sink, available, onChange,
                    null);
        }
        public Toggle(Component name, Component description, boolean defaultValue,
                boolean current, Consumer<Boolean> sink) {
            this(name, description, defaultValue, current, sink, null, null, null);
        }
    }
    record IntSlider(Component name, Component description, int defaultValue, int current,
            Consumer<Integer> sink, int min, int max, int step, BooleanSupplier available,
            Consumer<Integer> onChange, Function<Integer, Component> label,
            Supplier<Integer> showing) implements OptionSpec {
        public IntSlider {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(sink, "sink");
            current = checkedIntBounds("slider", "offers", "the slider cannot show", min,
                    max, defaultValue, current);
            checkedStep(step);
            available = defaultAvailable(available);
            onChange = defaultOnChange(onChange);
        }
        public IntSlider(Component name, Component description, int defaultValue,
                int current, Consumer<Integer> sink, int min, int max, int step,
                BooleanSupplier available, Consumer<Integer> onChange) {
            this(name, description, defaultValue, current, sink, min, max, step,
                    available, onChange, null, null);
        }
        public IntSlider(Component name, Component description, int defaultValue,
                int current, Consumer<Integer> sink, int min, int max, int step) {
            this(name, description, defaultValue, current, sink, min, max, step, null,
                    null, null, null);
        }
    }
    record FloatSlider(Component name, Component description, float defaultValue,
            float current, Consumer<Float> sink, float min, float max, float step,
            BooleanSupplier available, Consumer<Float> onChange,
            Function<Float, Component> label, Supplier<Float> showing)
            implements OptionSpec {
        public FloatSlider {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(sink, "sink");
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
        public FloatSlider(Component name, Component description, float defaultValue,
                float current, Consumer<Float> sink, float min, float max, float step,
                BooleanSupplier available, Consumer<Float> onChange) {
            this(name, description, defaultValue, current, sink, min, max, step,
                    available, onChange, null, null);
        }
        public FloatSlider(Component name, Component description, float defaultValue,
                float current, Consumer<Float> sink, float min, float max, float step) {
            this(name, description, defaultValue, current, sink, min, max, step, null,
                    null, null, null);
        }
    }
    record IntField(Component name, Component description, int defaultValue, int current,
            Consumer<Integer> sink, int min, int max, BooleanSupplier available,
            Consumer<Integer> onChange, Supplier<Integer> showing) implements OptionSpec {
        public IntField {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(sink, "sink");
            current = checkedIntBounds("field", "accepts", "the field would refuse", min,
                    max, defaultValue, current);
            available = defaultAvailable(available);
            onChange = defaultOnChange(onChange);
        }
        public IntField(Component name, Component description, int defaultValue,
                int current, Consumer<Integer> sink, int min, int max) {
            this(name, description, defaultValue, current, sink, min, max, null, null,
                    null);
        }
    }
    record Choice(Component name, Component description, List<Component> labels,
            int defaultIndex, int current, Consumer<Integer> sink,
            BooleanSupplier available, Consumer<Integer> onChange,
            Supplier<Integer> showing) implements OptionSpec {
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
        public Choice(Component name, Component description, List<Component> labels,
                int defaultIndex, int current, Consumer<Integer> sink,
                BooleanSupplier available, Consumer<Integer> onChange) {
            this(name, description, labels, defaultIndex, current, sink, available,
                    onChange, null);
        }
        public Choice(Component name, Component description, List<Component> labels,
                int defaultIndex, int current, Consumer<Integer> sink) {
            this(name, description, labels, defaultIndex, current, sink, null, null,
                    null);
        }
    }
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
        public Text(Component name, Component description, String defaultValue,
                String current, Consumer<String> sink, BooleanSupplier available,
                Consumer<String> onChange, Supplier<String> showing) {
            this(name, description, defaultValue, current, sink, available, onChange,
                    showing, null);
        }
        public Text(Component name, Component description, String defaultValue,
                String current, Consumer<String> sink, BooleanSupplier available,
                Consumer<String> onChange) {
            this(name, description, defaultValue, current, sink, available, onChange,
                    null);
        }
        public Text(Component name, Component description, String defaultValue,
                String current, Consumer<String> sink) {
            this(name, description, defaultValue, current, sink, null, null, null);
        }
    }
    record Colour(Component name, Component description, int shippedArgb, String current,
            Consumer<String> sink, BooleanSupplier available, Consumer<String> onChange,
            Supplier<String> showing) implements OptionSpec {
        public Colour {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(sink, "sink");
            available = defaultAvailable(available);
            onChange = defaultOnChange(onChange);
        }
        public Colour(Component name, Component description, int shippedArgb,
                String current, Consumer<String> sink, BooleanSupplier available,
                Consumer<String> onChange) {
            this(name, description, shippedArgb, current, sink, available, onChange,
                    null);
        }
        public Colour(Component name, Component description, int shippedArgb,
                String current, Consumer<String> sink) {
            this(name, description, shippedArgb, current, sink, null, null, null);
        }
    }
    record Button(Component name, Component description, Component label, Runnable action,
            BooleanSupplier available) implements OptionSpec {
        public Button {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(action, "action");
            available = defaultAvailable(available);
        }
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
