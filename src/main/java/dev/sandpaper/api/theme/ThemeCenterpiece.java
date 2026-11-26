package dev.sandpaper.api.theme;

import java.util.Objects;
import net.minecraft.resources.Identifier;

public sealed interface ThemeCenterpiece
        permits ThemeCenterpiece.None, ThemeCenterpiece.Sprite, ThemeCenterpiece.Custom {

    record None() implements ThemeCenterpiece {
    }

    record Sprite(
            Identifier texture,
            int texWidth,
            int texHeight,
            float widthScale,
            float crownYRatio,
            float scrollLiftFactor,
            float scrollSpinRadPerPx,
            float idleSpinRadPerSec) implements ThemeCenterpiece {

        private static final float MIN_CROWN_Y_RATIO = 0.49f;
        private static final float MAX_CROWN_Y_RATIO = 0.68f;

        public Sprite {
            Objects.requireNonNull(texture, "texture");
            if (texWidth <= 0 || texHeight <= 0) {
                throw new IllegalArgumentException("Texture dimensions must be positive");
            }
            if (!Float.isFinite(widthScale) || widthScale <= 0.0f) {
                throw new IllegalArgumentException("Width scale must be finite and positive");
            }
            if (!Float.isFinite(crownYRatio) || crownYRatio < MIN_CROWN_Y_RATIO || crownYRatio > MAX_CROWN_Y_RATIO) {
                throw new IllegalArgumentException("Crown Y ratio must be in [0.49, 0.68]");
            }
            if (!Float.isFinite(scrollLiftFactor) || scrollLiftFactor < 0.0f) {
                throw new IllegalArgumentException("Scroll lift factor must be finite and nonnegative");
            }
            if (!Float.isFinite(scrollSpinRadPerPx) || !Float.isFinite(idleSpinRadPerSec)) {
                throw new IllegalArgumentException("Spin rates must be finite");
            }
        }
    }

    record Custom(CenterpiecePainter painter) implements ThemeCenterpiece {
        public Custom {
            Objects.requireNonNull(painter, "painter");
        }
    }

    @FunctionalInterface
    interface CenterpiecePainter extends AutoCloseable {
        void paint(ThemeSurface surface, CenterpieceContext ctx);

        default boolean pointerDown(double mouseX, double mouseY, CenterpieceContext ctx) {
            return false;
        }

        @Override
        default void close() {
        }
    }

    interface CenterpieceContext {
        float anchorX();

        float anchorY();

        float suggestedDiameter();

        double spanLocalScrollPx();

        double spanHeightPx();

        double scrollPassProgress();

        float blendAlpha();

        double mouseX();

        double mouseY();

        boolean pointerInWindow();

        double elapsedSeconds();

        double dtSeconds();
    }
}
