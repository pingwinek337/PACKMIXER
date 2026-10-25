package pl.packmixer.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import pl.packmixer.license.LicenseCodec;
import pl.packmixer.license.LicenseManager;

import java.util.ArrayList;
import java.util.List;

/** Ekran aktywacji: bez ważnego klucza nie da się wejść do właściwego GUI. */
public class LicenseScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private final Screen parent;
    private final Runnable onSuccess;
    private TextFieldWidget keyField;
    private String message = "";
    private int messageColor = Ui.MUTED;
    private int mx, my;
    private final List<Hit> hits = new ArrayList<>();
    private String keyText = "";

    public LicenseScreen(Screen parent, Runnable onSuccess) {
        super(Text.literal("Licencja"));
        this.parent = parent;
        this.onSuccess = onSuccess;
    }

    @Override
    protected void init() {
        keyField = new TextFieldWidget(textRenderer, 0, 0, 100, 20, Text.literal("Klucz licencyjny"));
        keyField.setMaxLength(4096);
        keyField.setPlaceholder(Text.literal("PMX1-..."));
        keyField.setText(keyText);
        keyField.setChangedListener(s -> keyText = s);
        addDrawableChild(keyField);
        setInitialFocus(keyField);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // własne tło rysujemy w render()
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        mx = mouseX;
        my = mouseY;
        hits.clear();
        ctx.fillGradient(0, 0, width, height, Ui.BG_TOP, Ui.BG_BOTTOM);

        int cw = Math.min(440, width - 30);
        int ch = 226;
        int cx = (width - cw) / 2;
        int cy = Math.max(10, (height - ch) / 2);
        Ui.outlined(ctx, cx, cy, cw, ch, Ui.PANEL, Ui.BORDER);
        ctx.fill(cx + 1, cy + 1, cx + cw - 1, cy + 3, Ui.ACCENT);

        int pad = 20;
        int ix = cx + pad;
        int iw = cw - pad * 2;

        scaled(ctx, "PACK MIXER", ix, cy + 16, 1.6f, Ui.TEXT);
        ctx.drawText(textRenderer, "Wymagana licencja", ix, cy + 36, Ui.WARN, false);
        ctx.drawText(textRenderer, "Wpisz klucz licencyjny, aby odblokować moda.", ix, cy + 54, Ui.MUTED, false);

        // ID urządzenia
        ctx.drawText(textRenderer, "ID tego urządzenia (podaj je przy zakupie licencji):", ix, cy + 76, Ui.MUTED, false);
        String id = LicenseManager.machineId();
        Ui.round(ctx, ix, cy + 88, iw - 74, 20, Ui.CARD);
        ctx.drawText(textRenderer, id, ix + 8, cy + 94, Ui.GOOD, false);
        button(ctx, ix + iw - 68, cy + 88, 68, 20, "Kopiuj", Ui.CARD_HOVER, () -> {
            client.keyboard.setClipboard(id);
            setMessage("Skopiowano ID urządzenia.", Ui.GOOD);
        });

        // pole na klucz
        ctx.drawText(textRenderer, "Klucz licencyjny:", ix, cy + 118, Ui.MUTED, false);
        keyField.setX(ix);
        keyField.setY(cy + 130);
        keyField.setWidth(iw);

        // przyciski
        int by = cy + 160;
        button(ctx, ix, by, 80, 22, "Wklej", Ui.CARD_HOVER, () -> {
            String clip = client.keyboard.getClipboard();
            if (clip != null) keyField.setText(clip.trim());
        });
        button(ctx, ix + iw - 190, by, 100, 22, "Aktywuj", Ui.ACCENT, this::activate);
        button(ctx, ix + iw - 84, by, 84, 22, "Anuluj", Ui.CARD_HOVER, this::close);

        if (!message.isEmpty()) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(message, iw), ix, cy + 192, messageColor, false);
        }
        ctx.drawText(textRenderer, "Licencja jest jednorazowa i przypisana do tego urządzenia.", ix, cy + 206, 0xFF5E6985, false);

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void activate() {
        LicenseCodec.Result r = LicenseManager.activate(keyText);
        switch (r.status()) {
            case OK -> {
                setMessage("Licencja aktywna. Dziękujemy!", Ui.GOOD);
                onSuccess.run();
            }
            case BAD_FORMAT -> setMessage("Nieprawidłowy format klucza.", Ui.DANGER);
            case BAD_SIGNATURE -> setMessage("Klucz jest nieprawdziwy.", Ui.DANGER);
            case WRONG_MACHINE -> setMessage("Ten klucz został wystawiony dla innego urządzenia.", Ui.DANGER);
            case EXPIRED -> setMessage("Licencja wygasła.", Ui.DANGER);
        }
    }

    private void setMessage(String m, int color) {
        message = m;
        messageColor = color;
    }

    private void button(DrawContext ctx, int x, int y, int w, int h, String label, int color, Runnable action) {
        boolean hov = Ui.in(mx, my, x, y, w, h);
        Ui.round(ctx, x, y, w, h, hov ? Ui.lighten(color, 0x20) : color);
        ctx.drawCenteredTextWithShadow(textRenderer, label, x + w / 2, y + (h - 8) / 2, 0xFFFFFFFF);
        hits.add(new Hit(x, y, w, h, action));
    }

    private void scaled(DrawContext ctx, String s, int x, int y, float scale, int color) {
        ctx.getMatrices().push();
        ctx.getMatrices().translate(x, y, 0);
        ctx.getMatrices().scale(scale, scale, 1f);
        ctx.drawText(textRenderer, s, 0, 0, color, true);
        ctx.getMatrices().pop();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (Ui.in((int) Math.floor(mouseX), (int) Math.floor(mouseY), h.x(), h.y(), h.w(), h.h())) {
                    h.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            activate();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
