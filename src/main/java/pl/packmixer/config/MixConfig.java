package pl.packmixer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import pl.packmixer.PackMixerClient;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Zapisany miks: klucz "namespace:ścieżka" -> id packa, z którego ma pochodzić ten plik.
 * overrides = wybory użytkownika (tekstury), derived = pliki towarzyszące (modele, definicje przedmiotów,
 * tekstury z modeli), wyliczane z overrides przy "Zastosuj".
 */
public final class MixConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static MixConfig instance = new MixConfig();

    public Map<String, String> overrides = new LinkedHashMap<>();
    public Map<String, String> derived = new LinkedHashMap<>();
    public String activeProfile = "";
    /** Przezroczystość paneli GUI (0.25 - 1.0). */
    public float uiOpacity = 0.75f;

    public static MixConfig get() {
        return instance;
    }

    /** Wybory użytkownika + pliki towarzyszące (wybory użytkownika mają pierwszeństwo). */
    public Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>(derived);
        m.putAll(overrides);
        return m;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("packmixer").resolve("mix.json");
    }

    public static void load() {
        try {
            Path p = file();
            if (!Files.exists(p)) return;
            try (Reader r = Files.newBufferedReader(p)) {
                MixConfig c = GSON.fromJson(r, MixConfig.class);
                if (c != null) {
                    if (c.overrides == null) c.overrides = new LinkedHashMap<>();
                    if (c.derived == null) c.derived = new LinkedHashMap<>();
                    if (c.activeProfile == null) c.activeProfile = "";
                    if (c.uiOpacity < 0.25f || c.uiOpacity > 1f) c.uiOpacity = 0.75f;
                    instance = c;
                }
            }
        } catch (Exception e) {
            PackMixerClient.LOGGER.warn("Nie udało się wczytać mix.json: {}", e.toString());
        }
    }

    public static void save() {
        try {
            Path p = file();
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(p)) {
                GSON.toJson(instance, w);
            }
        } catch (Exception e) {
            PackMixerClient.LOGGER.error("Nie udało się zapisać mix.json", e);
        }
    }
}
