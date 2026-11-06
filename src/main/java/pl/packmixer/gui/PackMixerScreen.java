package pl.packmixer.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import pl.packmixer.PackMixerClient;
import pl.packmixer.config.MixConfig;
import pl.packmixer.config.ProfileCodec;
import pl.packmixer.config.ProfileStore;
import pl.packmixer.license.LicenseCodec;
import pl.packmixer.license.LicenseManager;
import pl.packmixer.pack.DerivedResolver;
import pl.packmixer.pack.PackIndex;
import pl.packmixer.pack.PackIndex.Category;
import pl.packmixer.pack.PackIndex.Source;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Główne GUI: boczne menu z kategoriami, wybór źródła tekstury, malarz, profile. */
public class PackMixerScreen extends Screen {

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private static boolean preview3d = true;

    private final Screen parent;
    private final PackIndex index;
    private final TexCache tex = new TexCache();
    private final Map<String, String> work = new LinkedHashMap<>(MixConfig.get().overrides);

    private Section section = Section.PACKS;
    private final EnumMap<Section, String> selected = new EnumMap<>(Section.class);
    private final EnumMap<Section, List<String>> sectionKeys = new EnumMap<>(Section.class);
    private final EnumMap<Section, Integer> sectionCounts = new EnumMap<>(Section.class);
    private final Map<String, Integer> packUse = new HashMap<>();
    private Collection<String> enabledIds = List.of();
    private List<String> filtered = List.of();
    private String searchText = "";
    private TextFieldWidget search;
    private TextFieldWidget profileName;
    private PainterPanel painter;
    private String activeProfile = MixConfig.get().activeProfile == null ? "" : MixConfig.get().activeProfile;
    private String confirmDelete = "";
    private final List<String> importReport = new ArrayList<>();
    private int seenVersion = -1;
    private int bulkIdx = 0;
    private boolean statsDirty = true;
    private boolean unsaved;
    private boolean applying;
    private long resetArmedUntil;
    private boolean dragOpacity;
    private int opX;
    private int opY;
    private int opW = 110;
    private int statusLimit = 300;

    private final Ui.SmoothScroll packScroll = new Ui.SmoothScroll();
    private final Ui.SmoothScroll listScroll = new Ui.SmoothScroll();
    private final Ui.SmoothScroll detailScroll = new Ui.SmoothScroll();
    private final Ui.SmoothScroll sideScroll = new Ui.SmoothScroll();
    private final Ui.SmoothScroll profScroll = new Ui.SmoothScroll();

    private final List<Hit> hits = new ArrayList<>();
    private String tooltip;
    private int mx;
    private int my;
    private int clipTop;
    private int clipBottom;
    private String statusMsg = "";
    private long statusUntil;

    // układ
    private int L;
    private int R;
    private int cTop;
    private int cBot;
    private int top0;
    private int sbW;
    private int cx0;
    private int cw;
    private int listW;

    public PackMixerScreen(Screen parent) {
        super(Text.literal("Pack Mixer"));
        this.parent = parent;
        Ui.opacity = MixConfig.get().uiOpacity;
        this.index = PackIndex.obtain(net.minecraft.client.MinecraftClient.getInstance().getResourcePackManager());
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

        profileName = new TextFieldWidget(textRenderer, 0, 0, 200, 20, Text.literal("Nazwa profilu"));
        profileName.setPlaceholder(Text.literal("Nazwa nowego profilu..."));
        profileName.setMaxLength(32);
        addDrawableChild(profileName);
        profileName.visible = false;

        if (painter != null) painter.close();
        painter = new PainterPanel(client, textRenderer, new PainterPanel.Host() {
            @Override
            public void onSaved(String key, String packId, String message) {
                client.getResourcePackManager().scanPacks();
                PackIndex.invalidate();
                work.put(key, packId);
                statsDirty = true;
                setStatus(message);
            }

            @Override
            public void message(String text) {
                setStatus(text);
            }
        });
        for (TextFieldWidget f : painter.widgets()) {
            addDrawableChild(f);
            f.visible = false;
        }
        refilter();
    }

    @Override
    public void removed() {
        tex.close();
        index.close();
        if (painter != null) painter.close();
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    /** Tło (rozmyta gra lub panorama) rysujemy tylko raz, na początku render(); drugie wywołanie z super.render() pomijamy. */
    private boolean allowBackground;

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        if (allowBackground) super.renderBackground(context, mouseX, mouseY, delta);
    }

    // ------------------------------------------------------------------ helpers

