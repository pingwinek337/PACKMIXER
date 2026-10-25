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
 * Zapisany miks: klucz "namespace:ścieżka/tekstury.png" -> id packa, z którego ma pochodzić ta tekstura.
 * Przykład: "minecraft:textures/block/obsidian.png" -> "file/MojPack.zip"
 */
public final class MixConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static MixConfig instance = new MixConfig();

    public Map<String, String> overrides = new LinkedHashMap<>();

    public static MixConfig get() {
        return instance;
    }

    public Map<String, String> snapshot() {
        return new LinkedHashMap<>(overrides);
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
                if (c != null && c.overrides != null) instance = c;
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
