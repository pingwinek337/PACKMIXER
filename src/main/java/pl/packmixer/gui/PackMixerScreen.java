package pl.packmixer.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import pl.packmixer.config.MixConfig;
import pl.packmixer.license.LicenseCodec;
import pl.packmixer.license.LicenseManager;
import pl.packmixer.pack.PackIndex;
import pl.packmixer.pack.PackIndex.Category;
import pl.packmixer.pack.PackIndex.Source;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Główne GUI: zakładki Packi / Bloki / Przedmioty / Moby / GUI. */
public class PackMixerScreen extends Screen {

    private enum Tab {
        PACKS("Packi", null),
        BLOCKS("Bloki", Category.BLOCKS),
        ITEMS("Przedmioty", Category.ITEMS),
        ENTITIES("Moby", Category.ENTITIES),
        GUI("GUI", Category.GUI);

        final String label;
        final Category cat;

        Tab(String label, Category cat) {
            this.label = label;
            this.cat = cat;
        }
    }

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private final Screen parent;
    private final PackIndex index;
    private final TexCache tex = new TexCache();
    private final Map<String, String> work = new LinkedHashMap<>(MixConfig.get().overrides);

    private Tab tab = Tab.PACKS;
    private final EnumMap<Tab, String> selected = new EnumMap<>(Tab.class);
    private String searchText = "";
    private TextFieldWidget search;
    private List<String> filtered = List.of();
    private int seenVersion = -1;
    private int bulkIdx = 0;

    private final Ui.SmoothScroll packScroll = new Ui.SmoothScroll();
    private final Ui.SmoothScroll listScroll = new Ui.SmoothScroll();
    private final Ui.SmoothScroll detailScroll = new Ui.SmoothScroll();

    private final List<Hit> hits = new ArrayList<>();
    private String tooltip;
    private int mx, my;
    private int clipTop, clipBottom;
    private String statusMsg = "";
    private long statusUntil;

    // layout
    private int L, R, tabY, cTop, cBot, listW;

    public PackMixerScreen(Screen parent) {
        super(Text.literal("Pack Mixer"));
        this.parent = parent;
        this.index = new PackIndex(net.minecraft.client.MinecraftClient.getInstance().getResourcePackManager());
    }

    // ------------------------------------------------------------------ init / lifecycle

    @Override
    protected void init() {
        search = new TextFieldWidget(textRenderer, 0, 0, 100, 20, Text.literal("Szukaj"));
        search.setPlaceholder(Text.literal("Szukaj tekstury..."));
        search.setMaxLength(64);
        search.setText(searchText);
        search.setChangedListener(s -> {
            searchText = s;
            refilter();
            listScroll.reset();
        });
        addDrawableChild(search);
        search.visible = false;
        refilter();
    }

    @Override
    public void removed() {
        tex.close();
        index.close();
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // własne tło w render()
    }

    // ------------------------------------------------------------------ helpers

    private void layout() {
        L = 16;
        R = width - 16;
        tabY = 44;
        cTop = tabY + 32;
        cBot = height - 46;
        listW = Math.max(190, (int) ((R - L) * 0.38f));
        if (clipBottom == 0) clipBottom = height;
    }

    private boolean hover(int x, int y, int w, int h) {
        return Ui.in(mx, my, x, y, w, h) && my >= clipTop && my < clipBottom;
    }

    private void hit(int x, int y, int w, int h, Runnable r) {
        int y0 = Math.max(y, clipTop);
        int y1 = Math.min(y + h, clipBottom);
        if (y1 <= y0) return;
        hits.add(new Hit(x, y0, w, y1 - y0, r));
    }

    private void beginClip(DrawContext ctx, int x, int y, int w, int h) {
        ctx.enableScissor(x, y, x + w, y + h);
        clipTop = y;
        clipBottom = y + h;
    }

    private void endClip(DrawContext ctx) {
        ctx.disableScissor();
        clipTop = 0;
        clipBottom = height;
    }

    private void button(DrawContext ctx, int x, int y, int w, int h, String label, int color, boolean enabled, String tip, Runnable r) {
        boolean hov = enabled && hover(x, y, w, h);
        int bg = !enabled ? 0xFF262B38 : hov ? Ui.lighten(color, 0x20) : color;
        Ui.round(ctx, x, y, w, h, bg);
        ctx.drawCenteredTextWithShadow(textRenderer, label, x + w / 2, y + (h - 8) / 2, enabled ? 0xFFFFFFFF : 0xFF77809A);
        if (enabled) hit(x, y, w, h, r);
        if (hov && tip != null) tooltip = tip;
    }

