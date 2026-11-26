package dev.sandpaper.client.gui;
import dev.sandpaper.Sandpaper;
import dev.sandpaper.api.settings.OptionSpec;
import dev.sandpaper.api.settings.SettingsContributor;
import dev.sandpaper.api.settings.SettingsGroup;
import dev.sandpaper.api.settings.SettingsPage;
import dev.sandpaper.client.gui.options.Opt;
import dev.sandpaper.client.gui.options.OptPage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public final class SettingsContributions {
    private static final Logger LOGGER = LoggerFactory.getLogger(Sandpaper.MOD_ID);
    private static final String UNREADABLE_COLOUR = "which is not a preset name and not six "
            + "or eight hex digits";
    private static final String UNNAMED = "(unnamed mod)";
    private static final Comparator<EntrypointContainer<SettingsContributor>> ORDER =
            Comparator.<EntrypointContainer<SettingsContributor>>comparingInt(
                            container -> Sandpaper.MOD_ID.equals(modIdOf(container)) ? 0 : 1)
                    .thenComparing(SettingsContributions::modIdOf);
    private static long gatherings;
    private static final Map<EntrypointContainer<SettingsContributor>, String> MOD_IDS =
            new IdentityHashMap<>();
    private static final ThreadLocal<SaveFailureReport> ownSaveFailureReport = new ThreadLocal<>();
    private static final AtomicLong ownSaveFailureReports = new AtomicLong();
    private final List<Contributed> contributed;
    private final List<Saver> savers;
    private final Gathering gathering;
    private List<SaveFailureReport> lastSaveReports = List.of();
    private boolean saveFailureUnreported;
    private SettingsContributions(List<Contributed> contributed, List<Saver> savers) {
        this(contributed, savers, new Gathering());
    }
    private SettingsContributions(List<Contributed> contributed, List<Saver> savers,
            Gathering gathering) {
        this.contributed = List.copyOf(contributed);
        this.savers = List.copyOf(savers);
        this.gathering = gathering;
    }
    public record Contributed(String modId, Identifier id, OptPage page) {
    }
    private record Saver(String modId, SettingsContributor contributor) {
    }
    private static final class SaveFailureReport {
        private final AtomicLong reports = new AtomicLong();
        private boolean reported() {
            return reports.get() > 0;
        }
    }
    private static final class Gathering {
        private final List<Live> rows = new ArrayList<>();
        private boolean away;
    }
    static final class SaveRound {
        private final List<SaveFailureReport> made;
        private final long at;
        private SaveRound(List<SaveFailureReport> made) {
            this.made = made;
            this.at = reports(made);
        }
        boolean reportedSince() {
            return reports(made) > at;
        }
        private static long reports(List<SaveFailureReport> made) {
            long total = 0;
            for (SaveFailureReport one : made) {
                total += one.reports.get();
            }
            return total;
        }
    }
    private record Conditional(Opt row, BooleanSupplier rule) {
    }
    private static final class Live implements SettingsContributor.Rules {
        private static final int PASSES = 13;
        private final String modId;
        private final List<Conditional> conditional = new ArrayList<>();
        private final List<Opt.Value<?>> edits = new ArrayList<>();
        private final List<Runnable> following = new ArrayList<>();
        private int followingRuleLimit;
        private int bound = PASSES;
        private int restageBound = 1;
        private boolean[] followerReported = {};
        private boolean[] ruleReported = {};
        private long revision;
        private final Thread built = Thread.currentThread();
        private final long gathering = gatherings;
        private final Gathering mine;
        private boolean missed;
        private boolean sweeping;
        private boolean moved;
        private int telling;
        private final AtomicBoolean toldOffThread = new AtomicBoolean();
        private Live(String modId) {
            this(modId, new Gathering());
        }
        private Live(String modId, Gathering mine) {
            this.modId = modId;
            this.mine = mine;
        }
        private void drawnAgain() {
            if (missed) {
                missed = false;
                refresh();
            }
        }
        @Override
        public void askAgain() {
            Thread caller = Thread.currentThread();
            if (caller != built) {
                if (toldOffThread.compareAndSet(false, true)) {
                    LOGGER.error("a mod, {}, asked for its own settings rows to be asked "
                            + "again from thread {}; built on "
                            + "{}. Refused. "
                            + "Call from the client thread.",
                            modId, caller.getName(), built.getName());
                }
                return;
            }
            sweepIfDrawn();
        }
        private void sweepIfDrawn() {
            if (!drawnNowOrHeldForLater()) {
                return;
            }
            if (telling > 0) {
                return;
            }
            refresh();
        }
        private void markOrSweepIfDrawn() {
            if (sweeping) {
                moved = true;
                return;
            }
            if (!drawnNowOrHeldForLater()) {
                return;
            }
            refresh();
        }
        private boolean drawnNowOrHeldForLater() {
            if (gathering != gatherings) {
                return false;
            }
            if (mine.away) {
                missed = true;
                return false;
            }
            return true;
        }
        private Opt holds(OptionSpec option, Opt row) {
            if (option.available().getClass().getNestHost() != OptionSpec.class) {
                conditional.add(new Conditional(row, option.available()));
            }
            if (row instanceof Opt.Value<?> value) {
                edits.add(value);
            }
            return row;
        }
        private void follows(Runnable restage) {
            following.add(restage);
            followingRuleLimit = conditional.size();
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        private void arm() {
            bound = Math.max(PASSES, conditional.size() + 1);
            restageBound = following.size() + 1;
            followerReported = new boolean[following.size()];
            ruleReported = new boolean[conditional.size()];
            Consumer<Object> refresher = ignored -> markOrSweepIfDrawn();
            for (Opt.Value<?> edit : edits) {
                ((Opt.Value) edit).onChange(refresher);
            }
            refresh();
        }
        private void refresh() {
            if (sweeping) {
                moved = true;
                return;
            }
            sweeping = true;
            revision++;
            try {
                int restages = 0;
                int asks = 0;
                int unsettledPasses = 0;
                boolean unsettled = false;
                boolean finalRestaged = false;
                while (true) {
                    if (restages == 0) {
                        do {
                            moved = false;
                            restage();
                            restages++;
                        } while (moved && restages < restageBound);
                        unsettled = moved;
                        if (unsettled) {
                            unsettledPasses = restages;
                        }
                    }
                    moved = false;
                    ask();
                    asks++;
                    if (!moved || asks >= bound) {
                        if (!moved && asks > 1 && !finalRestaged) {
                            finalRestaged = true;
                            int finalRestages = 0;
                            boolean finalRestageMoved = false;
                            do {
                                moved = false;
                                restage();
                                finalRestages++;
                                finalRestageMoved |= moved;
                            } while (moved && finalRestages < restageBound);
                            boolean finalRestageConverged = !moved;
                            unsettled |= moved;
                            if (moved) {
                                unsettledPasses = finalRestages;
                            }
                            if (finalRestageMoved && asks < bound) {
                                moved = false;
                                ask();
                                asks++;
                                if (moved && asks < bound) {
                                    if (finalRestageConverged) {
                                        moved = false;
                                        restage();
                                    }
                                    continue;
                                }
                            }
                        }
                        break;
                    }
                }
                if (unsettled || moved && asks >= bound) {
                    LOGGER.error("{} did not settle after "
                            + "{} passes.", modId, unsettled ? unsettledPasses : asks);
                }
            } finally {
                sweeping = false;
            }
        }
        private void restage() {
            guard(following, followerReported, Runnable::run, "what it should be showing");
        }
        private void ask() {
            guard(conditional, ruleReported,
                    one -> one.row().setAvailable(one.rule().getAsBoolean()),
                    "whether the reader may change it");
        }
        private void ask(int rules) {
            if (rules == conditional.size()) {
                ask();
                return;
            }
            guard(conditional.subList(0, rules), ruleReported,
                    one -> one.row().setAvailable(one.rule().getAsBoolean()),
                    "whether the reader may change it");
        }
        private <T> void guard(List<T> rows, boolean[] reported, Consumer<T> asked,
                String question) {
            int at = 0;
            for (T one : rows) {
                try {
                    asked.accept(one);
                } catch (Throwable broken) {
                    if (!reported[at]) {
                        reported[at] = true;
                        LOGGER.error("a settings row contributed by {} threw while being "
                                + "asked {}; the row is left as it was.", modId, question, broken);
                    }
                }
                at++;
            }
        }
        private <T, R extends Opt.Value<T>> R watched(R row, Consumer<T> listener) {
            boolean[] told = { false };
            row.onChange(staged -> {
                telling++;
                try {
                    listener.accept(staged);
                } catch (Throwable broken) {
                    if (!told[0]) {
                        told[0] = true;
                        LOGGER.error("a settings row contributed by {} threw while being "
                                + "told that the reader had changed it; the notification "
                                + "is lost.", modId, broken);
                    }
                } finally {
                    telling--;
                }
            });
            return row;
        }
        private Runnable pressed(Runnable action) {
            return () -> {
                telling++;
                try {
                    action.run();
                } catch (Throwable broken) {
                    LOGGER.error("a settings button contributed by {} threw when it was "
                            + "pressed. It threw partway through; "
                            + "an earlier effect may still stand.",
                            modId, broken);
                } finally {
                    telling--;
                }
                sweepIfDrawn();
            };
        }
    }
    static void closed() {
        gatherings++;
    }
    public static SettingsContributions gather() {
        gatherings++;
        MOD_IDS.clear();
        Gathering made = new Gathering();
        List<EntrypointContainer<SettingsContributor>> found;
        try {
            found = FabricLoader.getInstance().getEntrypointContainers(
                    SettingsContributor.ENTRYPOINT, SettingsContributor.class);
            if (found.size() > 1) {
                var entries = found.iterator();
                var previous = entries.next();
                while (entries.hasNext()) {
                    var current = entries.next();
                    if (ORDER.compare(previous, current) > 0) {
                        found = new ArrayList<>(found);
                        found.sort(ORDER);
                        break;
                    }
                    previous = current;
                }
            }
        } catch (Throwable broken) {
            LOGGER.error("Sandpaper could not list settings contributors; "
                    + "no mod is to blame.", broken);
            return new SettingsContributions(List.of(), List.of(), made);
        }
        List<Contributed> accepted = new ArrayList<>();
        List<Saver> savers = new ArrayList<>();
        Set<Identifier> taken = new HashSet<>();
        for (EntrypointContainer<SettingsContributor> container : found) {
            String modId = modIdOf(container);
            List<Contributed> mine = new ArrayList<>();
            Set<Identifier> mineIds = new HashSet<>();
            Live live = new Live(modId, made);
            SettingsContributor contributor;
            try {
                contributor = container.getEntrypoint();
                List<SettingsPage> offered = contributor.pages();
                if (offered == null) {
                    throw new NullPointerException("pages() returned null");
                }
                for (SettingsPage page : offered) {
                    if (taken.contains(page.id()) || !mineIds.add(page.id())) {
                        LOGGER.error("{} contributes a settings page {}, already taken. "
                                + "The page is left out. Use your own mod id as the "
                                + "namespace.", modId, page.id());
                        continue;
                    }
                    mine.add(new Contributed(modId, page.id(), translate(page, live)));
                }
            } catch (Throwable broken) {
                LOGGER.error("{} threw while contributing its settings pages; "
                        + "its section is left out.", modId, broken);
                continue;
            }
            if (mine.isEmpty()) {
                continue;
            }
            live.arm();
            try {
                contributor.screenOpened(live);
            } catch (Throwable broken) {
                LOGGER.error("{} threw when given its own refresh callback. "
                        + "A row it greys stays greyed until touched.", modId, broken);
            }
            taken.addAll(mineIds);
            accepted.addAll(mine);
            made.rows.add(live);
            savers.add(new Saver(modId, contributor));
        }
        return new SettingsContributions(accepted, savers, made);
    }
    void screenRemoved() {
        gathering.away = true;
    }
    void screenShown() {
        gathering.away = false;
        for (Live live : gathering.rows) {
            live.drawnAgain();
        }
    }
    public List<Contributed> contributed() {
        return contributed;
    }
    public List<OptPage> pages() {
        OptPage[] drawn = new OptPage[contributed.size()];
        for (int index = 0; index < drawn.length; index++) {
            drawn[index] = contributed.get(index).page();
        }
        return List.of(drawn);
    }
    public boolean applyAll() {
        boolean everythingStaged = true;
        for (Contributed one : contributed) {
            for (OptPage.Group group : one.page().groups()) {
                for (Opt option : group.options()) {
                    try {
                        option.apply();
                    } catch (Throwable broken) {
                        everythingStaged = false;
                        LOGGER.error("a settings row contributed by {} threw while "
                                + "handing its value back; the setting is not "
                                + "staged.", one.modId(),
                                broken);
                    }
                }
            }
        }
        return everythingStaged;
    }
    public boolean saveAll() {
        Sandpaper.carryBackgroundSaveFailures(SettingsContributions::backToTheSaveThatStartedIt);
        boolean everythingSaved = true;
        boolean unreported = false;
        List<SaveFailureReport> made = new ArrayList<>(savers.size());
        for (Saver saver : savers) {
            SaveFailureReport report = new SaveFailureReport();
            made.add(report);
            ownSaveFailureReport.set(report);
            try {
                if (!saver.contributor().save()) {
                    everythingSaved = false;
                    unreported |= !report.reported();
                    LOGGER.error("{} could not write its settings. "
                            + "They will not survive a restart.", saver.modId());
                }
            } catch (Throwable broken) {
                everythingSaved = false;
                unreported |= !report.reported();
                LOGGER.error("{} threw while writing its settings. "
                        + "They will not survive a restart.", saver.modId(), broken);
            } finally {
                ownSaveFailureReport.remove();
            }
        }
        lastSaveReports = List.copyOf(made);
        saveFailureUnreported = unreported;
        return everythingSaved;
    }
    SaveRound saveRound() {
        return new SaveRound(lastSaveReports);
    }
    private static Runnable backToTheSaveThatStartedIt(Runnable onFailure) {
        SaveFailureReport started = ownSaveFailureReport.get();
        if (started == null) {
            return onFailure;
        }
        return () -> {
            SaveFailureReport had = ownSaveFailureReport.get();
            ownSaveFailureReport.set(started);
            try {
                onFailure.run();
            } finally {
                if (had == null) {
                    ownSaveFailureReport.remove();
                } else {
                    ownSaveFailureReport.set(had);
                }
            }
        };
    }
    public static void reportedOwnSaveFailure() {
        ownSaveFailureReports.incrementAndGet();
        SaveFailureReport report = ownSaveFailureReport.get();
        if (report != null) {
            report.reports.incrementAndGet();
        }
    }
    static long ownSaveFailureReports() {
        return ownSaveFailureReports.get();
    }
    boolean saveFailureUnreported() {
        return saveFailureUnreported;
    }
    private static String modIdOf(EntrypointContainer<SettingsContributor> container) {
        String modId = MOD_IDS.get(container);
        if (modId == null && !MOD_IDS.containsKey(container)) {
            modId = readModId(container);
            MOD_IDS.put(container, modId);
        }
        return modId;
    }
    private static String readModId(EntrypointContainer<SettingsContributor> container) {
        try {
            return container.getProvider().getMetadata().getId();
        } catch (Throwable broken) {
            return UNNAMED;
        }
    }
    private static OptPage translate(SettingsPage page, Live live) {
        List<OptPage.Group> groups = new ArrayList<>(page.groups().size());
        for (SettingsGroup group : page.groups()) {
            List<Opt> rows = new ArrayList<>(group.options().size());
            for (OptionSpec option : group.options()) {
                rows.add(row(option, live));
            }
            groups.add(new OptPage.Group(group.title(), group.description(), rows));
        }
        return new OptPage(page.title(), groups,
                page.icon() == null ? null : page.icon().toString());
    }
    private static Opt row(OptionSpec option, Live live) {
        Opt built = switch (option) {
            case OptionSpec.Toggle toggle -> flag(toggle, live);
            case OptionSpec.IntSlider slider -> slider(slider, live);
            case OptionSpec.FloatSlider slider -> fraction(slider, live);
            case OptionSpec.IntField number -> typed(number, live);
            case OptionSpec.Choice choice -> cycle(choice, live);
            case OptionSpec.Text text -> line(text, live);
            case OptionSpec.Colour colour -> swatch(colour, live);
            case OptionSpec.Button button -> new Opt.Action(button.name(),
                    button.description(), button.label(), live.pressed(button.action()));
        };
        return live.holds(option, built);
    }
    private static Opt flag(OptionSpec.Toggle toggle, Live live) {
        Opt.Bool row = live.watched(new Opt.Bool(toggle.name(), toggle.description(),
                toggle.defaultValue(), toggle.current(), toggle.sink()),
                toggle.onChange());
        follow(live, toggle.showing(), row::set);
        return row;
    }
    private static Opt slider(OptionSpec.IntSlider slider, Live live) {
        Function<Integer, Component> label = slider.label();
        return ranged(label, live,
                () -> new Opt.Ints(slider.name(), slider.description(),
                        slider.defaultValue(), slider.current(), slider.sink(),
                        slider.min(), slider.max(), slider.step()),
                text -> Opt.Ints.labelled(slider.name(), slider.description(),
                        slider.defaultValue(), slider.current(), slider.sink(),
                slider.min(), slider.max(), slider.step(), text),
                slider.onChange(), slider.showing());
    }
    private static Opt fraction(OptionSpec.FloatSlider slider, Live live) {
        Function<Float, Component> label = slider.label();
        return ranged(label, live,
                () -> new Opt.Floats(slider.name(), slider.description(),
                        slider.defaultValue(), slider.current(), slider.sink(),
                        slider.min(), slider.max(), slider.step()),
                text -> Opt.Floats.labelled(slider.name(), slider.description(),
                        slider.defaultValue(), slider.current(), slider.sink(),
                slider.min(), slider.max(), slider.step(), text),
                slider.onChange(), slider.showing());
    }
    private static <T, R extends Opt.Value<T> & Opt.Ranged> R ranged(
            Function<T, Component> label, Live live, Supplier<R> plain,
            Function<Function<T, String>, R> labelled, Consumer<T> onChange,
            Supplier<T> showing) {
        R row = live.watched(label == null
                        ? plain.get()
                        : labelled.apply(askedWhileDrawing(label, live.modId,
                                "what to write beside its name",
                                "the host writes the number", live)),
                onChange);
        follow(live, showing, value -> row.setNumber(((Number) value).doubleValue()));
        return row;
    }
    private static <T> void follow(Live live, Supplier<T> showing, Consumer<T> set) {
        if (showing != null) {
            live.follows(() -> {
                T value = showing.get();
                if (value != null) {
                    set.accept(value);
                }
            });
        }
    }
    private static Opt typed(OptionSpec.IntField number, Live live) {
        Opt.Ints row = live.watched(Opt.Ints.typed(number.name(), number.description(),
                number.defaultValue(), number.current(), number.sink(), number.min(),
                number.max()), number.onChange());
        follow(live, number.showing(), value -> row.setNumber(value));
        return row;
    }
    private static Opt line(OptionSpec.Text text, Live live) {
        Function<String, Component> refusing = text.refusing();
        Opt.Text row = live.watched(refusing == null
                        ? new Opt.Text(text.name(), text.description(),
                                text.defaultValue(), text.current(), text.sink())
                        : Opt.Text.refusing(text.name(), text.description(),
                                text.defaultValue(), text.current(), text.sink(),
                                askedWhileDrawing(refusing, live.modId,
                                        "whether it takes what the reader typed",
                                        "the host takes it; the sink may "
                                                + "refuse it", live)),
                text.onChange());
        follow(live, text.showing(), row::set);
        return row;
    }
    private static <T> Function<T, String> askedWhileDrawing(Function<T, Component> answer,
            String modId, String asked, String instead, Live live) {
        return new DrawnAnswer<>(answer, modId, asked, instead, live);
    }
    private static final class DrawnAnswer<T> implements Function<T, String> {
        private final Function<T, Component> answer;
        private final String modId;
        private final String asked;
        private final String instead;
        private final Live live;
        private boolean told;
        private boolean cached;
        private long revision;
        private T previous;
        private String written;
        private DrawnAnswer(Function<T, Component> answer, String modId, String asked,
                String instead, Live live) {
            this.answer = answer;
            this.modId = modId;
            this.asked = asked;
            this.instead = instead;
            this.live = live;
        }
        @Override
        public String apply(T value) {
            long current = live.revision;
            if (cached && revision == current && !live.sweeping
                    && Objects.equals(previous, value)) {
                return written;
            }
            String result = ask(value);
            previous = value;
            written = result;
            revision = current;
            cached = !live.sweeping && current == live.revision;
            return result;
        }
        private String ask(T value) {
            try {
                Component written = answer.apply(value);
                return written == null ? null : written.getString();
            } catch (Throwable broken) {
                if (!told) {
                    told = true;
                    LOGGER.error("a settings row contributed by {} threw while being asked "
                            + "{}; {}.",
                            modId, asked, instead,
                            broken);
                }
                return null;
            }
        }
    }
    private static Opt cycle(OptionSpec.Choice choice, Live live) {
        List<Component> labels = choice.labels();
        Opt.Cycle<Integer> row = live.watched(Opt.Cycle.overIndices(choice.name(),
                choice.description(), labels.size(), choice.defaultIndex(),
                choice.current(), choice.sink(), labels::get, () -> live.revision),
                choice.onChange());
        follow(live, choice.showing(), row::setIndex);
        return row;
    }
    private static Opt swatch(OptionSpec.Colour colour, Live live) {
        Opt.Swatch current = Opt.Swatch.parse(colour.current(), colour.shippedArgb() >>> 24);
        if (current == null && colour.current() != null) {
            LOGGER.warn("a settings row contributed by {}, called {}, is stored as the "
                    + "colour {}, " + UNREADABLE_COLOUR
                    + "; the row uses its shipped colour.",
                    live.modId, colour.name().getString(), colour.current());
        }
        Consumer<String> sink = colour.sink();
        Consumer<String> told = colour.onChange();
        Opt.Colour row = live.watched(new Opt.Colour(colour.name(), colour.description(),
                colour.shippedArgb(), current, staged -> sink.accept(staged.format())),
                staged -> told.accept(staged.format()));
        boolean[] reported = { false };
        follow(live, colour.showing(), value -> {
                Opt.Swatch staged = Opt.Swatch.parse(value, row.argb() >>> 24);
                if (staged != null) {
                    row.set(staged);
                } else if (!reported[0]) {
                    reported[0] = true;
                    LOGGER.warn("a settings row contributed by {}, called {}, is showing "
                            + "the colour {}, " + UNREADABLE_COLOUR
                            + "; the row keeps its colour.",
                            live.modId, colour.name().getString(), value);
                }
            });
        return row;
    }
}
