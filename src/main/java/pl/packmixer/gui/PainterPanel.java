package pl.packmixer.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Malarz pikseli: płótno z powiększeniem, ołówek / gumka / wiadro / pipeta, pędzle 1-3, lustro,
 * suwaki RGBA + hex, paleta, cofanie (Ctrl+Z). Zapis trafia do własnego packa "PackMixer-Moje"
 * w folderze resourcepacks i od razu ustawia się jako źródło tej tekstury w miksie.
 * Stan płótna jest statyczny, więc zostaje po zamknięciu GUI.
 */
public final class PainterPanel {
    public interface Host {
        void onSaved(String key, String packId, String message);

        void message(String text);
    }

    public static final String PACK_DIR = "PackMixer-Moje";
    private static final int MAX_SIZE = 128;
    private static final int[] PRESETS = {
            0xFF000000, 0xFFFFFFFF, 0xFF7F7F7F, 0xFFC3C3C3, 0xFF880015, 0xFFED1C24, 0xFFFF7F27, 0xFFFFF200,
            0xFF22B14C, 0xFF00A2E8, 0xFF3F48CC, 0xFFA349A4, 0xFFB97A57, 0xFFFFAEC9, 0xFF7B5B2E, 0xFF55FFFF
    };
    private static final int[] CHANNEL_COLORS = {0xFFE5566A, 0xFF3DDC97, 0xFF5B8CFF, 0xFFB0B7C8};
    private static final String[] CHANNEL_NAMES = {"R", "G", "B", "A"};

    private record Snap(int w, int h, int[] px) {}

    private record Hit(int x, int y, int w, int h, Runnable r) {}

    private record Slider(int x, int y, int w, int h, int ch) {}

    private enum Tool {
        PENCIL("Ołówek"), ERASER("Gumka"), FILL("Wiadro"), PICKER("Pipeta");

        final String label;

        Tool(String label) {
            this.label = label;
        }
    }

    // ---- stan trwały (przeżywa zamknięcie GUI) ----
    private static int W = 16;
    private static int H = 16;
    private static int[] px = new int[16 * 16];
    private static final ArrayDeque<Snap> UNDO = new ArrayDeque<>();
    private static String loadedKey;
    private static int color = 0xFFFFFFFF;
    private static final int[] RECENT = new int[8];
    private static Tool tool = Tool.PENCIL;
    private static int brush = 1;
    private static boolean mirror;
    private static boolean grid = true;
    private static boolean blockCat;
    private static String nameText = "moja_tekstura";
    private static boolean dirty = true;
    private static int texCounter;

    // ---- stan instancji ----
    private final MinecraftClient client;
    private final TextRenderer tr;
    private final Host host;
    private final TextFieldWidget hexField;
    private final TextFieldWidget nameField;
    private boolean syncing;
    private boolean shown;

    private NativeImageBackedTexture canvasTex;
    private NativeImageBackedTexture bgTex;
    private Identifier canvasId;
    private Identifier bgId;
    private int texW;
    private int texH;

    private final List<Hit> hits = new ArrayList<>();
    private final List<Slider> sliders = new ArrayList<>();
    private int mx;
    private int my;
    private int cx;
    private int cy;
    private int cell = 1;
    private boolean painting;
    private int lastX = -1;
    private int lastY = -1;
    private Tool strokeTool = Tool.PENCIL;
    private int dragSlider = -1;

    public PainterPanel(MinecraftClient client, TextRenderer tr, Host host) {
        this.client = client;
        this.tr = tr;
        this.host = host;
        hexField = new TextFieldWidget(tr, 0, 0, 100, 18, Text.literal("Kolor"));
        hexField.setMaxLength(8);
        hexField.setChangedListener(this::onHex);
        nameField = new TextFieldWidget(tr, 0, 0, 180, 18, Text.literal("Nazwa"));
        nameField.setMaxLength(40);
        nameField.setText(nameText);
        nameField.setChangedListener(s -> nameText = s);
        syncHex();
    }

    public List<TextFieldWidget> widgets() {
        return List.of(hexField, nameField);
    }

    public void setVisible(boolean v) {
        shown = v;
        hexField.visible = v;
        nameField.visible = v && loadedKey == null;
        if (!v) {
            hexField.setFocused(false);
            nameField.setFocused(false);
        }
    }

    public boolean anyFieldFocused() {
        return hexField.isFocused() || nameField.isFocused();
    }

    // ------------------------------------------------------------------ stan