    private void scaledText(DrawContext ctx, String s, int x, int y, float scale, int color) {
        ctx.getMatrices().push();
        ctx.getMatrices().translate(x, y, 0);
        ctx.getMatrices().scale(scale, scale, 1f);
        ctx.drawText(textRenderer, s, 0, 0, color, true);
        ctx.getMatrices().pop();
    }

    private void setStatus(String msg) {
        statusMsg = msg;
        statusUntil = System.currentTimeMillis() + 4000;
    }

    private boolean dirty() {
        return !work.equals(MixConfig.get().overrides);
    }

    private int countOverrides(Tab t) {
        if (t.cat == null) return work.size();
        int n = 0;
        for (String k : work.keySet()) if (PackIndex.categoryOf(k) == t.cat) n++;
        return n;
    }

    private static String shortName(String key) {
        int colon = key.indexOf(':');
        String ns = key.substring(0, colon);
        String path = key.substring(colon + 1);
        int slash = path.indexOf('/');
        int slash2 = slash < 0 ? -1 : path.indexOf('/', slash + 1);
        String name = slash2 < 0 ? path : path.substring(slash2 + 1);
        if (name.endsWith(".png")) name = name.substring(0, name.length() - 4);
        return ns.equals("minecraft") ? name : ns + ":" + name;
    }

    private Source effective(String key) {
        String ov = work.get(key);
        if (ov != null) {
            Source s = index.byId(ov);
            if (s != null) return s;
        }
        return index.autoSource(key, client.getResourcePackManager().getEnabledIds());
    }

    private void switchTab(Tab t) {
        if (tab == t) return;
        tab = t;
        searchText = "";
        if (search != null) search.setText("");
        listScroll.reset();
        detailScroll.reset();
        refilter();
    }

    private void refilter() {
        if (tab.cat == null) {
            filtered = List.of();
            return;
        }
        String q = searchText.toLowerCase(Locale.ROOT).trim();
        List<String> out = new ArrayList<>();
        for (String key : index.textures(tab.cat).keySet()) {
            if (q.isEmpty() || shortName(key).toLowerCase(Locale.ROOT).contains(q)) out.add(key);
        }
        filtered = out;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!LicenseManager.isLicensed()) {
            client.setScreen(new LicenseScreen(parent, () -> client.setScreen(new PackMixerScreen(parent))));
            return;
        }
        mx = mouseX;
        my = mouseY;
        hits.clear();
        tooltip = null;
        clipTop = 0;
        clipBottom = height;
        layout();
        tex.beginFrame();
        if (index.version != seenVersion) {
            seenVersion = index.version;
            refilter();
        }

        ctx.fillGradient(0, 0, width, height, Ui.BG_TOP, Ui.BG_BOTTOM);
        drawHeader(ctx);
        drawTabs(ctx);

        search.visible = tab != Tab.PACKS;
        if (tab == Tab.PACKS) drawPacksTab(ctx);
        else drawCategoryTab(ctx);

