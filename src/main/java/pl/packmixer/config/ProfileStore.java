package pl.packmixer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import pl.packmixer.PackMixerClient;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Zapisane profile (presety) układu tekstur: config/packmixer/profiles.json. */
public final class ProfileStore {
    public static final class Profile {
        public Map<String, String> overrides = new LinkedHashMap<>();
    }

    private static final class Data {
        Map<String, Profile> profiles = new LinkedHashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Data data = new Data();

    private ProfileStore() {}

    private static Path base() {
        return FabricLoader.getInstance().getConfigDir().resolve("packmixer");
    }

    private static Path file() {
        return base().resolve("profiles.json");
    }

    public static Path exportDir() {
        return base().resolve("export");
    }

    public static Path importDir() {
        return base().resolve("import");
    }

    public static synchronized void load() {
        try {
            Path p = file();
            if (!Files.exists(p)) return;
            try (Reader r = Files.newBufferedReader(p)) {
                Data d = GSON.fromJson(r, Data.class);
                if (d != null && d.profiles != null) data = d;
            }
        } catch (Exception e) {
            PackMixerClient.LOGGER.warn("Nie udało się wczytać profiles.json: {}", e.toString());
        }
    }

    public static synchronized void save() {
        try {
            Path p = file();
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(p)) {
                GSON.toJson(data, w);
            }
        } catch (Exception e) {
            PackMixerClient.LOGGER.error("Nie udało się zapisać profiles.json", e);
        }
    }

    public static synchronized List<String> names() {
        return new ArrayList<>(data.profiles.keySet());
    }

    public static synchronized Profile get(String name) {
        return data.profiles.get(name);
    }

    public static synchronized void put(String name, Map<String, String> overrides) {
        Profile p = new Profile();
        p.overrides = new LinkedHashMap<>(overrides);
        data.profiles.put(name, p);
        save();
    }

    public static synchronized void remove(String name) {
        data.profiles.remove(name);
        save();
    }

    /** Nazwa profilu: bez znaków sterujących, max 32 znaki. */
    public static String clean(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        return s.length() > 32 ? s.substring(0, 32).trim() : s;
    }

    private static String safeFile(String name) {
        String s = name.replaceAll("[^A-Za-z0-9_-]", "_");
        return s.isEmpty() ? "profil" : s;
    }

    public static Path writeExport(String name, String code) throws IOException {
        Path dir = exportDir();
        Files.createDirectories(dir);
        Path out = dir.resolve(safeFile(name) + ".pmxp");
        Files.writeString(out, code);
        return out;
    }

    /** Pliki .pmxp / .txt z folderu import. */
    public static List<Path> importFiles() {
        List<Path> out = new ArrayList<>();
        Path dir = importDir();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.endsWith(".pmxp") || n.endsWith(".txt")) out.add(p);
            });
        } catch (IOException ignored) {
        }
        return out;
    }
}
