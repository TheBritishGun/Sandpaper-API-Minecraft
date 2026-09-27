package dev.sandpaper;
import com.mojang.blaze3d.platform.Window;
import dev.sandpaper.client.SandpaperConfigScreen;
import dev.sandpaper.client.SandpaperKeys;
import dev.sandpaper.config.SandpaperConfig;
import dev.sandpaper.core.FrameBudget;
import dev.sandpaper.core.Handle;
import dev.sandpaper.core.JobSpec;
import dev.sandpaper.core.Lane;
import dev.sandpaper.core.Priority;
import dev.sandpaper.core.Scheduler;
import dev.sandpaper.core.WorkPool;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public final class SandpaperClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(Sandpaper.MOD_ID);
    private static final String BUILD_RECORD = "/sandpaper-build.json";
    private static final int BUILD_RECORD_BYTES = 1024;
    private static final int APPLIES_PER_SLICE = 2;
    private static final int REFRESH_RATE_POLL_FRAMES = 300;
    private static final int RENDER_LANE_SILENT_TICKS = 40;
    private final Scheduler scheduler = Sandpaper.scheduler();
    private WorkPool workPool;
    // Set before the pool can call back into it.
    private Handle applyResults;
    private FrameBudget renderBudget;
    private FrameBudget tickBudget;
    private long tickStartNanos;
    private long tickPumpNanos;
    // Client thread only, both where this is set and where it is read.
    private boolean renderLanePumpedSinceLastTick;
    private int renderLaneQuietTicks;
    private boolean renderLaneSilenceReported;
    // Zero means unknown; pacedFramerate treats it the same as unset.
    private int refreshRateHz;
    // The first frame polls.
    private int framesSinceRefreshRatePoll = REFRESH_RATE_POLL_FRAMES;
    private int lastFramerateLimit = -1;
    private int lastRefreshRate = -1;
    private boolean lastVsync;
    private double lastRenderShare = -1.0;
    private int lastRenderCeiling = -1;
    private int lastRenderFloor = -1;
    // Ignores the file for budgets and threads; always runs on the shipped values.
    // Only this class reads it.
    private static final SandpaperConfig SCHEDULING = new SandpaperConfig().normalise();
    private double lastTickShare = -1.0;
    private int lastTickCeiling = -1;
    private int lastTickFloor = -1;
    @Override
    public void onInitializeClient() {
        LOGGER.info("Sandpaper is up: {}.",
                buildIdentity());
        Sandpaper.loadConfig(FabricLoader.getInstance().getConfigDir().resolve("sandpaper.json"));
        SandpaperConfigScreen.applyColours(Sandpaper.config());
        SandpaperConfig config = SCHEDULING;
        workPool = config.workerThreads > 0
                ? new WorkPool(config.workerThreads, config.queueCapacity)
                : new WorkPool(WorkPool.defaultThreads(), config.queueCapacity);
        tickBudget = tickBudgetFor(config);
        lastTickShare = config.tickShare;
        lastTickCeiling = config.tickCeilingMicros;
        lastTickFloor = config.floorMicros;
        // The three zeros mean nothing is known yet.
        renderBudget = budgetFor(0, 0, 0, config);
        Sandpaper.install(workPool);
        // Registers the settings keybind; calling this twice is not safe.
        SandpaperKeys.register();
        // Pauses itself when there is nothing to apply.
        applyResults = scheduler.register(
                JobSpec.everyPump(Lane.TICK)
                        .withPriority(Priority.HIGH)
                        .neverDrop()
                        .withLabel("sandpaper-apply-results"),
                tick -> {
                    long deadline = System.nanoTime() + tick.remainingNanos();
                    int appliesLeft = SCHEDULING.applyPerTick;
                    // Runs at least one slice; count and the clock both bound the burst.
                    do {
                        int slice = Math.min(APPLIES_PER_SLICE, appliesLeft);
                        appliesLeft -= slice;
                        workPool.drainCompleted(slice);
                    } while (appliesLeft > 0 && System.nanoTime() - deadline < 0L);
                    // Sets paused before the second check, to not miss a result that just arrived.
                    if (!workPool.hasPending()) {
                        applyResults.setPaused(true);
                        if (workPool.hasPending()) {
                            applyResults.setPaused(false);
                        }
                    }
                });
        // Must come after applyResults is set.
        workPool.wakeOnResult(this::resumeApplyResults);
        ClientTickEvents.START_CLIENT_TICK.register(this::onStartTick);
        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
        // The only place the work pool is closed.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> workPool.close());
        HudElementRegistry.addFirst(
                Identifier.fromNamespaceAndPath(Sandpaper.MOD_ID, "pump"),
                this::onFrame);
        SandpaperDebugHud.register(scheduler, workPool, this);
    }
    // Runs on whichever thread queued a result or a drop callback.
    private void resumeApplyResults() {
        if (applyResults.isPaused()) {
            applyResults.setPaused(false);
        }
    }
    private void onStartTick(Minecraft client) {
        // Read at the start of the tick, not reused from the end of the last one.
        tickStartNanos = System.nanoTime();
    }
    private void onEndTick(Minecraft client) {
        retargetTickIfSettingsChanged();
        long tickEndNanos = System.nanoTime();
        if (tickStartNanos != 0L) {
            // Includes the prior pump's cost, closing the accounting loop.
            tickBudget.sample(tickEndNanos - tickStartNanos + tickPumpNanos);
        }
        scheduler.pump(Lane.TICK, tickBudget.budgetNanos());
        tickPumpNanos = System.nanoTime() - tickEndNanos;
        // Watches for the RENDER lane going silent because the HUD is hidden.
        if (client.level != null) {
            if (renderLanePumpedSinceLastTick) {
                renderLaneQuietTicks = 0;
                renderLaneSilenceReported = false;
            } else if (renderLaneQuietTicks < RENDER_LANE_SILENT_TICKS) {
                renderLaneQuietTicks++;
            }
            renderLanePumpedSinceLastTick = false;
            if (shouldReportRenderSilence(renderLaneQuietTicks, RENDER_LANE_SILENT_TICKS,
                    renderLaneSilenceReported)) {
                renderLaneSilenceReported = true;
                LOGGER.error("Sandpaper's RENDER lane has not pumped for {} ticks (about {} s). "
                        + "Likely cause: HUD hidden (F1).",
                        renderLaneQuietTicks, renderLaneQuietTicks / 20.0);
            }
        } else {
            renderLaneQuietTicks = 0;
            renderLanePumpedSinceLastTick = false;
            renderLaneSilenceReported = false;
        }
    }
    private static boolean shouldReportRenderSilence(int quietTicks, int bound,
            boolean alreadyReported) {
        return !alreadyReported && quietTicks >= bound;
    }
    private void onFrame(GuiGraphicsExtractor gfx, DeltaTracker delta) {
        Minecraft client = Minecraft.getInstance();
        retargetIfFramerateChanged(client);
        renderBudget.sample(client.getFrameTimeNs());
        scheduler.pump(Lane.RENDER, renderBudget.budgetNanos());
        // The only place this flag is set true.
        renderLanePumpedSinceLastTick = true;
    }
    // Rebuilds the render budget when the display or its settings change.
    private void retargetIfFramerateChanged(Minecraft client) {
        Window window = client.getWindow();
        if (client.options == null || window == null) {
            return;
        }
        if (++framesSinceRefreshRatePoll >= REFRESH_RATE_POLL_FRAMES) {
            framesSinceRefreshRatePoll = 0;
            refreshRateHz = window.getRefreshRate();
        }
        int limit = client.options.framerateLimit().get();
        boolean vsync = client.options.enableVsync().get();
        SandpaperConfig config = SCHEDULING;
        if (limit != lastFramerateLimit || refreshRateHz != lastRefreshRate
                || vsync != lastVsync || config.renderShare != lastRenderShare
                || config.renderCeilingMicros != lastRenderCeiling
                || config.floorMicros != lastRenderFloor) {
            lastFramerateLimit = limit;
            lastRefreshRate = refreshRateHz;
            lastVsync = vsync;
            lastRenderShare = config.renderShare;
            lastRenderCeiling = config.renderCeilingMicros;
            renderBudget = budgetFor(pacedFramerate(limit, vsync, refreshRateHz),
                    limit, refreshRateHz, config);
            // Reads floorMicros after budgetFor normalises it in place.
            lastRenderFloor = config.floorMicros;
        }
    }
    private static boolean isRealCap(int limit, int cutoff) {
        return limit > 0 && limit < cutoff;
    }
    // The rate actually achievable, from the limit, vsync and the display refresh rate.
    private static int pacedFramerate(int limit, boolean vsync, int refreshRateHz) {
        if (refreshRateHz <= 0) {
            return limit;
        }
        boolean limited = isRealCap(limit, Options.UNLIMITED_FRAMERATE_CUTOFF);
        if (!limited) {
            return refreshRateHz;
        }
        return vsync ? Math.min(limit, refreshRateHz) : limit;
    }
    // Never throws; the result is for printing, not parsing.
    private static String buildIdentity() {
        try (InputStream in = SandpaperClient.class.getResourceAsStream(BUILD_RECORD)) {
            if (in == null) {
                return "no " + BUILD_RECORD + " on the classpath ("
                        + "source checkout, not a built jar)";
            }
            return new String(in.readNBytes(BUILD_RECORD_BYTES), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::strip)
                    .collect(Collectors.joining(" "));
        } catch (IOException | RuntimeException unreadable) {
            return BUILD_RECORD + " on the classpath, unreadable: "
                    + unreadable;
        }
    }
    private void retargetTickIfSettingsChanged() {
        SandpaperConfig config = SCHEDULING;
        if (config.tickShare != lastTickShare
                || config.tickCeilingMicros != lastTickCeiling
                || config.floorMicros != lastTickFloor) {
            lastTickShare = config.tickShare;
            lastTickCeiling = config.tickCeilingMicros;
            tickBudget = tickBudgetFor(config);
            // Reads floorMicros after tickBudgetFor normalises it in place.
            lastTickFloor = config.floorMicros;
        }
    }
    // refreshRateHz is the raw value from the display panel.
    private static FrameBudget budgetFor(int pacedLimit, int limit, int refreshRateHz,
            SandpaperConfig config) {
        config.floorMicros = config.normalisedFloorMicros();
        boolean capped = isRealCap(limit, Options.UNLIMITED_FRAMERATE_CUTOFF);
        long target;
        if (capped) {
            target = 1_000_000_000L / pacedLimit;
        } else if (refreshRateHz > 0) {
            target = 1_000_000_000L / refreshRateHz;
        } else {
            target = 16_666_666L;
        }
        long ceiling = Math.max(config.floorNanos(), config.renderCeilingNanos());
        return new FrameBudget(target, config.renderShare, config.floorNanos(), ceiling);
    }
    private static FrameBudget tickBudgetFor(SandpaperConfig config) {
        config.floorMicros = config.normalisedFloorMicros();
        long ceiling = Math.max(config.floorNanos(), config.tickCeilingNanos());
        return new FrameBudget(50_000_000L, config.tickShare, config.floorNanos(), ceiling);
    }
    FrameBudget renderBudget() {
        return renderBudget;
    }
    FrameBudget tickBudget() {
        return tickBudget;
    }
}
