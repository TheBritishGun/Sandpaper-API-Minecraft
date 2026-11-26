package dev.sandpaper.api.theme;

import java.util.Objects;
import net.minecraft.resources.Identifier;

public sealed interface ThemeBackdrop
        permits ThemeBackdrop.GradientOnly, ThemeBackdrop.StaticTexture, ThemeBackdrop.Custom {

    record GradientOnly() implements ThemeBackdrop {
    }

    record StaticTexture(
            Identifier texture,
            int texWidth,
            int texHeight,
            float maxAlpha,
            FitMode fitMode,
            float scrollParallax) implements ThemeBackdrop {

        public StaticTexture {
            Objects.requireNonNull(texture, "texture");
            Objects.requireNonNull(fitMode, "fitMode");
            if (texWidth <= 0 || texHeight <= 0) {
                throw new IllegalArgumentException("Texture dimensions must be positive");
            }
            if (!Float.isFinite(maxAlpha) || maxAlpha < 0.0f || maxAlpha > 1.0f) {
                throw new IllegalArgumentException("Maximum alpha must be in [0, 1]");
            }
            if (!Float.isFinite(scrollParallax)) {
                throw new IllegalArgumentException("Scroll parallax must be finite");
            }
        }
    }

    enum FitMode {
        COVER,
        TILE,
        HORIZON_BAND
    }

    record Custom(BackdropPainter painter) implements ThemeBackdrop {
        public Custom {
            Objects.requireNonNull(painter, "painter");
        }
    }

    @FunctionalInterface
    interface BackdropPainter extends AutoCloseable {
        // Runs on the render thread only.
        void paint(ThemeSurface surface, BackdropContext ctx);

        @Override
        default void close() {
        }
    }

    interface BackdropContext {
        int screenWidth();

        int screenHeight();

        int contentTop();

        int contentBottom();

        double displayScrollPx();

        double sectionStartPx();

        double sectionEndPx();

        double sectionLocalProgress();

        float blendAlpha();

        double elapsedSeconds();

        double dtSeconds();

        boolean peekActive();
    }
}
