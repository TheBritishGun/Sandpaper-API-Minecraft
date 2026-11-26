package dev.sandpaper.client.gui.theme;

import dev.sandpaper.api.theme.ThemeSounds;
import java.util.function.LongSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import org.jspecify.annotations.Nullable;

public final class MenuAudio {

    private static final long HOVER_COOLDOWN_NANOS = 65_000_000L;
    private static final float HOVER_PITCH = 1.65F;
    private static final float HOVER_VOLUME = 0.08F;
    private static final float STEP_FORWARD_PITCH = 1.12F;
    private static final float STEP_BACK_PITCH = 0.94F;
    private static final float STEP_VOLUME = 0.30F;
    private static final float DEFAULT_PITCH = 1.34F;
    private static final float DEFAULT_VOLUME = 0.28F;
    private static final float SLIDER_SETTLE_PITCH = 1.04F;
    private static final float SLIDER_SETTLE_VOLUME = 0.26F;
    private static final float PAGE_FORWARD_PITCH = 1.10F;
    private static final float PAGE_BACK_PITCH = 0.88F;
    private static final float PAGE_VOLUME = 0.32F;
    private static final long CROSSING_COOLDOWN_NANOS = 250_000_000L;
    private static final float CROSSING_PITCH = 1.06F;
    private static final float CROSSING_VOLUME = 0.10F;
    private static final float APPLY_PITCH = 1.15F;
    private static final float APPLY_VOLUME = 0.38F;
    private static final float CANCEL_PITCH = 0.92F;
    private static final float CANCEL_VOLUME = 0.30F;
    private static final float CORNER_ON_PITCH = 1.26F;
    private static final float CORNER_OFF_PITCH = 0.94F;
    private static final float CORNER_VOLUME = 0.34F;

    enum Cue {
        HOVER,
        OPTION_STEP,
        OPTION_DEFAULT,
        SLIDER_SETTLE,
        PAGE_JUMP,
        SECTION_CROSSING,
        APPLY,
        CANCEL,
        CORNER_TOGGLE
    }

    @FunctionalInterface
    interface SoundSink {
        void play(Cue cue, @Nullable Identifier override, float pitch, float volume);
    }

    private final LongSupplier clockNanos;
    private final SoundSink sink;
    private boolean enabled = true;
    private float volumeScale = 1.0F;
    private boolean hasHovered = false;
    private long lastHoverNanos = 0L;
    private boolean hasCrossed = false;
    private long lastCrossingNanos = 0L;

    MenuAudio(LongSupplier clockNanos, SoundSink sink) {
        this.clockNanos = clockNanos;
        this.sink = sink;
    }