    /** Wczytuje obraz do edycji. Zwraca komunikat błędu albo null. */
    public static String load(NativeImage img, String key) {
        int w = img.getWidth();
        int h = Math.min(img.getHeight(), w); // animacja (pionowy pasek): pierwsza klatka
        if (w > MAX_SIZE || h > MAX_SIZE) {
            return "Tekstura jest za duża do malowania (max " + MAX_SIZE + "x" + MAX_SIZE + ").";
        }
        pushUndo();
        W = w;
        H = h;
        px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) px[y * w + x] = img.getColorArgb(x, y);
        }
        loadedKey = key;
        dirty = true;
        return null;
    }

    private static void newCanvas(int size) {
        pushUndo();
        W = size;
        H = size;
        px = new int[size * size];
        loadedKey = null;
        dirty = true;
    }

    private static void pushUndo() {
        UNDO.push(new Snap(W, H, px.clone()));
        while (UNDO.size() > 50) UNDO.removeLast();
    }

    public void undo() {
        Snap s = UNDO.poll();
        if (s == null) return;
        W = s.w();
        H = s.h();
        px = s.px().clone();
        dirty = true;
    }

    private static void remember(int c) {
        if (RECENT[0] == c) return;
        System.arraycopy(RECENT, 0, RECENT, 1, RECENT.length - 1);
        RECENT[0] = c;
    }

    // ------------------------------------------------------------------ kolor

    private static int channel(int ch) {
        return switch (ch) {
            case 0 -> (color >> 16) & 255;
            case 1 -> (color >> 8) & 255;
            case 2 -> color & 255;
            default -> (color >>> 24) & 255;
        };
    }

    private void setChannel(int ch, int v) {
        v = Math.max(0, Math.min(255, v));
        switch (ch) {
            case 0 -> color = (color & 0xFF00FFFF) | (v << 16);
            case 1 -> color = (color & 0xFFFF00FF) | (v << 8);
            case 2 -> color = (color & 0xFFFFFF00) | v;
            default -> color = (color & 0x00FFFFFF) | (v << 24);
        }
        syncHex();
    }

    private void syncHex() {
        syncing = true;
        String s = String.format("%06X", color & 0xFFFFFF);
        if (((color >>> 24) & 255) != 255) s += String.format("%02X", (color >>> 24) & 255);
        hexField.setText(s);
        syncing = false;
    }

    private void onHex(String raw) {
        if (syncing) return;
        String s = raw.replace("#", "").trim();
        if (s.length() != 6 && s.length() != 8) return;
        try {
            long v = Long.parseLong(s, 16);
            if (s.length() == 6) color = (color & 0xFF000000) | (int) v;
            else color = (int) (((v & 0xFF) << 24) | (v >>> 8));
        } catch (NumberFormatException ignored) {
        }
    }

    // ------------------------------------------------------------------ tekstury płótna

    private void ensureTextures() {
        if (canvasTex != null && texW == W && texH == H) return;
        releaseTextures();
        NativeImage ci = new NativeImage(W, H, false);
        NativeImage bi = new NativeImage(W, H, false);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) bi.setColorArgb(x, y, ((x + y) & 1) == 0 ? 0xFF2B3248 : 0xFF222839);
        }
        canvasTex = new NativeImageBackedTexture(ci);
        bgTex = new NativeImageBackedTexture(bi);
        canvasTex.setFilter(false, false);
        bgTex.setFilter(false, false);
        int n = texCounter++;
        canvasId = Identifier.of("packmixer", "paint/canvas_" + n);
        bgId = Identifier.of("packmixer", "paint/bg_" + n);
        TextureManager tm = client.getTextureManager();
        tm.registerTexture(canvasId, canvasTex);
        tm.registerTexture(bgId, bgTex);
        texW = W;
        texH = H;
        dirty = true;
    }

    private void upload() {
        NativeImage img = canvasTex.getImage();
        if (img == null) return;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) img.setColorArgb(x, y, px[y * W + x]);
        }
        canvasTex.upload();
    }

    private void releaseTextures() {
        TextureManager tm = client.getTextureManager();
        if (canvasId != null) tm.destroyTexture(canvasId);
        if (bgId != null) tm.destroyTexture(bgId);
        canvasTex = null;
        bgTex = null;
        canvasId = null;
        bgId = null;
    }

    public void close() {
        releaseTextures();
    }

    // ------------------------------------------------------------------ render

    public void render(DrawContext ctx, int mouseX, int mouseY, int x, int y, int w, int h) {
        mx = mouseX;
        my = mouseY;
        hits.clear();
        sliders.clear();
        ensureTextures();
        if (dirty) {
            upload();
            dirty = false;
        }

        int rightW = Math.min(310, Math.max(236, w * 38 / 100));
        int areaW = w - rightW - 14;
        cell = Math.max(1, Math.min(areaW / W, h / H));
        int cw = cell * W;
        int ch = cell * H;
        cx = x + Math.max(0, (areaW - cw) / 2);
        cy = y + Math.max(0, (h - ch) / 2);

        Ui.outlined(ctx, cx - 2, cy - 2, cw + 4, ch + 4, Ui.PANEL, Ui.BORDER);
        ctx.drawTexture(RenderLayer::getGuiTextured, bgId, cx, cy, 0f, 0f, cw, ch, W, H, W, H);
        ctx.drawTexture(RenderLayer::getGuiTextured, canvasId, cx, cy, 0f, 0f, cw, ch, W, H, W, H);
        if (grid && cell >= 6) {
            for (int i = 0; i <= W; i++) ctx.fill(cx + i * cell, cy, cx + i * cell + 1, cy + ch, 0x28FFFFFF);
            for (int j = 0; j <= H; j++) ctx.fill(cx, cy + j * cell, cx + cw, cy + j * cell + 1, 0x28FFFFFF);
        }
        int hp = pixelAt(mouseX, mouseY);
        if (hp >= 0) {
            int hx = cx + (hp % W) * cell;
            int hy = cy + (hp / W) * cell;
            ctx.fill(hx, hy, hx + cell, hy + 1, 0xFFFFFFFF);
            ctx.fill(hx, hy + cell - 1, hx + cell, hy + cell, 0xFFFFFFFF);
            ctx.fill(hx, hy, hx + 1, hy + cell, 0xFFFFFFFF);
            ctx.fill(hx + cell - 1, hy, hx + cell, hy + cell, 0xFFFFFFFF);
        }
        String size = W + "x" + H + (loadedKey != null ? "  (edycja)" : "  (nowa)");
        ctx.drawText(tr, size, cx, cy + ch + 6, Ui.MUTED, false);

        int rx = x + areaW + 14;
        int ry = y;
        int rw = rightW;

        // narzędzia
        Tool[] tools = Tool.values();
        int tb = (rw - 12) / tools.length;
        for (int i = 0; i < tools.length; i++) {
            Tool t = tools[i];
            btn(ctx, rx + i * (tb + 4), ry, tb, 20, t.label, t == tool ? Ui.ACCENT : Ui.CARD_HOVER, () -> tool = t);
        }
        ry += 26;

        // pędzel, lustro, siatka
        ctx.drawText(tr, "Pędzel", rx, ry + 6, Ui.MUTED, false);
        for (int i = 1; i <= 3; i++) {
            int b = i;
            btn(ctx, rx + 46 + (i - 1) * 26, ry, 22, 20, String.valueOf(i), brush == i ? Ui.ACCENT : Ui.CARD_HOVER, () -> brush = b);
        }
        btn(ctx, rx + 46 + 3 * 26 + 6, ry, 62, 20, mirror ? "Lustro: tak" : "Lustro: nie", mirror ? Ui.ACCENT : Ui.CARD_HOVER, () -> mirror = !mirror);
        btn(ctx, rx + 46 + 3 * 26 + 74, ry, 58, 20, grid ? "Siatka: tak" : "Siatka: nie", grid ? Ui.ACCENT : Ui.CARD_HOVER, () -> grid = !grid);
        ry += 26;

        // podgląd koloru + hex
        Ui.round(ctx, rx, ry, 22, 18, 0xFF1B2030);
        ctx.fill(rx + 1, ry + 1, rx + 21, ry + 17, color);
        ctx.drawText(tr, "#", rx + 28, ry + 5, Ui.MUTED, false);
        hexField.setX(rx + 38);
        hexField.setY(ry);
        hexField.setWidth(Math.min(110, rw - 40));
        ry += 24;

        // suwaki RGBA
        for (int i = 0; i < 4; i++) {
            ctx.drawText(tr, CHANNEL_NAMES[i], rx, ry + 2, Ui.MUTED, false);
            int bx = rx + 14;
            int bw = rw - 14 - 30;
            Ui.round(ctx, bx, ry, bw, 12, Ui.CARD);
            int fillW = Math.max(0, bw * channel(i) / 255);
            if (fillW > 0) Ui.round(ctx, bx, ry, fillW, 12, CHANNEL_COLORS[i]);
            ctx.drawText(tr, String.valueOf(channel(i)), bx + bw + 6, ry + 2, Ui.TEXT, false);
            sliders.add(new Slider(bx, ry, bw, 12, i));
            ry += 16;
        }
        ry += 4;

        // paleta
        int sw = Math.min(22, (rw - 7 * 3) / 8);
        for (int i = 0; i < PRESETS.length; i++) {
            int sx = rx + (i % 8) * (sw + 3);
            int sy = ry + (i / 8) * (sw + 3);
            swatch(ctx, sx, sy, sw, PRESETS[i]);
        }
        ry += 2 * (sw + 3) + 2;
        for (int i = 0; i < RECENT.length; i++) {
            int sx = rx + i * (sw + 3);
            if (RECENT[i] == 0) {
                Ui.round(ctx, sx, ry, sw, sw, 0xFF1B2030);
            } else {
                swatch(ctx, sx, ry, sw, RECENT[i]);
            }
        }
        ry += sw + 8;

        // akcje
        int half = (rw - 4) / 2;
        btn(ctx, rx, ry, half, 20, "Cofnij (Ctrl+Z)", Ui.CARD_HOVER, this::undo);
        btn(ctx, rx + half + 4, ry, half, 20, "Wyczyść", Ui.CARD_HOVER, () -> {
            pushUndo();
            java.util.Arrays.fill(px, 0);
            dirty = true;
        });
        ry += 26;
        ctx.drawText(tr, "Nowa:", rx, ry + 6, Ui.MUTED, false);
        int[] sizes = {16, 32, 64};
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            btn(ctx, rx + 40 + i * 36, ry, 32, 20, String.valueOf(s), Ui.CARD_HOVER, () -> newCanvas(s));
        }
        ry += 28;

        // cel zapisu
        if (loadedKey != null) {
            ctx.drawText(tr, tr.trimToWidth("Edytujesz: " + loadedKey, rw), rx, ry + 2, Ui.GOOD, false);
            ry += 20;
        } else {
            btn(ctx, rx, ry, 70, 18, "Przedmiot", !blockCat ? Ui.ACCENT : Ui.CARD_HOVER, () -> blockCat = false);
            btn(ctx, rx + 74, ry, 46, 18, "Blok", blockCat ? Ui.ACCENT : Ui.CARD_HOVER, () -> blockCat = true);
            ry += 22;
            nameField.setX(rx);
            nameField.setY(ry);
            nameField.setWidth(rw);
            ry += 24;
        }
        nameField.visible = shown && loadedKey == null;
        btn(ctx, rx, ry, rw, 22, "Zapisz do packa " + PACK_DIR, Ui.GOOD, this::save);
        ry += 26;
        ctx.drawText(tr, tr.trimToWidth("Potem kliknij Zastosuj i przeładuj.", rw), rx, ry, 0xFF5E6985, false);
    }

    private void swatch(DrawContext ctx, int x, int y, int s, int argb) {
        Ui.round(ctx, x, y, s, s, 0xFF1B2030);
        ctx.fill(x + 1, y + 1, x + s - 1, y + s - 1, argb);
        if (Ui.in(mx, my, x, y, s, s)) ctx.fill(x, y, x + s, y + 1, 0xFFFFFFFF);
        hits.add(new Hit(x, y, s, s, () -> {
            color = argb;
            syncHex();
        }));
    }

    private void btn(DrawContext ctx, int x, int y, int w, int h, String label, int colorBg, Runnable r) {
        boolean hov = Ui.in(mx, my, x, y, w, h);
        Ui.round(ctx, x, y, w, h, hov ? Ui.lighten(colorBg, 0x20) : colorBg);
        ctx.drawCenteredTextWithShadow(tr, label, x + w / 2, y + (h - 8) / 2, 0xFFFFFFFF);
        hits.add(new Hit(x, y, w, h, r));
    }

    // ------------------------------------------------------------------ mysz

    private int pixelAt(int x, int y) {
        if (cell <= 0 || x < cx || y < cy) return -1;
        int px_ = (x - cx) / cell;
        int py_ = (y - cy) / cell;
        if (px_ >= W || py_ >= H) return -1;
        return py_ * W + px_;
    }

    public boolean mouseClicked(double mxd, double myd, int button) {
        int x = (int) Math.floor(mxd);
        int y = (int) Math.floor(myd);
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (Ui.in(x, y, h.x(), h.y(), h.w(), h.h())) {
                    h.r().run();
                    return true;
                }
            }
            for (Slider s : sliders) {
                if (Ui.in(x, y, s.x(), s.y(), s.w(), s.h())) {
                    dragSlider = s.ch();
                    setSlider(s, x);
                    return true;
                }
            }
        }
        int p = pixelAt(x, y);
        if (p < 0 || (button != 0 && button != 1)) return false;

        strokeTool = button == 1 ? Tool.ERASER : tool;
        int x0 = p % W;
        int y0 = p / W;
        if (strokeTool == Tool.PICKER) {
            int c = px[p];
            color = c == 0 ? 0xFFFFFFFF : c;
            syncHex();
            tool = Tool.PENCIL;
            return true;
        }
        pushUndo();
        if (strokeTool == Tool.FILL) {
            flood(x0, y0, color);
            remember(color);
            dirty = true;
            return true;
        }
        if (strokeTool == Tool.PENCIL) remember(color);
        painting = true;
        lastX = x0;
        lastY = y0;
        plot(x0, y0, strokeTool == Tool.ERASER ? 0 : color);
        return true;
    }

    public boolean mouseDragged(double mxd, double myd) {
        int x = (int) Math.floor(mxd);
        int y = (int) Math.floor(myd);
        if (dragSlider >= 0) {
            for (Slider s : sliders) if (s.ch() == dragSlider) setSlider(s, x);
            return true;
        }
        if (!painting) return false;
        int p = pixelAt(x, y);
        if (p < 0) return true;
        int x1 = p % W;
        int y1 = p / W;
        line(lastX, lastY, x1, y1, strokeTool == Tool.ERASER ? 0 : color);
        lastX = x1;
        lastY = y1;
        return true;
    }

    public void mouseReleased() {
        painting = false;
        lastX = -1;
        lastY = -1;
        dragSlider = -1;
    }

    private void setSlider(Slider s, int mouseX) {
        int v = Math.round((mouseX - s.x()) * 255f / Math.max(1, s.w() - 1));
        setChannel(s.ch(), v);
    }

    // ------------------------------------------------------------------ rysowanie na płótnie

    private static void set(int x, int y, int v) {
        if (x < 0 || y < 0 || x >= W || y >= H) return;
        px[y * W + x] = v;
    }

    private static void plot(int x, int y, int v) {
        int off = (brush - 1) / 2;
        for (int dy = 0; dy < brush; dy++) {
            for (int dx = 0; dx < brush; dx++) {
                int xx = x - off + dx;
                int yy = y - off + dy;
                set(xx, yy, v);
                if (mirror) set(W - 1 - xx, yy, v);
            }
        }
        dirty = true;
    }

    private static void line(int x0, int y0, int x1, int y1, int v) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            plot(x0, y0, v);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x0 += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    private static void flood(int x, int y, int newColor) {
        int target = px[y * W + x];
        if (target == newColor) return;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        q.add(y * W + x);
        while (!q.isEmpty()) {
            int i = q.poll();
            if (px[i] != target) continue;
            px[i] = newColor;
            int xx = i % W;
            int yy = i / W;
            if (xx > 0) q.add(i - 1);
            if (xx < W - 1) q.add(i + 1);
            if (yy > 0) q.add(i - W);
            if (yy < H - 1) q.add(i + W);
        }
    }

    // ------------------------------------------------------------------ zapis

    private static String sanitize(String s) {
        String t = s.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
        StringBuilder sb = new StringBuilder();
        for (char c : t.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '/') sb.append(c);
        }
        String r = sb.toString().replaceAll("/+", "/").replaceAll("^/|/$", "");
        return r.isEmpty() ? "tekstura" : r;
    }

    private void save() {
        String key = loadedKey != null
                ? loadedKey
                : "minecraft:textures/" + (blockCat ? "block" : "item") + "/" + sanitize(nameText) + ".png";
        Identifier id = Identifier.tryParse(key);
        if (id == null) {
            host.message("Nieprawidłowa nazwa tekstury.");
            return;
        }
        try {
            Path dir = client.getResourcePackDir().resolve(PACK_DIR);
            Files.createDirectories(dir);
            Path meta = dir.resolve("pack.mcmeta");
            if (!Files.exists(meta)) {
                Files.writeString(meta, "{\"pack\":{\"pack_format\":46,\"description\":\"Pack Mixer - Twoje tekstury\"}}");
            }
            Path file = dir.resolve("assets").resolve(id.getNamespace()).resolve(id.getPath());
            Files.createDirectories(file.getParent());
            NativeImage out = new NativeImage(W, H, false);
            try {
                for (int y = 0; y < H; y++) {
                    for (int x = 0; x < W; x++) out.setColorArgb(x, y, px[y * W + x]);
                }
                out.writeTo(file);
            } finally {
                out.close();
            }
            host.onSaved(key, "file/" + PACK_DIR, "Zapisano " + id.getPath() + ". Kliknij Zastosuj i przeładuj.");
        } catch (Exception e) {
            host.message("Błąd zapisu: " + e.getMessage());
        }
    }
}
