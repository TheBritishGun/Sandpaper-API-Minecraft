package dev.sandpaper.client.gui;
import dev.sandpaper.Sandpaper;
import dev.sandpaper.client.gui.options.Opt;
import java.util.function.UnaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// Owns input, saving and narration; all drawing is done by AeroPainter
public final class AeroConfigScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger(Sandpaper.MOD_ID);
    // No lwjgl jar on this classpath
    private static final int KEY_SPACE = 32;
    private static final int KEY_ENTER = 257;
    private static final int KEY_TAB = 258;
    private static final int KEY_BACKSPACE = 259;
    private static final int KEY_DELETE = 261;
    private static final int KEY_RIGHT = 262;
    private static final int KEY_LEFT = 263;
    private static final int KEY_DOWN = 264;
    private static final int KEY_UP = 265;
    private static final int KEY_HOME = 268;
    private static final int KEY_END = 269;
    private static final int KEY_KP_ENTER = 335;
    private static final SystemToast.SystemToastId STAGE_FAILED_TOAST =
            new SystemToast.SystemToastId();
    private static final SystemToast.SystemToastId SETTINGS_NOT_SAVED_TOAST =
            new SystemToast.SystemToastId();
    private static final SystemToast.SystemToastId REBUILD_REFUSED_TOAST =
            new SystemToast.SystemToastId();
    private final Screen parent;
    private final SettingsContributions contributions;
    private final UnaryOperator<Font> lettering;
    private final AeroPainter painter;
    private AeroGameSurface surface;
    private Font lastPaintedFont;
    private Language lastLanguage;
    private boolean awaitingDoneSave;
    private Applied doneApplied;
    private boolean retired;
    // The same screen, lettered by a supplied font operator
    public AeroConfigScreen(Screen parent, Component title, UnaryOperator<Font> lettering) {
        this(parent, title, lettering, null);
    }
    private AeroConfigScreen(Screen parent, Component title, UnaryOperator<Font> lettering,
            Identifier pageInView) {
        super(title);
        this.parent = parent;
        this.lettering = lettering;
        this.contributions = SettingsContributions.gather();
        this.painter = new AeroPainter(title.getString(), contributions.pages());
        if (pageInView != null) {
            for (int index = 0; index < contributions.contributed().size(); index++) {
                if (pageInView.equals(contributions.contributed().get(index).id())) {
                    painter.restorePage(index);
                    break;
                }
            }
        }
        // Apply logs failures but never closes the screen
        this.painter.onApply(this::applyAndReport);
        this.painter.onDone(this::applyAndClose);
        // Already guarded in SettingsContributions.row
        this.painter.onButton(Opt.Action::run);
    }
    @Override
    protected void init() {
        contributions.screenShown();
        painter.layout(width, height);
        AeroGameSurface.forgetTextWidths();
        painter.forgetFooterWrap();
    }
    // The painter draws blur and wash here instead
    @Override
    public void extractBackground(GuiGraphicsExtractor gfx, int mouseX, int mouseY,
            float partial) {
    }
    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY,
            float partial) {
        if (pollPendingDoneSave()) {
            return;
        }
        Language language = Language.getInstance();
        if (language != lastLanguage) {
            painter.refreshTitle(getTitle());
            painter.layout(width, height);
            lastLanguage = language;
        } else {
            painter.layoutFrame(width, height);
        }
        surface = AeroGameSurface.of(surface, gfx, minecraft, lettering,
                AeroTheme.needsBlurredBackdrop(AeroPainter.treatment()));
        if (surface.font() != lastPaintedFont) {
            painter.forgetFooterWrap();
            lastPaintedFont = surface.font();
        }
        painter.paint(surface, mouseX, mouseY, System.nanoTime());
        super.extractRenderState(gfx, mouseX, mouseY, partial);
    }
    // Rebuilds the screen keeping staged edits; sinks run early, nothing is written to disk
    public void rebuildKeepingEdits(Runnable between) {
        rebuildKeepingEdits(between, this::reportRebuildRefused);
    }
    public void rebuildKeepingEdits(Runnable between, Runnable refused) {
        if (awaitingDoneSave) {
            LOGGER.error("rebuild refused, a Done save is pending.");
            if (refused != null) {
                refused.run();
            }
            return;
        }
        int pageIndex = painter.page();
        Identifier pageInView = pageIndex >= 0 && pageIndex < contributions.contributed().size()
                ? contributions.contributed().get(pageIndex).id() : null;
        // Rebuild proceeds even if staging fails for a row; the failure is only logged
        if (!stage()) {
            LOGGER.error("a row failed to hand its value to its mod; rebuilt without it.");
            reportStageFailed();
        }
        if (between != null) {
            between.run();
        }
        client().setScreenAndShow(new AeroConfigScreen(parent, getTitle(), lettering, pageInView));
    }
    // Moves pending values into configs; writes nothing to disk. False if any row failed
    private boolean stage() {
        painter.commitEditing();
        return contributions.applyAll();
    }
    // Always runs both stage and save
    private Applied applyNow() {
        boolean staged = stage();
        boolean written = contributions.saveAll();
        return new Applied(staged, written, contributions.saveFailureUnreported(),
                contributions.saveRound());
    }
    private void applyAndReport() {
        Applied applied = applyNow();
        if (!applied.staged() || !applied.written()) {
            reportFailures(applied.staged(), applied.unreported());
        }
    }
    // Closes only if staging and writing both succeed; a failing sink keeps it open forever
    private void applyAndClose() {
        if (awaitingDoneSave) {
            return;
        }
        Applied applied = applyNow();
        if (Sandpaper.hasPendingBackgroundSave()) {
            awaitingDoneSave = true;
            doneApplied = applied;
            return;
        }
        finishDone(applied.staged(), applied.written(), applied.unreported());
    }
    private record Applied(boolean staged, boolean written, boolean unreported,
            SettingsContributions.SaveRound round) {
    }
    private void finishDone(boolean staged, boolean written, boolean unreported) {
        if (staged && written) {
            close();
            return;
        }
        LOGGER.error("a setting did not reach its mod; screen stays open.");
        reportFailures(staged, unreported);
    }
    private void reportFailures(boolean staged, boolean unreported) {
        if (!staged) {
            reportStageFailed();
        }
        if (unreported) {
            SystemToast.addOrUpdate(client().gui.toastManager(), SETTINGS_NOT_SAVED_TOAST,
                    Component.translatableWithFallback("sandpaper.toast.settings_not_saved.title",
                            "Settings not saved"),
                    Component.translatableWithFallback("sandpaper.toast.settings_not_saved.body",
                            "Some settings not saved. Check the log"));
        }
    }
    private boolean pollPendingDoneSave() {
        if (!awaitingDoneSave || Sandpaper.hasPendingBackgroundSave()) {
            return false;
        }
        awaitingDoneSave = false;
        boolean saved = Sandpaper.lastBackgroundSaveSucceeded();
        boolean written = doneApplied.written() && saved;
        boolean unreported = doneApplied.unreported() || (!saved
                && !doneApplied.round().reportedSince());
        finishDone(doneApplied.staged(), written, unreported);
        return doneApplied.staged() && written;
    }
    private void reportStageFailed() {
        SystemToast.addOrUpdate(client().gui.toastManager(), STAGE_FAILED_TOAST,
                Component.translatableWithFallback("sandpaper.toast.stage_failed.title",
                        "Setting not carried over"),
                Component.translatableWithFallback("sandpaper.toast.stage_failed.body",
                        "Setting not applied. Check the log"));
    }
    // Escape commits nothing; every contributor's file is left exactly as it was
    @Override
    public void onClose() {
        if (awaitingDoneSave) {
            return;
        }
        close();
    }
    private void close() {
        retire();
        client().setScreenAndShow(parent);
    }
    @Override
    public void removed() {
        contributions.screenRemoved();
        if (client().gui.screen() == null) {
            retire();
            return;
        }
        client().execute(() -> {
            if (client().gui.screen() == parent) {
                retire();
            }
        });
    }
    private void retire() {
        if (!retired) {
            retired = true;
            SettingsContributions.closed();
        }
    }
    private void reportRebuildRefused() {
        SystemToast.addOrUpdate(client().gui.toastManager(), REBUILD_REFUSED_TOAST,
                Component.translatableWithFallback("sandpaper.toast.rebuild_refused.title",
                        "Screen not rebuilt"),
                Component.translatableWithFallback("sandpaper.toast.rebuild_refused.body",
                        "Not changed. Try again when saving finishes"));
    }
    // Minecraft field is set before any callback fires
    private Minecraft client() {
        return minecraft == null ? Minecraft.getInstance() : minecraft;
    }
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (awaitingDoneSave) {
            return true;
        }
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        return painter.click((int) event.x(), (int) event.y(), event.button());
    }
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (awaitingDoneSave) {
            return true;
        }
        if (painter.dragging()) {
            painter.drag((int) event.x(), (int) event.y());
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }
    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (awaitingDoneSave) {
            return true;
        }
        painter.release();
        return super.mouseReleased(event);
    }
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (awaitingDoneSave) {
            return true;
        }
        if (painter.scroll(deltaY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }
    @Override
    public boolean charTyped(CharacterEvent event) {
        if (awaitingDoneSave) {
            return true;
        }
        return painter.codepointTyped(event.codepoint()) || super.charTyped(event);
    }
    // Checks paste, copy and cut via the event, before the switch
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (awaitingDoneSave) {
            return true;
        }
        if (event.isPaste()) {
            // getClipboard never returns null and surrogates are already filtered out
            if (painter.pasteTarget()
                    && painter.paste(client().keyboardHandler.getClipboard())) {
                return true;
            }
        } else {
            boolean cut = event.isCut();
            if (event.isCopy() || cut) {
                String taken = cut ? painter.cut() : painter.copy();
                if (taken != null) {
                    client().keyboardHandler.setClipboard(taken);
                    return true;
                }
            }
        }
        switch (event.key()) {
            case KEY_BACKSPACE -> {
                if (painter.backspace()) {
                    return true;
                }
            }
            case KEY_DELETE -> {
                if (painter.delete()) {
                    return true;
                }
            }
            case KEY_HOME -> {
                if (painter.caretHome()) {
                    return true;
                }
            }
            case KEY_END -> {
                if (painter.caretEnd()) {
                    return true;
                }
            }
            case KEY_UP -> {
                return painter.arrow(0, -1);
            }
            case KEY_DOWN -> {
                return painter.arrow(0, 1);
            }
            case KEY_LEFT -> {
                return painter.arrow(-1, 0);
            }
            case KEY_RIGHT -> {
                return painter.arrow(1, 0);
            }
            case KEY_TAB -> {
                painter.cycleZone(event.hasShiftDown() ? -1 : 1);
                return true;
            }
            case KEY_ENTER, KEY_KP_ENTER -> {
                if (painter.activate()) {
                    return true;
                }
            }
            case KEY_SPACE -> {
                if (!typing() && painter.activate()) {
                    return true;
                }
            }
            default -> {
            }
        }
        return super.keyPressed(event);
    }
    // True while typing; lets space be a character instead of triggering activate
    private boolean typing() {
        return painter.pasteTarget();
    }
    // Reports title and focus; narration() handles an empty page list safely
    @Override
    protected void updateNarrationState(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getTitle());
        output.add(NarratedElementType.USAGE, Component.literal(painter.narration()));
    }
}