        drawFooter(ctx);
        super.render(ctx, mouseX, mouseY, delta);
        if (tooltip != null) ctx.drawTooltip(textRenderer, Text.literal(tooltip), mouseX, mouseY);
    }

    private void drawHeader(DrawContext ctx) {
        scaledText(ctx, "PACK MIXER", L, 10, 1.6f, Ui.TEXT);
        ctx.fill(L, 10, L + 3, 32, Ui.ACCENT);
        ctx.drawText(textRenderer, "Łącz wiele texturepacków w jeden", L + 118, 18, Ui.MUTED, false);

        LicenseCodec.License lic = LicenseManager.current();
        String s = "Licencja aktywna" + (lic != null && !lic.owner().isEmpty() ? " - " + lic.owner() : "");
        int w = textRenderer.getWidth(s);
        ctx.fill(R - w - 14, 19, R - w - 8, 25, Ui.GOOD);
        ctx.drawText(textRenderer, s, R - w, 18, Ui.GOOD, false);
    }

    private void drawTabs(DrawContext ctx) {
        int x = L;
        for (Tab t : Tab.values()) {
            int n = countOverrides(t);
            String label = t.label;
            String badge = n > 0 ? " " + n : "";
            int w = textRenderer.getWidth(label + badge) + 28;
            int h = 26;
            boolean act = t == tab;
            boolean hov = Ui.in(mx, my, x, tabY, w, h);
            if (act) Ui.round(ctx, x, tabY, w, h, Ui.PANEL);
            else if (hov) Ui.round(ctx, x, tabY, w, h, Ui.CARD);
            int color = act ? Ui.TEXT : hov ? Ui.TEXT : Ui.MUTED;
            ctx.drawText(textRenderer, label, x + 14, tabY + 9, color, false);
            if (n > 0) ctx.drawText(textRenderer, badge, x + 14 + textRenderer.getWidth(label), tabY + 9, Ui.GOOD, false);
            if (act) ctx.fill(x + 6, tabY + h - 2, x + w - 6, tabY + h, Ui.ACCENT);
            hits.add(new Hit(x, tabY, w, h, () -> switchTab(t)));
            x += w + 4;
        }
        ctx.fill(L, tabY + 26, R, tabY + 27, Ui.BORDER);
    }

    // ---------------------------------------------------------------- Packi

    private void drawPacksTab(DrawContext ctx) {
        List<Source> srcs = index.sources;
        int ext = 0;
        for (Source s : srcs) if (!s.vanilla) ext++;

        String info;
        int infoColor = Ui.MUTED;
        if (ext == 0) {
            info = "Nie znaleziono texturepacków. Wrzuć pliki .zip do folderu resourcepacks.";
            infoColor = Ui.WARN;
        } else {
            info = "Wykryte texturepacki: " + ext + (index.done ? "" : "   (skanowanie" + dots() + ")");
        }
        ctx.drawText(textRenderer, info, L, cTop + 4, infoColor, false);
        button(ctx, R - 150, cTop - 2, 150, 20, "Otwórz folder packów", Ui.CARD_HOVER, true, null, this::openPackFolder);

        int top = cTop + 26;
        int view = cBot - top;
        int cardH = 58;
        int gap = 6;
        int total = srcs.size() * (cardH + gap);
        int max = Math.max(0, total - view);
        packScroll.tick(max);
        int off = packScroll.get();
        int cw = R - L - (max > 0 ? 10 : 0);

        beginClip(ctx, L, top, R - L, view);
        for (int i = 0; i < srcs.size(); i++) {
            int y = top + i * (cardH + gap) - off;
            if (y + cardH < top || y > cBot) continue;
            drawPackCard(ctx, srcs.get(i), L, y, cw, cardH);
        }
        endClip(ctx);
        Ui.scrollbar(ctx, R - 4, top, view, off, max, view);
    }

    private void drawPackCard(DrawContext ctx, Source s, int x, int y, int w, int h) {
        boolean hov = hover(x, y, w, h);
        Ui.round(ctx, x, y, w, h, hov ? Ui.CARD_HOVER : Ui.CARD);
        int pc = Ui.packColor(s.id);
        ctx.fill(x, y + 5, x + 3, y + h - 5, pc);

        Ui.checker(ctx, x + 12, y + 9, 40);
        Ui.drawThumb(ctx, tex.icon(s), x + 12, y + 9, 40);

        int tx = x + 62;
        int btnW = 128;
        int textW = w - 62 - btnW - 16;
        ctx.drawText(textRenderer, textRenderer.trimToWidth(s.displayName(), textW), tx, y + 9, Ui.TEXT, false);

        String desc = s.description.getString().replace('\n', ' ');
        ctx.drawText(textRenderer, textRenderer.trimToWidth(desc, textW), tx, y + 22, Ui.MUTED, false);

        int used = 0;
        for (String v : work.values()) if (v.equals(s.id)) used++;
        String stats = "Bloki " + s.counts[0] + "   Przedm. " + s.counts[1] + "   Moby " + s.counts[2] + "   GUI " + s.counts[3];
        ctx.drawText(textRenderer, textRenderer.trimToWidth(stats, textW), tx, y + 36, 0xFF6E7C9C, false);
        if (used > 0) {
            ctx.drawText(textRenderer, "W miksie: " + used, tx, y + 46, Ui.GOOD, false);
        }

        int bx = x + w - btnW - 8;
        button(ctx, bx, y + 8, btnW, 19, s.vanilla ? "Przywróć wszystko" : "Użyj wszystkiego", Ui.ACCENT, index.done,
                "Ustawia każdą teksturę, którą ma ten pack, na ten pack", () -> useAll(s));
        button(ctx, bx, y + 31, btnW, 19, "Wyczyść z miksu", Ui.CARD_HOVER, used > 0,
                "Usuwa wybory wskazujące na ten pack", () -> work.values().removeIf(v -> v.equals(s.id)));
    }

    private void useAll(Source s) {
        for (Category c : Category.values()) {
            for (Map.Entry<String, List<String>> e : index.textures(c).entrySet()) {
                if (e.getValue().contains(s.id)) work.put(e.getKey(), s.id);
            }
        }
        setStatus("Ustawiono wszystko z: " + s.displayName());
    }

    private void openPackFolder() {
        try {
            Util.getOperatingSystem().open(client.getResourcePackDir());
        } catch (Exception e) {
            setStatus("Nie udało się otworzyć folderu.");
        }
    }

    private String dots() {
        return ".".repeat((int) ((System.currentTimeMillis() / 400) % 4));
    }

    // ---------------------------------------------------------------- Kategorie

    private void drawCategoryTab(DrawContext ctx) {
        Category cat = tab.cat;
        int lx = L;
        int dx = L + listW + 10;
        int dw = R - dx;

        search.setX(lx);
        search.setY(cTop);
        search.setWidth(listW);

        // lista
        int top = cTop + 26;
        int view = cBot - top;
        int rowH = 26;
        int gap = 2;
        int total = filtered.size() * (rowH + gap);
        int max = Math.max(0, total - view);
        listScroll.tick(max);
        int off = listScroll.get();
        int rw = listW - (max > 0 ? 8 : 0);

        if (!index.done && filtered.isEmpty()) {
            ctx.drawText(textRenderer, "Skanowanie packów" + dots(), lx + 4, top + 6, Ui.MUTED, false);
        } else if (filtered.isEmpty()) {
            ctx.drawText(textRenderer, "Brak tekstur.", lx + 4, top + 6, Ui.MUTED, false);
        }

        String sel = selected.get(tab);
        beginClip(ctx, lx, top, listW, view);
        for (int i = 0; i < filtered.size(); i++) {
            int y = top + i * (rowH + gap) - off;
            if (y + rowH < top || y > cBot) continue;
            drawRow(ctx, filtered.get(i), lx, y, rw, rowH, filtered.get(i).equals(sel));
        }
        endClip(ctx);
        Ui.scrollbar(ctx, lx + listW - 4, top, view, off, max, view);

        // panel szczegółów
        Ui.outlined(ctx, dx, cTop, dw, cBot - cTop, Ui.PANEL, Ui.BORDER);
        drawDetails(ctx, cat, sel, dx, cTop, dw, cBot - cTop);
    }

    private void drawRow(DrawContext ctx, String key, int x, int y, int w, int h, boolean sel) {
        boolean hov = hover(x, y, w, h);
        Ui.round(ctx, x, y, w, h, sel ? Ui.CARD_SELECTED : hov ? Ui.CARD_HOVER : Ui.CARD);
        if (sel) ctx.fill(x, y + 3, x + 2, y + h - 3, Ui.ACCENT);

        Source eff = effective(key);
        Ui.checker(ctx, x + 6, y + 3, 20);
        if (eff != null) Ui.drawThumb(ctx, tex.thumb(eff, key), x + 6, y + 3, 20);

        String ov = work.get(key);
        String tag = ov != null ? shortSource(ov) : "auto";
        int tagColor = ov != null ? Ui.packColor(ov) : 0xFF5E6985;
        int tagW = Math.min(90, textRenderer.getWidth(tag));
        String tagTrim = textRenderer.trimToWidth(tag, tagW);
        ctx.drawText(textRenderer, tagTrim, x + w - tagW - 8, y + 9, tagColor, false);

        int nameW = w - 36 - tagW - 16;
        ctx.drawText(textRenderer, textRenderer.trimToWidth(shortName(key), nameW), x + 34, y + 9, Ui.TEXT, false);
        hit(x, y, w, h, () -> selected.put(tab, key));
    }

    private String shortSource(String id) {
        Source s = index.byId(id);
        return s == null ? id : s.displayName();
    }

    private void drawDetails(DrawContext ctx, Category cat, String sel, int x, int y, int w, int h) {
        int pad = 12;
        if (sel == null || !index.textures(cat).containsKey(sel)) {
            ctx.drawText(textRenderer, "Wybierz teksturę z listy po lewej.", x + pad, y + pad, Ui.MUTED, false);
            drawBulk(ctx, cat, x, y, w, h);
            return;
        }

        scaledText(ctx, shortName(sel), x + pad, y + 10, 1.3f, Ui.TEXT);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(sel, w - pad * 2), x + pad, y + 28, 0xFF5E6985, false);

        String ov = work.get(sel);
        Source eff = effective(sel);
        String cur = ov != null ? "Wybrane źródło: " + shortSource(ov) : "Źródło: auto" + (eff != null ? " (" + eff.displayName() + ")" : "");
        ctx.drawText(textRenderer, textRenderer.trimToWidth(cur, w - pad * 2), x + pad, y + 42, ov != null ? Ui.GOOD : Ui.MUTED, false);

        // karty źródeł
        List<String> opts = new ArrayList<>();
        opts.add(null);
        opts.addAll(index.textures(cat).get(sel));

        int cardW = 108;
        int cardH = 124;
        int gap = 8;
        int areaX = x + pad;
        int areaW = w - pad * 2 - 8;
        int areaTop = y + 60;
        int areaBot = y + h - 66;
        int view = Math.max(40, areaBot - areaTop);
        int cols = Math.max(1, (areaW + gap) / (cardW + gap));
        int rows = (opts.size() + cols - 1) / cols;
        int total = rows * (cardH + gap);
        int max = Math.max(0, total - view);
        detailScroll.tick(max);
        int off = detailScroll.get();

        beginClip(ctx, x + 2, areaTop, w - 4, view);
        for (int i = 0; i < opts.size(); i++) {
            int cx = areaX + (i % cols) * (cardW + gap);
            int cy = areaTop + (i / cols) * (cardH + gap) - off;
            if (cy + cardH < areaTop || cy > areaBot) continue;
            drawOptionCard(ctx, sel, opts.get(i), cx, cy, cardW, cardH);
        }
        endClip(ctx);
        Ui.scrollbar(ctx, x + w - 8, areaTop, view, off, max, view);

        drawBulk(ctx, cat, x, y, w, h);
    }

    private void drawOptionCard(DrawContext ctx, String key, String packId, int x, int y, int w, int h) {
        boolean auto = packId == null;
        boolean chosen = auto ? !work.containsKey(key) : packId.equals(work.get(key));
        boolean hov = hover(x, y, w, h);

        Source src = auto ? effective(key) : index.byId(packId);
        if (auto && work.containsKey(key)) {
            // karta "Auto" pokazuje, co byłoby bez miksu
            src = index.autoSource(key, client.getResourcePackManager().getEnabledIds());
        }

        if (chosen) Ui.outlined(ctx, x, y, w, h, hov ? Ui.CARD_HOVER : Ui.CARD, Ui.ACCENT);
        else Ui.round(ctx, x, y, w, h, hov ? Ui.CARD_HOVER : Ui.CARD);

        int tx = x + (w - 64) / 2;
        Ui.checker(ctx, tx, y + 12, 64);
        TexCache.Thumb th = src == null ? null : tex.thumb(src, key);
        Ui.drawThumb(ctx, th, tx, y + 12, 64);

        String name = auto ? "Auto" : src != null ? src.displayName() : packId;
        int color = auto ? Ui.MUTED : Ui.packColor(packId);
        String trimmed = textRenderer.trimToWidth(name, w - 12);
        ctx.drawText(textRenderer, trimmed, x + (w - textRenderer.getWidth(trimmed)) / 2, y + 84, color, false);

        String sub = auto ? "kolejność packów" : th != null ? th.w() + "x" + th.h() : "";
        ctx.drawText(textRenderer, sub, x + (w - textRenderer.getWidth(sub)) / 2, y + 96, 0xFF5E6985, false);

        if (chosen) {
            String t = "WYBRANE";
            ctx.drawText(textRenderer, t, x + (w - textRenderer.getWidth(t)) / 2, y + 109, Ui.GOOD, false);
        }
        hit(x, y, w, h, () -> {
            if (auto) work.remove(key);
            else work.put(key, packId);
        });
    }

    /** Dolny pasek panelu: ustaw całą kategorię (lub tylko wyniki wyszukiwania) z wybranego packa. */
    private void drawBulk(DrawContext ctx, Category cat, int x, int y, int w, int h) {
        int by = y + h - 58;
        ctx.fill(x + 8, by - 4, x + w - 8, by - 3, Ui.BORDER);
        String q = searchText.isBlank() ? "cała kategoria" : "wyniki wyszukiwania";
        ctx.drawText(textRenderer, "Hurtowo - " + q + " (" + filtered.size() + "):", x + 12, by + 2, Ui.MUTED, false);

        List<Source> srcs = index.sources;
        if (srcs.isEmpty()) return;
        bulkIdx = Math.floorMod(bulkIdx, srcs.size());
        Source s = srcs.get(bulkIdx);

        int row = by + 16;
        button(ctx, x + 12, row, 22, 22, "<", Ui.CARD_HOVER, true, null, () -> bulkIdx--);
        int nameW = Math.min(170, w - 24 - 22 * 2 - 8 - 260);
        nameW = Math.max(90, nameW);
        Ui.round(ctx, x + 38, row, nameW, 22, Ui.CARD);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(s.displayName(), nameW - 12), x + 44, row + 7, Ui.packColor(s.id), false);
        button(ctx, x + 42 + nameW, row, 22, 22, ">", Ui.CARD_HOVER, true, null, () -> bulkIdx++);

        int bx = x + 72 + nameW;
        int bw = Math.max(80, Math.min(150, x + w - 12 - bx - 96));
        button(ctx, bx, row, bw, 22, "Ustaw z tego packa", Ui.ACCENT, index.done && !filtered.isEmpty(),
                "Ustawia wszystkie wyświetlane tekstury, które ten pack posiada", () -> bulkApply(cat, s));
        button(ctx, bx + bw + 6, row, 84, 22, "Wyczyść", Ui.CARD_HOVER, !filtered.isEmpty(),
                "Usuwa wybory dla wyświetlanych tekstur", () -> {
                    for (String k : filtered) work.remove(k);
                });
    }

    private void bulkApply(Category cat, Source s) {
        int n = 0;
        Map<String, List<String>> map = index.textures(cat);
        for (String k : filtered) {
            List<String> has = map.get(k);
            if (has != null && has.contains(s.id)) {
                work.put(k, s.id);
                n++;
            }
        }
        setStatus("Ustawiono " + n + " tekstur z: " + s.displayName());
    }

    // ---------------------------------------------------------------- Stopka

    private void drawFooter(DrawContext ctx) {
        int fy = cBot + 8;
        ctx.fill(0, fy, width, height, 0xFF0D1118);
        ctx.fill(0, fy, width, fy + 1, Ui.BORDER);

        int by = fy + 8;
        int bx = R;
        bx -= 170;
        button(ctx, bx, by, 170, 22, "Zastosuj i przeładuj", Ui.ACCENT, true, "Zapisuje miks i przeładowuje zasoby", this::apply);
        bx -= 8 + 126;
        button(ctx, bx, by, 126, 22, "Wyczyść wszystko", Ui.DANGER, !work.isEmpty(), "Usuwa wszystkie wybory (do czasu zastosowania)", () -> {
            work.clear();
            setStatus("Wyczyszczono miks.");
        });
        bx -= 8 + 90;
        button(ctx, bx, by, 90, 22, "Zamknij", Ui.CARD_HOVER, true, null, this::close);

        long now = System.currentTimeMillis();
        if (now < statusUntil) {
            ctx.drawText(textRenderer, statusMsg, L, by + 7, Ui.GOOD, false);
        } else if (dirty()) {
            ctx.drawText(textRenderer, "Niezastosowane zmiany", L, by + 7, Ui.WARN, false);
        } else {
            ctx.drawText(textRenderer, "Wszystko zastosowane", L, by + 7, 0xFF5E6985, false);
        }
    }

    private void apply() {
        MixConfig cfg = MixConfig.get();
        cfg.overrides = new LinkedHashMap<>(work);
        MixConfig.save();
        setStatus("Zapisano. Przeładowuję zasoby...");
        client.reloadResources();
    }

    // ---------------------------------------------------------------- Input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int x = (int) Math.floor(mouseX);
            int y = (int) Math.floor(mouseY);
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (Ui.in(x, y, h.x(), h.y(), h.w(), h.h())) {
                    h.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        float amt = (float) verticalAmount * -34f;
        if (tab == Tab.PACKS) packScroll.by(amt);
        else if (mouseX < L + listW + 5) listScroll.by(amt);
        else detailScroll.by(amt);
        return true;
    }
}
