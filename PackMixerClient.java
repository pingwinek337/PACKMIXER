package pl.packmixer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.packmixer.config.MixConfig;
import pl.packmixer.gui.LicenseScreen;
import pl.packmixer.gui.PackMixerScreen;
import pl.packmixer.license.LicenseManager;

public class PackMixerClient implements ClientModInitializer {
    public static final String MOD_ID = "packmixer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyBinding openKey;

    @Override
    public void onInitializeClient() {
        MixConfig.load();
        LicenseManager.init();

        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.packmixer.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.packmixer"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                if (client.currentScreen == null) open(client, null);
            }
        });

        // Przycisk "Pack Mixer" w menu głównym i na ekranie packów.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof TitleScreen || screen instanceof PackScreen) {
                ButtonWidget b = ButtonWidget.builder(Text.literal("Pack Mixer"), btn -> open(client, screen))
                        .dimensions(6, 6, 90, 20)
                        .build();
                Screens.getButtons(screen).add(b);
            }
        });

        LOGGER.info("Pack Mixer załadowany (licencja: {}).", LicenseManager.isLicensed() ? "aktywna" : "brak");
    }

    /** Bez ważnej licencji pokazuje tylko ekran wpisywania klucza. */
    public static void open(MinecraftClient client, Screen parent) {
        if (LicenseManager.isLicensed()) {
            client.setScreen(new PackMixerScreen(parent));
        } else {
            client.setScreen(new LicenseScreen(parent, () -> client.setScreen(new PackMixerScreen(parent))));
        }
    }
}
