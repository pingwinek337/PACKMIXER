package pl.packmixer.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;

/** Paleta kolorów i drobne helpery do rysowania ładnego, ciemnego GUI. */
public final class Ui {
    public static final int BG_TOP = 0xFF0B0E14;
    public static final int BG_BOTTOM = 0xFF161C2B;
    public static final int PANEL = 0xFF171D2B;
    public static final int CARD = 0xFF212A3E;
    public static final int CARD_HOVER = 0xFF2A3550;
    public static final int CARD_SELECTED = 0xFF2D3B66;
    public static final int ACCENT = 0xFF5B8CFF;
    public static final int GOOD = 0xFF3DDC97;
    public static final int DANGER = 0xFFE5566A;
    public static final int WARN = 0xFFFFC857;
    public static final int TEXT = 0xFFE8ECF5;
    public static final int MUTED = 0xFF8D98B0;
    public static final int BORDER = 0xFF2E3850;

    private static final int[] PALETTE = {
            0xFF5B8CFF, 0xFF3DDC97, 0xFFFFC857, 0xFFFF7A59, 0xFFB57BFF, 0xFF4FD1E8, 0xFFFF6FB5, 0xFF9BDB4D
    };

    private Ui() {}

    /** Prostokąt z lekko zaokrąglonymi rogami. */
    public static void round(DrawContext c, int x, int y, int w, int h, int color) {
        if (w < 3 || h < 3) {
            c.fill(x, y, x + w, y + h, color);
            return;
        }
        c.fill(x + 1, y, x + w - 1, y + h, color);
        c.fill(x, y + 1, x + w, y + h - 1, color);
    }

    public static void outlined(DrawContext c, int x, int y, int w, int h, int fill, int border) {
        round(c, x, y, w, h, border);
        round(c, x + 1, y + 1, w - 2, h - 2, fill);
    }

    public static int lighten(int argb, int amount) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.min(255, ((argb >> 16) & 0xFF) + amount);
        int g = Math.min(255, ((argb >> 8) & 0xFF) + amount);
        int b = Math.min(255, (argb & 0xFF) + amount);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int packColor(String id) {
        return PALETTE[Math.floorMod(id.hashCode(), PALETTE.length)];
    }

    public static boolean in(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Szachownica pod miniaturą, żeby było widać przezroczystość. */
    public static void checker(DrawContext c, int x, int y, int size) {
        c.fill(x, y, x + size, y + size, 0xFF1B2030);
        int cell = Math.max(8, size / 4);
        int light = 0xFF252C40;
        for (int cy = 0; cy * cell < size; cy++) {
            for (int cx = 0; cx * cell < size; cx++) {
                if (((cx + cy) & 1) == 0) continue;
                int x0 = x + cx * cell;
                int y0 = y + cy * cell;
                c.fill(x0, y0, Math.min(x0 + cell, x + size), Math.min(y0 + cell, y + size), light);
            }
        }
    }

    /** Rysuje pierwszą klatkę tekstury (animacje to pionowe paski) wpasowaną w kwadrat box x box. */
    public static void drawThumb(DrawContext c, TexCache.Thumb t, int x, int y, int box) {
        if (t == null) return;
        int rw = t.w();
        int rh = t.h() > t.w() ? t.w() : t.h();
        float s = Math.min((float) box / rw, (float) box / rh);
        int dw = Math.max(1, Math.round(rw * s));
        int dh = Math.max(1, Math.round(rh * s));
        int dx = x + (box - dw) / 2;
        int dy = y + (box - dh) / 2;
        c.drawTexture(RenderLayer::getGuiTextured, t.id(), dx, dy, 0f, 0f, dw, dh, rw, rh, t.w(), t.h());
    }

    public static void scrollbar(DrawContext c, int x, int y, int h, int scroll, int max, int viewH) {
        if (max <= 0) return;
        c.fill(x, y, x + 4, y + h, 0xFF121722);
        int total = max + viewH;
        int thumb = Math.max(16, (int) ((long) h * viewH / total));
        int ty = y + (int) ((long) (h - thumb) * scroll / max);
        round(c, x, ty, 4, thumb, ACCENT);
    }

    /** Płynne przewijanie: cel i wartość bieżąca dążąca do celu. */
    public static final class SmoothScroll {
        private float target;
        private float current;

        public void by(float delta) {
            target += delta;
        }

        public void reset() {
            target = 0;
            current = 0;
        }

        public void tick(int max) {
            target = Math.max(0, Math.min(max, target));
            current += (target - current) * 0.3f;
            if (Math.abs(target - current) < 0.5f) current = target;
        }

        public int get() {
            return Math.round(current);
        }
    }
}
