package dev.sandpaper;
import dev.sandpaper.core.Cadenced;
import dev.sandpaper.core.Handle;
import dev.sandpaper.core.Lane;
import dev.sandpaper.core.Scheduler;
import dev.sandpaper.core.WorkPool;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
final class SandpaperDebugHud {
    private static final int COLOUR = 0xFF9FD3C7;
    private static final int LEFT = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int BOTTOM_MARGIN = 14;
    // Client thread only.
    private static final StringBuilder LINE = new StringBuilder(96);
    private SandpaperDebugHud() {
    }
    static void register(Scheduler scheduler, WorkPool pool, SandpaperClient client) {
        // Client thread only (write and all reads).
        AtomicReference<Cadenced<List<String>>> lines = new AtomicReference<>();
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(Sandpaper.MOD_ID, "debug"),
                (gfx, delta) -> {
                    // Called by the game only while the HUD is actually drawing.
                    boolean shown = shown();
                    Cadenced<List<String>> cadenced = lines.get();
                    if (cadenced == null) {
                        if (!shown) {
                            return;
                        }
                        cadenced = buildReadoutCadence(scheduler, pool, client);
                        lines.set(cadenced);
                    }
                    Handle handle = cadenced.handle();
                    if (handle.isPaused() == shown) {
                        handle.setPaused(!shown);
                    }
                    if (!shown) {
                        return;
                    }
                    draw(gfx, cadenced.get());
                });
    }
    private static Cadenced<List<String>> buildReadoutCadence(Scheduler scheduler,
            WorkPool pool, SandpaperClient client) {
        AtomicReference<List<String>> latest =
                new AtomicReference<>(List.of("sandpaper: starting"));
        return Cadenced.everyOtherFrame(
                scheduler,
                latest.get(),
                () -> {
                    if (!shown()) {
                        return latest.get();
                    }
                    List<String> built = buildLines(scheduler, pool, client);
                    latest.set(built);
                    return built;
                },
                // The name is the only identifier used if this job is dropped.
                "sandpaper-debug-readout");
    }
    // Caller must check shown() first; this method has no guard of its own.
    private static void draw(GuiGraphicsExtractor gfx, List<String> lines) {
        Minecraft client = Minecraft.getInstance();
        int bottom = client.getWindow().getGuiScaledHeight() - BOTTOM_MARGIN;
        int y = bottom - lines.size() * LINE_HEIGHT;
        for (String line : lines) {
            gfx.text(client.font, line, LEFT, y, COLOUR, true);
            y += LINE_HEIGHT;
        }
    }
    private static boolean shown() {
        Minecraft client = Minecraft.getInstance();
        return Sandpaper.config().debugReadout && client.player != null
                && client.getDebugOverlay().showDebugScreen();
    }
    private static List<String> buildLines(Scheduler scheduler, WorkPool pool,
                                           SandpaperClient client) {
        Scheduler.LaneStats tick = scheduler.stats(Lane.TICK);
        Scheduler.LaneStats render = scheduler.stats(Lane.RENDER);
        WorkPool.Stats ps = pool.stats();
        StringBuilder line = LINE;
        line.setLength(0);
        line.append("sandpaper  tick ").append(tick.pumps())
                .append(" pumps ").append(tick.periodic()).append('/').append(tick.sliced())
                .append(" jobs ran ").append(tick.ran())
                .append(" def ").append(tick.deferred())
                .append(" free ").append(tick.freeChunks());
        String tickJobs = line.toString();
        line.setLength(0);
        line.append("      render ").append(render.pumps())
                .append(" pumps ").append(render.periodic()).append('/').append(render.sliced())
                .append(" jobs ran ").append(render.ran())
                .append(" def ").append(render.deferred())
                .append(" free ").append(render.freeChunks());
        String renderJobs = line.toString();
        line.setLength(0);
        line.append("      tick ");
        appendMillis(line, client.tickBudget().smoothedFrameNanos());
        line.append("ms  budget ").append(client.tickBudget().budgetNanos() / 1_000)
                .append("us");
        String tickBudget = line.toString();
        line.setLength(0);
        line.append("      frame ");
        appendMillis(line, client.renderBudget().smoothedFrameNanos());
        line.append("ms  budget ").append(client.renderBudget().budgetNanos() / 1_000)
                .append("us");
        String frameBudget = line.toString();
        line.setLength(0);
        line.append("      pool ").append(ps.threadsLine())
                .append(", ").append(ps.active())
                .append(" active, ").append(ps.queued())
                .append(" queued, ").append(ps.awaitingApply())
                .append(" to apply");
        String poolState = line.toString();
        return List.of(tickJobs, renderJobs, tickBudget, frameBudget, poolState);
    }
    // +5_000 implements HALF_UP rounding; non-negative inputs only.
    private static void appendMillis(StringBuilder out, long nanos) {
        long hundredths = (nanos + 5_000L) / 10_000L;
        out.append(hundredths / 100L).append('.');
        long fraction = hundredths % 100L;
        if (fraction < 10L) {
            out.append('0');
        }
        out.append(fraction);
    }
}