    public static MenuAudio create() {
        return new MenuAudio(System::nanoTime, MenuAudio::playOnClient);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setVolumeScale(float scale) {
        volumeScale = Float.isNaN(scale) ? 0.0F : Math.clamp(scale, 0.0F, 1.0F);
    }

    public void playHover() {
        playHover(null);
    }

    public void playHover(@Nullable ThemeSounds sounds) {
        if (!audible()) {
            return;
        }
        long now = clockNanos.getAsLong();
        if (!hasHovered || now - lastHoverNanos >= HOVER_COOLDOWN_NANOS) {
            hasHovered = true;
            lastHoverNanos = now;
            sink.play(Cue.HOVER, sounds == null ? null : sounds.hover(), HOVER_PITCH, HOVER_VOLUME * volumeScale);
        }
    }

    public void playOptionStep(boolean forward) {
        playOptionStep(forward, null);
    }

    public void playOptionStep(boolean forward, @Nullable ThemeSounds sounds) {
        Identifier override = sounds == null ? null : (forward ? sounds.stepForward() : sounds.stepBack());
        play(Cue.OPTION_STEP, override, forward ? STEP_FORWARD_PITCH : STEP_BACK_PITCH, STEP_VOLUME);
    }

    public void playOptionDefault() {
        playOptionDefault(null);
    }

    public void playOptionDefault(@Nullable ThemeSounds sounds) {
        play(Cue.OPTION_DEFAULT, sounds == null ? null : sounds.resetDefault(), DEFAULT_PITCH, DEFAULT_VOLUME);
    }

    public void playSliderSettle() {
        playSliderSettle(null);
    }

    public void playSliderSettle(@Nullable ThemeSounds sounds) {
        play(Cue.SLIDER_SETTLE, sounds == null ? null : sounds.sliderSettle(),
                SLIDER_SETTLE_PITCH, SLIDER_SETTLE_VOLUME);
    }

    public void playPageJump(boolean forward) {
        playPageJump(forward, null);
    }

    public void playPageJump(boolean forward, @Nullable ThemeSounds sounds) {
        play(Cue.PAGE_JUMP, sounds == null ? null : sounds.sectionChange(),
                forward ? PAGE_FORWARD_PITCH : PAGE_BACK_PITCH, PAGE_VOLUME);
    }

    public void playSectionCrossing() {
        playSectionCrossing(null);
    }

    public void playSectionCrossing(@Nullable ThemeSounds sounds) {
        if (!audible()) {
            return;
        }
        long now = clockNanos.getAsLong();
        if (!hasCrossed || now - lastCrossingNanos >= CROSSING_COOLDOWN_NANOS) {
            hasCrossed = true;
            lastCrossingNanos = now;
            sink.play(Cue.SECTION_CROSSING, sounds == null ? null : sounds.sectionChange(),
                    CROSSING_PITCH, CROSSING_VOLUME * volumeScale);
        }
    }

    public void playApply() {
        playApply(null);
    }

    public void playApply(@Nullable ThemeSounds sounds) {
        play(Cue.APPLY, sounds == null ? null : sounds.apply(), APPLY_PITCH, APPLY_VOLUME);
    }

    public void playCancel() {
        playCancel(null);
    }

    public void playCancel(@Nullable ThemeSounds sounds) {
        play(Cue.CANCEL, sounds == null ? null : sounds.cancel(), CANCEL_PITCH, CANCEL_VOLUME);
    }

    public void playCornerToggle(boolean on) {
        playCornerToggle(on, null);
    }

    public void playCornerToggle(boolean on, @Nullable ThemeSounds sounds) {
        play(Cue.CORNER_TOGGLE, sounds == null ? null : sounds.specialToggle(),
                on ? CORNER_ON_PITCH : CORNER_OFF_PITCH, CORNER_VOLUME);
    }

    private boolean audible() {
        return enabled && volumeScale > 0.0F;
    }

    private void play(Cue cue, @Nullable Identifier override, float pitch, float volume) {
        if (audible()) {
            sink.play(cue, override, pitch, volume * volumeScale);
        }
    }

    private static void playOnClient(Cue cue, @Nullable Identifier override, float pitch, float volume) {
        Minecraft client = findClient();
        if (client == null) {
            return;
        }
        SoundManager manager = client.getSoundManager();
        if (manager != null) {
            SoundEvent event = resolveSoundEvent(manager, cue, override);
            manager.play(SimpleSoundInstance.forUI(event, pitch, volume));
        }
    }

    private static SoundEvent resolveSoundEvent(SoundManager manager, Cue cue, @Nullable Identifier override) {
        if (override != null && manager.getSoundEvent(override) != null) {
            SoundEvent registered = BuiltInRegistries.SOUND_EVENT.getValue(override);
            return registered != null ? registered : SoundEvent.createVariableRangeEvent(override);
        }
        return switch (cue) {
            case HOVER, OPTION_STEP, OPTION_DEFAULT, SLIDER_SETTLE, SECTION_CROSSING ->
                SoundEvents.UI_BUTTON_CLICK.value();
            case PAGE_JUMP -> SoundEvents.UI_LOOM_SELECT_PATTERN;
            case APPLY -> SoundEvents.UI_TOAST_IN;
            case CANCEL -> SoundEvents.UI_TOAST_OUT;
            case CORNER_TOGGLE -> SoundEvents.NOTE_BLOCK_CHIME.value();
        };
    }

    private static Minecraft findClient() {
        Minecraft client;
        try {
            client = Minecraft.getInstance();
        } catch (LinkageError unavailable) {
            client = null;
        }
        return client;
    }
}