    private void layout() {
        L = 16;
        R = width - 16;
        cTop = 48;
        cBot = height - 46;
        top0 = cTop + 28;
        sbW = Math.min(158, Math.max(116, width / 7));
        cx0 = L + sbW + 12;
        cw = R - cx0;
        listW = Math.max(180, (int) (cw * 0.38f));
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
        Ui.shadow(ctx, x, y, w, h);
        Ui.round(ctx, x, y, w, h, bg);
        ctx.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(label, w - 6), x + w / 2, y + (h - 8) / 2, enabled ? 0xFFFFFFFF : 0xFF77809A);
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
        statusUntil = System.currentTimeMillis() + 5000;
    }

    private static String shortName(String key) {
        int colon = key.indexOf(':');
        String ns = key.substring(0, colon);
        String path = key.substring(colon + 1);
        int slash = path.indexOf('/');
        int slash2 = slash < 0 ? -1 : path.indexOf('/', slash + 1);
        String name = slash2 >= 0 ? path.substring(slash2 + 1) : slash >= 0 ? path.substring(slash + 1) : path;
        if (name.endsWith(".png")) name = name.substring(0, name.length() - 4);
        else if (name.endsWith(".json")) name = name.substring(0, name.length() - 5) + " (definicja)";
        return ns.equals("minecraft") ? name : ns + ":" + name;
    }

    private Source effective(String key) {
        String ov = work.get(key);
        if (ov != null) {
            Source s = index.byId(ov);
            if (s != null) return s;
        }
        return index.autoSource(key, enabledIds);
    }

    private String shortSource(String id) {
        Source s = index.byId(id);
        if (s != null) return s.displayName();
        return id.startsWith("file/") ? id.substring(5) : id;
    }

    private void switchSection(Section s) {
        if (section == s) return;
        section = s;
        searchText = "";
        if (search != null) search.setText("");
        listScroll.reset();
        detailScroll.reset();
        confirmDelete = "";
        refilter();
    }

    private void buildSections() {
        sectionKeys.clear();
        for (Category c : Category.values()) {
            for (String key : index.textures(c).keySet()) {
                sectionKeys.computeIfAbsent(Section.of(c, key), k -> new ArrayList<>()).add(key);
            }
        }
        for (List<String> l : sectionKeys.values()) l.sort(String.CASE_INSENSITIVE_ORDER);
    }

    private void refilter() {
        if (!section.isTextureSection()) {
            filtered = List.of();
            return;
        }
        List<String> base = sectionKeys.getOrDefault(section, List.of());
        String q = searchText.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) {
            filtered = base;
            return;
        }
        List<String> out = new ArrayList<>();
        for (String k : base) if (shortName(k).toLowerCase(Locale.ROOT).contains(q)) out.add(k);
        filtered = out;
    }

    /** Liczniki do menu, użycie packów i flaga "niezastosowane zmiany" - liczone po zmianie, nie w każdej klatce. */
    private void recomputeStats() {
        sectionCounts.clear();
        packUse.clear();
        for (Map.Entry<String, String> e : work.entrySet()) {
            sectionCounts.merge(Section.of(e.getKey()), 1, Integer::sum);
            packUse.merge(e.getValue(), 1, Integer::sum);
        }
        sectionCounts.put(Section.PACKS, work.size());
        sectionCounts.put(Section.PROFILES, 0);
        unsaved = !work.equals(MixConfig.get().overrides);
    }

    private static boolean isBlock(String key) {
        return PackIndex.categoryOf(key) == Category.BLOCKS;
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
        enabledIds = client.getResourcePackManager().getEnabledIds();
        if (index.version != seenVersion) {
            seenVersion = index.version;
            buildSections();
            refilter();
            statsDirty = true;
        }
        if (statsDirty) {
            recomputeStats();
            statsDirty = false;
        }

        allowBackground = true;
        renderBackground(ctx, mouseX, mouseY, delta);
        allowBackground = false;
        int tint = (int) (40 + 110 * Ui.opacity);
        ctx.fillGradient(0, 0, width, height, ((tint / 2) << 24) | 0x070A12, (tint << 24) | 0x121A2C);
        drawHeader(ctx);
        drawSidebar(ctx);
        drawSectionTitle(ctx);

        search.visible = section.isTextureSection();
        profileName.visible = section == Section.PROFILES;
        painter.setVisible(section == Section.PAINT);

        switch (section) {
            case PACKS -> drawPacksTab(ctx);
            case PAINT -> painter.render(ctx, mouseX, mouseY, cx0, top0, cw, cBot - top0);
            case PROFILES -> drawProfiles(ctx);
            default -> drawCategoryTab(ctx);
        }

        drawFooter(ctx);
        super.render(ctx, mouseX, mouseY, delta);
        if (tooltip != null) ctx.drawTooltip(textRenderer, Text.literal(tooltip), mouseX, mouseY);
    }

    // ---------------------------------------------------------------- nagłówek

    private void drawHeader(DrawContext ctx) {
        ctx.fillGradient(0, 0, width, 40, Ui.fade(0xFF131B2E), Ui.fade(0xFF0B0F18));
        ctx.fill(0, 40, width, 41, Ui.BORDER);
        ctx.fill(L, 10, L + 3, 30, Ui.ACCENT);
        scaledText(ctx, "PACK MIXER", L + 10, 11, 1.6f, Ui.TEXT);
        ctx.drawText(textRenderer, "Łącz wiele texturepacków w jeden", L + 130, 19, Ui.MUTED, false);

        LicenseCodec.License lic = LicenseManager.current();
        String s = "Licencja aktywna" + (lic != null && !lic.owner().isEmpty() ? " - " + lic.owner() : "");
        int w = textRenderer.getWidth(s);
        ctx.fill(R - w - 14, 18, R - w - 8, 24, Ui.GOOD);
        ctx.drawText(textRenderer, s, R - w, 17, Ui.GOOD, false);

        // szybkie przełączanie profili
        int pw = 200;
        int px = R - w - 30 - pw;
        if (px > L + 330) {
            Ui.round(ctx, px, 9, pw, 22, Ui.CARD);
            List<String> names = ProfileStore.names();
            button(ctx, px, 9, 22, 22, "<", Ui.CARD_HOVER, true, "Poprzedni profil", () -> cycleProfile(-1));
            button(ctx, px + pw - 22, 9, 22, 22, ">", Ui.CARD_HOVER, true, "Następny profil", () -> cycleProfile(1));
            String label = "Profil: " + (activeProfile.isEmpty() ? (names.isEmpty() ? "brak" : "-") : activeProfile);
            String t = textRenderer.trimToWidth(label, pw - 52);
            ctx.drawText(textRenderer, t, px + 28, 16, activeProfile.isEmpty() ? Ui.MUTED : Ui.TEXT, false);
            hit(px + 22, 9, pw - 44, 22, () -> switchSection(Section.PROFILES));
        }
    }

    private void drawSectionTitle(DrawContext ctx) {
        String title = switch (section) {
            case PACKS -> "Texturepacki";
            case PAINT -> "Malarz pikseli";
            case PROFILES -> "Profile (presety)";
            default -> section.label;
        };
        scaledText(ctx, title, cx0, cTop + 2, 1.3f, Ui.TEXT);
        String sub = switch (section) {
            case PACKS -> "";
            case PAINT -> "maluj własne tekstury";
            case PROFILES -> ProfileStore.names().size() + " zapisanych";
            default -> filtered.size() + " tekstur" + (sectionCounts.getOrDefault(section, 0) > 0
                    ? "  -  " + sectionCounts.get(section) + " w miksie" : "");
        };
        int tw = (int) (textRenderer.getWidth(title) * 1.3f);
        ctx.drawText(textRenderer, sub, cx0 + tw + 12, cTop + 6, Ui.MUTED, false);
        ctx.fill(cx0, cTop + 22, R, cTop + 23, Ui.BORDER);
    }

    // ---------------------------------------------------------------- boczne menu

    private void drawSidebar(DrawContext ctx) {
        int x = L;
        int y = cTop;
        int w = sbW;
        int h = cBot - cTop;
        Ui.outlined(ctx, x, y, w, h, Ui.PANEL, Ui.BORDER);

        int rowH = 20;
        int headH = 20;
        int total = 4;
        String last = "";
        for (Section s : Section.values()) {
            if (!s.heading.equals(last)) {
                total += headH;
                last = s.heading;
            }
            total += rowH;
        }
        int view = h - 8;
        int max = Math.max(0, total - view);
        sideScroll.tick(max);
        int off = sideScroll.get();

        beginClip(ctx, x + 1, y + 4, w - 2, view);
        int cy = y + 4 - off;
        last = "";
        for (Section s : Section.values()) {
            if (!s.heading.equals(last)) {
                last = s.heading;
                ctx.drawText(textRenderer, s.heading, x + 12, cy + 9, 0xFF5E6985, false);
                cy += headH;
            }
            drawSideRow(ctx, s, x + 6, cy, w - 12 - (max > 0 ? 6 : 0), rowH);
            cy += rowH;
        }
        endClip(ctx);
        Ui.scrollbar(ctx, x + w - 6, y + 4, view, off, max, view);
    }

    private void drawSideRow(DrawContext ctx, Section s, int x, int y, int w, int h) {
        boolean sel = s == section;
        boolean hov = hover(x, y, w, h);
        if (sel) Ui.round(ctx, x, y, w, h, Ui.CARD_SELECTED);
        else if (hov) Ui.round(ctx, x, y, w, h, Ui.CARD_HOVER);
        if (sel) ctx.fill(x, y + 3, x + 3, y + h - 3, s.color);
        ctx.fill(x + 9, y + 8, x + 13, y + 12, s.color);

        int n = sectionCounts.getOrDefault(s, 0);
        String badge = "";
        if (s == Section.PACKS) badge = n > 0 ? String.valueOf(n) : "";
        else if (n > 0) badge = String.valueOf(n);
        else if (s.isTextureSection() && index.done) badge = String.valueOf(sectionKeys.getOrDefault(s, List.of()).size());
        int bw = badge.isEmpty() ? 0 : textRenderer.getWidth(badge);
        if (!badge.isEmpty()) {
            ctx.drawText(textRenderer, badge, x + w - bw - 6, y + 6, n > 0 ? Ui.GOOD : 0xFF5E6985, false);
        }
        String label = textRenderer.trimToWidth(s.label, w - 24 - bw - 8);
        ctx.drawText(textRenderer, label, x + 19, y + 6, sel || hov ? Ui.TEXT : 0xFFB4BED3, false);
        hit(x, y, w, h, () -> switchSection(s));
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
        ctx.drawText(textRenderer, info, cx0, top0 + 4, infoColor, false);
        button(ctx, R - 150, top0 - 2, 150, 20, "Otwórz folder packów", Ui.CARD_HOVER, true, null, this::openPackFolder);

        int top = top0 + 26;
        int view = cBot - top;
        int cardH = 58;
        int gap = 6;
        int total = srcs.size() * (cardH + gap);
        int max = Math.max(0, total - view);
        packScroll.tick(max);
        int off = packScroll.get();
        int cardW = cw - (max > 0 ? 10 : 0);

        beginClip(ctx, cx0, top, cw, view);
        for (int i = 0; i < srcs.size(); i++) {
            int y = top + i * (cardH + gap) - off;
            if (y + cardH < top || y > cBot) continue;
            drawPackCard(ctx, srcs.get(i), cx0, y, cardW, cardH);
        }
        endClip(ctx);
        Ui.scrollbar(ctx, R - 4, top, view, off, max, view);
    }

    private void drawPackCard(DrawContext ctx, Source s, int x, int y, int w, int h) {
        boolean hov = hover(x, y, w, h);
        Ui.shadow(ctx, x, y, w, h);
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

        int used = packUse.getOrDefault(s.id, 0);
        String stats = "Bloki " + s.counts[0] + "  Przedm. " + s.counts[1] + "  Moby " + s.counts[2] + "  GUI " + s.counts[3] + "  Czc. " + s.counts[4];
        ctx.drawText(textRenderer, textRenderer.trimToWidth(stats, textW), tx, y + 36, 0xFF6E7C9C, false);
        if (used > 0) ctx.drawText(textRenderer, "W miksie: " + used, tx, y + 46, Ui.GOOD, false);

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

    // ---------------------------------------------------------------- Kategorie tekstur

    private void drawCategoryTab(DrawContext ctx) {
        int lx = cx0;
        int dx = cx0 + listW + 10;
        int dw = R - dx;

        search.setX(lx);
        search.setY(top0);
        search.setWidth(listW);

        int top = top0 + 26;
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
            ctx.drawText(textRenderer, "Brak tekstur w tej kategorii.", lx + 4, top + 6, Ui.MUTED, false);
        }

        String sel = selected.get(section);
        beginClip(ctx, lx, top, listW, view);
        for (int i = 0; i < filtered.size(); i++) {
            int y = top + i * (rowH + gap) - off;
            if (y + rowH < top || y > cBot) continue;
            drawRow(ctx, filtered.get(i), lx, y, rw, rowH, filtered.get(i).equals(sel));
        }
        endClip(ctx);
        Ui.scrollbar(ctx, lx + listW - 4, top, view, off, max, view);

        Ui.outlined(ctx, dx, top0, dw, cBot - top0, Ui.PANEL, Ui.BORDER);
        drawDetails(ctx, sel, dx, top0, dw, cBot - top0);
    }

    private void drawRow(DrawContext ctx, String key, int x, int y, int w, int h, boolean sel) {
        boolean hov = hover(x, y, w, h);
        Ui.round(ctx, x, y, w, h, sel ? Ui.CARD_SELECTED : hov ? Ui.CARD_HOVER : Ui.CARD);
        if (sel) ctx.fill(x, y + 3, x + 2, y + h - 3, section.color);

        Source eff = effective(key);
        Ui.checker(ctx, x + 6, y + 3, 20);
        if (eff != null) Ui.drawThumb(ctx, tex.thumb(eff, key), x + 6, y + 3, 20);

        String ov = work.get(key);
        String tag = ov != null ? shortSource(ov) : "auto";
        int tagColor = ov != null ? Ui.packColor(ov) : 0xFF5E6985;
        int tagW = Math.min(90, textRenderer.getWidth(tag));
        ctx.drawText(textRenderer, textRenderer.trimToWidth(tag, tagW), x + w - tagW - 8, y + 9, tagColor, false);

        int nameW = w - 36 - tagW - 16;
        ctx.drawText(textRenderer, textRenderer.trimToWidth(shortName(key), nameW), x + 34, y + 9, Ui.TEXT, false);
        hit(x, y, w, h, () -> selected.put(section, key));
    }

    private void drawDetails(DrawContext ctx, String sel, int x, int y, int w, int h) {
        int pad = 12;
        if (sel == null || !index.textures(PackIndex.categoryOf(sel)).containsKey(sel)) {
            ctx.drawText(textRenderer, "Wybierz teksturę z listy po lewej.", x + pad, y + pad, Ui.MUTED, false);
            drawBulk(ctx, x, y, w, h);
            return;
        }
        Category cat = PackIndex.categoryOf(sel);

        scaledText(ctx, shortName(sel), x + pad, y + 10, 1.3f, Ui.TEXT);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(sel, w - pad * 2), x + pad, y + 28, 0xFF5E6985, false);

        String ov = work.get(sel);
        Source eff = effective(sel);
        String cur = ov != null ? "Wybrane źródło: " + shortSource(ov) : "Źródło: auto" + (eff != null ? " (" + eff.displayName() + ")" : "");
        int btnArea = isBlock(sel) ? 260 : 160;
        ctx.drawText(textRenderer, textRenderer.trimToWidth(cur, w - pad * 2 - btnArea), x + pad, y + 42, ov != null ? Ui.GOOD : Ui.MUTED, false);
        button(ctx, x + w - pad - 140, y + 36, 140, 18, "Edytuj w malarzu", Ui.CARD_HOVER, sel.endsWith(".png"),
                "Wczytuje tę teksturę do zakładki Malarz", () -> editInPainter(sel));
        if (isBlock(sel)) {
            button(ctx, x + w - pad - 140 - 8 - 100, y + 36, 100, 18, preview3d ? "Widok: 3D" : "Widok: płaski", Ui.CARD_HOVER, true,
                    "Przełącza podgląd bloków między 3D a płaskim", () -> preview3d = !preview3d);
        }

        List<String> opts = new ArrayList<>();
        opts.add(null);
        opts.addAll(index.textures(cat).get(sel));

        int cardW = 108;
        int cardH = 124;
        int gap = 8;
        int areaX = x + pad;
        int areaW = w - pad * 2 - 8;
        int areaTop = y + 64;
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

        drawBulk(ctx, x, y, w, h);
    }

    private void drawOptionCard(DrawContext ctx, String key, String packId, int x, int y, int w, int h) {
        boolean auto = packId == null;
        boolean chosen = auto ? !work.containsKey(key) : packId.equals(work.get(key));
        boolean hov = hover(x, y, w, h);

        Source src = auto ? effective(key) : index.byId(packId);
        if (auto && work.containsKey(key)) src = index.autoSource(key, enabledIds);

        Ui.shadow(ctx, x, y, w, h);
        if (chosen) Ui.outlined(ctx, x, y, w, h, hov ? Ui.CARD_HOVER : Ui.CARD, Ui.ACCENT);
        else Ui.round(ctx, x, y, w, h, hov ? Ui.CARD_HOVER : Ui.CARD);

        int tx = x + (w - 64) / 2;
        boolean cube = preview3d && isBlock(key) && src != null;
        TexCache.Thumb th = null;
        if (cube) {
            Ui.roundSolid(ctx, tx, y + 12, 64, 64, 0xFF141927);
            drawCube(ctx, src, key, tx + 32, y + 15, 58);
        } else {
            Ui.checker(ctx, tx, y + 12, 64);
            th = src == null ? null : tex.thumb(src, key);
            Ui.drawThumb(ctx, th, tx, y + 12, 64);
            if (th == null && key.endsWith(".json")) {
                ctx.drawCenteredTextWithShadow(textRenderer, "{ }", tx + 32, y + 12 + 28, Ui.MUTED);
            }
        }

        String name = auto ? "Auto" : src != null ? src.displayName() : shortSource(packId);
        int color = auto ? Ui.MUTED : Ui.packColor(packId);
        String trimmed = textRenderer.trimToWidth(name, w - 12);
        ctx.drawText(textRenderer, trimmed, x + (w - textRenderer.getWidth(trimmed)) / 2, y + 84, color, false);

        String sub = auto ? "kolejność packów" : cube ? "podgląd 3D" : th != null ? th.w() + "x" + th.h() : key.endsWith(".json") ? "definicja czcionki" : "";
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

    /** Rysuje blok w 3D: góra z "_top", boki z "_side" (lewa ściana z "_front", jeśli pack ją ma). */
    private void drawCube(DrawContext ctx, Source src, String key, int cx, int top, int size) {
        int colon = key.indexOf(':');
        String ns = key.substring(0, colon);
        String path = key.substring(colon + 1);
        String base = path.substring("textures/block/".length() <= path.length() ? "textures/block/".length() : 0);
        if (base.endsWith(".png")) base = base.substring(0, base.length() - 4);
        for (String suf : new String[]{"_top", "_side", "_bottom", "_front", "_back"}) {
            if (base.endsWith(suf)) {
                base = base.substring(0, base.length() - suf.length());
                break;
            }
        }
        TexCache.Thumb topT = faceThumb(src, ns, base, "_top");
        TexCache.Thumb sideT = faceThumb(src, ns, base, "_side");
        TexCache.Thumb frontT = faceThumb(src, ns, base, "_front");
        if (frontT == null) frontT = sideT;
        Cube3D.draw(ctx, topT != null ? topT : sideT, frontT, sideT, cx, top, size);
    }

    private TexCache.Thumb faceThumb(Source src, String ns, String base, String suffix) {
        Map<String, List<String>> blocks = index.textures(Category.BLOCKS);
        String[] candidates = {ns + ":textures/block/" + base + suffix + ".png", ns + ":textures/block/" + base + ".png"};
        for (String k : candidates) {
            List<String> has = blocks.get(k);
            if (has != null && has.contains(src.id)) return tex.thumb(src, k);
        }
        return null;
    }

    private void editInPainter(String key) {
        Source src = effective(key);
        if (src == null) {
            setStatus("Brak źródła tej tekstury.");
            return;
        }
        try {
            Identifier id = Identifier.tryParse(key);
            InputSupplier<InputStream> sup = id == null ? null : src.pack.open(ResourceType.CLIENT_RESOURCES, id);
            if (sup == null) {
                setStatus("Nie znaleziono pliku tekstury.");
                return;
            }
            try (InputStream in = sup.get(); NativeImage img = NativeImage.read(in)) {
                String err = PainterPanel.load(img, key);
                if (err != null) {
                    setStatus(err);
                    return;
                }
            }
            switchSection(Section.PAINT);
        } catch (Exception e) {
            setStatus("Nie udało się wczytać tekstury.");
        }
    }

    /** Dolny pasek panelu: ustaw wyświetlane tekstury z wybranego packa. */
    private void drawBulk(DrawContext ctx, int x, int y, int w, int h) {
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
        int nameW = Math.max(90, Math.min(170, w - 24 - 22 * 2 - 8 - 260));
        Ui.round(ctx, x + 38, row, nameW, 22, Ui.CARD);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(s.displayName(), nameW - 12), x + 44, row + 7, Ui.packColor(s.id), false);
        button(ctx, x + 42 + nameW, row, 22, 22, ">", Ui.CARD_HOVER, true, null, () -> bulkIdx++);

        int bx = x + 72 + nameW;
        int bw = Math.max(80, Math.min(150, x + w - 12 - bx - 96));
        button(ctx, bx, row, bw, 22, "Ustaw z tego packa", Ui.ACCENT, index.done && !filtered.isEmpty(),
                "Ustawia wszystkie wyświetlane tekstury, które ten pack posiada", () -> bulkApply(s));
        button(ctx, bx + bw + 6, row, 84, 22, "Wyczyść", Ui.CARD_HOVER, !filtered.isEmpty(),
                "Usuwa wybory dla wyświetlanych tekstur", () -> {
                    for (String k : filtered) work.remove(k);
                });
    }

    private void bulkApply(Source s) {
        int n = 0;
        for (String k : filtered) {
            List<String> has = index.textures(PackIndex.categoryOf(k)).get(k);
            if (has != null && has.contains(s.id)) {
                work.put(k, s.id);
                n++;
            }
        }
        setStatus("Ustawiono " + n + " tekstur z: " + s.displayName());
    }

    // ---------------------------------------------------------------- Profile

    private void drawProfiles(DrawContext ctx) {
        int x = cx0;
        int w = cw;
        int top = top0;

        ctx.drawText(textRenderer, "Zapisz obecny układ jako nowy profil", x, top + 2, Ui.MUTED, false);
        int fw = Math.max(120, Math.min(260, w - 190));
        profileName.setX(x);
        profileName.setY(top + 14);
        profileName.setWidth(fw);
        button(ctx, x + fw + 8, top + 14, 170, 20, "Zapisz jako nowy profil", Ui.ACCENT, true,
                "Zapisuje wszystkie obecne wybory tekstur pod tą nazwą", () -> saveProfileAs(profileName.getText()));

        int sy = top + 46;
        ctx.drawText(textRenderer, "Udostępnianie (znajomy musi mieć te same texturepacki)", x, sy + 2, Ui.MUTED, false);
        int bw = (w - 16) / 3;
        button(ctx, x, sy + 14, bw, 20, "Importuj ze schowka", Ui.CARD_HOVER, true,
                "Wklej kod profilu od znajomego (skopiowany do schowka)", this::importFromClipboard);
        button(ctx, x + bw + 8, sy + 14, bw, 20, "Importuj z folderu", Ui.CARD_HOVER, true,
                "Importuje pliki .pmxp i .txt z config/packmixer/import", this::importFromFolder);
        button(ctx, x + 2 * (bw + 8), sy + 14, bw, 20, "Otwórz folder import", Ui.CARD_HOVER, true, null, this::openImportFolder);

        int ry = sy + 40;
        for (String line : importReport) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(line, w), x, ry, line.startsWith("Błąd") || line.contains("nieprawidłowy") ? Ui.DANGER : Ui.GOOD, false);
            ry += 11;
        }

        int listTop = ry + 8;
        int view = cBot - listTop;
        List<String> names = ProfileStore.names();
        if (names.isEmpty()) {
            ctx.drawText(textRenderer, "Brak profili. Ustaw tekstury, wpisz nazwę i zapisz profil.", x, listTop + 4, Ui.MUTED, false);
            return;
        }
        int cardH = 64;
        int gap = 6;
        int total = names.size() * (cardH + gap);
        int max = Math.max(0, total - view);
        profScroll.tick(max);
        int off = profScroll.get();
        int cardW = w - (max > 0 ? 10 : 0);

        beginClip(ctx, x, listTop, w, view);
        for (int i = 0; i < names.size(); i++) {
            int y = listTop + i * (cardH + gap) - off;
            if (y + cardH < listTop || y > cBot) continue;
            drawProfileCard(ctx, names.get(i), x, y, cardW, cardH);
        }
        endClip(ctx);
        Ui.scrollbar(ctx, R - 4, listTop, view, off, max, view);
    }

    private void drawProfileCard(DrawContext ctx, String name, int x, int y, int w, int h) {
        boolean active = name.equals(activeProfile);
        ProfileStore.Profile p = ProfileStore.get(name);
        int count = p == null ? 0 : p.overrides.size();
        Ui.shadow(ctx, x, y, w, h);
        Ui.round(ctx, x, y, w, h, hover(x, y, w, h) ? Ui.CARD_HOVER : Ui.CARD);
        ctx.fill(x, y + 5, x + 3, y + h - 5, active ? Ui.GOOD : Ui.ACCENT);

        ctx.drawText(textRenderer, textRenderer.trimToWidth(name, w - 150), x + 14, y + 8, Ui.TEXT, false);
        if (active) {
            int nw = textRenderer.getWidth(textRenderer.trimToWidth(name, w - 150));
            ctx.drawText(textRenderer, "AKTYWNY", x + 22 + nw, y + 8, Ui.GOOD, false);
        }
        ctx.drawText(textRenderer, count + " zmienionych tekstur", x + 14, y + 22, Ui.MUTED, false);

        int gap = 6;
        int bw = (w - 28 - 4 * gap) / 5;
        int by = y + 38;
        int bx = x + 14;
        button(ctx, bx, by, bw, 20, "Wczytaj", Ui.CARD_HOVER, true, "Wczytuje układ (potem Zastosuj i przeładuj)", () -> loadProfile(name, false));
        bx += bw + gap;
        button(ctx, bx, by, bw, 20, "Zastosuj", Ui.ACCENT, true, "Wczytuje i od razu przeładowuje zasoby", () -> loadProfile(name, true));
        bx += bw + gap;
        button(ctx, bx, by, bw, 20, "Zapisz tu", Ui.CARD_HOVER, true, "Nadpisuje profil obecnym układem", () -> {
            ProfileStore.put(name, work);
            activeProfile = name;
            setStatus("Zapisano układ w profilu: " + name);
        });
        bx += bw + gap;
        button(ctx, bx, by, bw, 20, "Eksportuj", Ui.CARD_HOVER, true, "Kopiuje kod profilu do schowka (do wysłania znajomemu)", () -> exportProfile(name));
        bx += bw + gap;
        boolean armed = confirmDelete.equals(name);
        button(ctx, bx, by, bw, 20, armed ? "Na pewno?" : "Usuń", Ui.DANGER, true, null, () -> {
            if (armed) {
                ProfileStore.remove(name);
                if (name.equals(activeProfile)) activeProfile = "";
                confirmDelete = "";
                setStatus("Usunięto profil: " + name);
            } else {
                confirmDelete = name;
            }
        });
    }

    private void loadProfile(String name, boolean applyNow) {
        ProfileStore.Profile p = ProfileStore.get(name);
        if (p == null) return;
        work.clear();
        work.putAll(p.overrides);
        activeProfile = name;
        statsDirty = true;
        setStatus("Wczytano profil: " + name + (applyNow ? "" : " (kliknij Zastosuj i przeładuj)"));
        if (applyNow) apply();
    }

    private void saveProfileAs(String raw) {
        String name = ProfileStore.clean(raw);
        if (name.isEmpty()) {
            setStatus("Wpisz nazwę profilu.");
            return;
        }
        ProfileStore.put(name, work);
        activeProfile = name;
        profileName.setText("");
        setStatus("Zapisano profil: " + name);
    }

    private void cycleProfile(int dir) {
        List<String> names = ProfileStore.names();
        if (names.isEmpty()) {
            switchSection(Section.PROFILES);
            return;
        }
        int i = names.indexOf(activeProfile);
        i = i < 0 ? (dir > 0 ? 0 : names.size() - 1) : Math.floorMod(i + dir, names.size());
        loadProfile(names.get(i), false);
    }

    private void exportProfile(String name) {
        ProfileStore.Profile p = ProfileStore.get(name);
        if (p == null) return;
        String code = ProfileCodec.encode(name, p.overrides);
        client.keyboard.setClipboard(code);
        String where = "";
        try {
            Path f = ProfileStore.writeExport(name, code);
            where = " Plik: " + f.getFileName();
        } catch (Exception ignored) {
        }
        setStatus("Skopiowano kod profilu do schowka - wyślij go znajomemu." + where);
    }

    private void importFromClipboard() {
        importReport.clear();
        importCode(client.keyboard.getClipboard(), "Schowek");
    }

    private void importFromFolder() {
        importReport.clear();
        List<Path> files = ProfileStore.importFiles();
        if (files.isEmpty()) {
            importReport.add("Brak plików .pmxp / .txt w folderze config/packmixer/import.");
            return;
        }
        for (Path f : files) {
            try {
                importCode(Files.readString(f), f.getFileName().toString());
                Files.move(f, f.resolveSibling(f.getFileName() + ".done"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                importReport.add("Błąd odczytu pliku " + f.getFileName());
            }
        }
    }

    private void openImportFolder() {
        try {
            Files.createDirectories(ProfileStore.importDir());
            Util.getOperatingSystem().open(ProfileStore.importDir());
        } catch (Exception e) {
            setStatus("Nie udało się otworzyć folderu.");
        }
    }

    private void importCode(String code, String source) {
        ProfileCodec.Decoded d = ProfileCodec.decode(code);
        if (d == null) {
            importReport.add(source + ": nieprawidłowy kod profilu.");
            return;
        }
        Map<String, String> kept = new LinkedHashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : d.overrides().entrySet()) {
            if (client.getResourcePackManager().getProfile(e.getValue()) == null) {
                missing.add(e.getValue());
                continue;
            }
            kept.put(e.getKey(), e.getValue());
        }
        String base = d.name().isEmpty() ? "Importowany" : d.name();
        String name = base;
        int n = 2;
        while (ProfileStore.get(name) != null) name = base + " (" + n++ + ")";
        ProfileStore.put(name, kept);
        loadProfile(name, false);

        importReport.add(source + ": zaimportowano profil \"" + name + "\" (" + kept.size() + " tekstur).");
        if (!missing.isEmpty()) {
            List<String> shown = new ArrayList<>();
            for (String m : missing) {
                if (shown.size() >= 3) break;
                shown.add(m.startsWith("file/") ? m.substring(5) : m);
            }
            importReport.add("Pominięto wpisy z packów, których nie masz: " + String.join(", ", shown)
                    + (missing.size() > 3 ? " i " + (missing.size() - 3) + " więcej" : "") + ".");
        }
        while (importReport.size() > 6) importReport.remove(0);
    }

    // ---------------------------------------------------------------- Stopka

    private void drawFooter(DrawContext ctx) {
        int fy = cBot + 8;
        ctx.fillGradient(0, fy, width, height, Ui.fade(0xFF0E1320), Ui.fade(0xFF0A0E16));
        ctx.fill(0, fy, width, fy + 1, Ui.BORDER);

        int by = fy + 8;
        int bx = R;
        bx -= 170;
        button(ctx, bx, by, 170, 22, applying ? "Analizuję..." : "Zastosuj i przeładuj", Ui.ACCENT, !applying,
                "Przeładowanie zasobów chwilę zamraża grę (na serwerze podbija ping) - najlepiej w menu głównym", this::apply);
        long now = System.currentTimeMillis();
        boolean armed = now < resetArmedUntil;
        bx -= 8 + 160;
        button(ctx, bx, by, 160, 22, armed ? "Na pewno? Kliknij ponownie" : "Zresetuj wszystko", Ui.DANGER,
                !work.isEmpty() || !MixConfig.get().overrides.isEmpty(),
                "Usuwa wszystkie wybory i od razu przywraca zwykłe tekstury", () -> {
                    if (System.currentTimeMillis() < resetArmedUntil) resetAll();
                    else resetArmedUntil = System.currentTimeMillis() + 4000;
                });
        bx -= 8 + 90;
        button(ctx, bx, by, 90, 22, "Zamknij", Ui.CARD_HOVER, true, null, this::close);

        // suwak przezroczystości paneli
        String opLabel = "Przezroczystość";
        int lw = textRenderer.getWidth(opLabel);
        opW = 110;
        opX = bx - 16 - opW;
        opY = by + 4;
        ctx.drawText(textRenderer, opLabel, opX - lw - 8, by + 7, Ui.MUTED, false);
        float t = (Ui.opacity - 0.25f) / 0.75f;
        ctx.fill(opX, opY + 6, opX + opW, opY + 9, 0x66000000);
        ctx.fill(opX, opY + 6, opX + Math.round(opW * t), opY + 9, Ui.ACCENT);
        int kx = opX + Math.round((opW - 4) * t);
        boolean opHover = dragOpacity || Ui.in(mx, my, opX - 3, opY, opW + 6, 14);
        ctx.fill(kx, opY + 2, kx + 4, opY + 13, opHover ? 0xFFFFFFFF : 0xFFD5DCF0);
        statusLimit = opX - lw - 8 - 14 - L;

        if (now < statusUntil) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(statusMsg, Math.max(60, statusLimit)), L, by + 7, Ui.GOOD, false);
        } else if (unsaved) {
            ctx.drawText(textRenderer, "Niezastosowane zmiany", L, by + 7, Ui.WARN, false);
        } else {
            ctx.drawText(textRenderer, "Wszystko zastosowane", L, by + 7, 0xFF5E6985, false);
        }
    }

    /** Zapisuje miks (z analizą modeli w tle) i przeładowuje zasoby. */
    private void apply() {
        if (applying) return;
        applying = true;
        setStatus("Analizuję modele i zapisuję...");
        final Map<String, String> snap = new LinkedHashMap<>(work);
        final var manager = client.getResourcePackManager();
        final String profile = activeProfile;
        Thread t = new Thread(() -> {
            Map<String, String> derived;
            try {
                derived = DerivedResolver.compute(manager, snap);
            } catch (Throwable e) {
                PackMixerClient.LOGGER.warn("Analiza modeli nie powiodła się: {}", e.toString());
                derived = new LinkedHashMap<>();
            }
            final Map<String, String> d = derived;
            client.execute(() -> {
                MixConfig cfg = MixConfig.get();
                cfg.overrides = snap;
                cfg.derived = d;
                cfg.activeProfile = profile;
                MixConfig.save();
                applying = false;
                statsDirty = true;
                setStatus("Zapisano. Przeładowuję zasoby...");
                client.reloadResources();
            });
        }, "PackMixer-Apply");
        t.setDaemon(true);
        t.start();
    }

    /** Czyści wszystko i od razu przeładowuje zasoby: gra wraca do zwykłych tekstur. */
    private void resetAll() {
        resetArmedUntil = 0;
        work.clear();
        activeProfile = "";
        MixConfig cfg = MixConfig.get();
        cfg.overrides = new LinkedHashMap<>();
        cfg.derived = new LinkedHashMap<>();
        cfg.activeProfile = "";
        MixConfig.save();
        statsDirty = true;
        setStatus("Zresetowano wszystko. Przeładowuję zasoby...");
        client.reloadResources();
    }

    // ---------------------------------------------------------------- Input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && Ui.in((int) Math.floor(mouseX), (int) Math.floor(mouseY), opX - 3, opY, opW + 6, 14)) {
            dragOpacity = true;
            setOpacityFromMouse(mouseX);
            return true;
        }
        if (section == Section.PAINT && painter.mouseClicked(mouseX, mouseY, button)) return true;
        if (button == 0) {
            int x = (int) Math.floor(mouseX);
            int y = (int) Math.floor(mouseY);
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (Ui.in(x, y, h.x(), h.y(), h.w(), h.h())) {
                    h.action().run();
                    statsDirty = true;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragOpacity) {
            setOpacityFromMouse(mouseX);
            return true;
        }
        if (section == Section.PAINT && painter.mouseDragged(mouseX, mouseY)) return true;
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (painter != null) painter.mouseReleased();
        if (dragOpacity) {
            dragOpacity = false;
            MixConfig.save();
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void setOpacityFromMouse(double mouseX) {
        float t = (float) Math.max(0, Math.min(1, (mouseX - opX) / (double) (opW - 1)));
        Ui.opacity = 0.25f + 0.75f * t;
        MixConfig.get().uiOpacity = Ui.opacity;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (section == Section.PAINT && Screen.hasControlDown() && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_Z
                && !painter.anyFieldFocused()) {
            painter.undo();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        float amt = (float) verticalAmount * -34f;
        if (mouseX < L + sbW + 4) {
            sideScroll.by(amt);
            return true;
        }
        switch (section) {
            case PACKS -> packScroll.by(amt);
            case PROFILES -> profScroll.by(amt);
            case PAINT -> {
            }
            default -> {
                if (mouseX < cx0 + listW + 5) listScroll.by(amt);
                else detailScroll.by(amt);
            }
        }
        return true;
    }
}
