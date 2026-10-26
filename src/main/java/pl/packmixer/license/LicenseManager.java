package pl.packmixer.license;

import net.fabricmc.loader.api.FabricLoader;
import pl.packmixer.PackMixerClient;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.util.Base64;

/** Przechowuje i sprawdza licencję. Bez ważnej licencji mod nie otwiera GUI i nie robi nic z packami. */
public final class LicenseManager {
    private static PublicKey publicKey;
    private static final java.util.Set<String> revoked = new java.util.HashSet<>();
    private static volatile boolean licensed;
    private static volatile LicenseCodec.License current;
    private static volatile long lastCheck;
    private static String machineId;

    private LicenseManager() {}

    public static void init() {
        machineId = LicenseCodec.machineId();
        loadPublicKey();
        loadRevoked();
        recheck();
    }

    public static String machineId() {
        if (machineId == null) machineId = LicenseCodec.machineId();
        return machineId;
    }

    public static boolean isLicensed() {
        if (System.currentTimeMillis() - lastCheck > 30_000L) recheck();
        return licensed;
    }

    public static LicenseCodec.License current() {
        return licensed ? current : null;
    }

    /** Próbuje aktywować klucz. Zapisuje go na dysk tylko, jeśli jest poprawny. */
    public static LicenseCodec.Result activate(String key) {
        LicenseCodec.Result r = check(key);
        if (r.ok()) {
            try {
                Files.createDirectories(file().getParent());
                Files.writeString(file(), key.replaceAll("\\s+", ""), StandardCharsets.UTF_8);
            } catch (Exception e) {
                PackMixerClient.LOGGER.error("Nie udało się zapisać licencji", e);
            }
            licensed = true;
            current = r.license();
            lastCheck = System.currentTimeMillis();
        }
        return r;
    }

    private static void recheck() {
        lastCheck = System.currentTimeMillis();
        licensed = false;
        current = null;
        try {
            Path f = file();
            if (publicKey == null || !Files.exists(f)) return;
            String key = Files.readString(f, StandardCharsets.UTF_8);
            LicenseCodec.Result r = check(key);
            if (r.ok()) {
                licensed = true;
                current = r.license();
            }
        } catch (Exception e) {
            PackMixerClient.LOGGER.warn("Błąd odczytu licencji: {}", e.toString());
        }
    }

    /** Weryfikacja podpisu + sprawdzenie listy unieważnionych numerów seryjnych. */
    private static LicenseCodec.Result check(String key) {
        LicenseCodec.Result r = LicenseCodec.verify(key, publicKey, machineId(), LocalDate.now().toEpochDay());
        if (r.ok() && revoked.contains(r.license().serial().toUpperCase(java.util.Locale.ROOT))) {
            return new LicenseCodec.Result(LicenseCodec.Status.REVOKED, r.license());
        }
        return r;
    }

    private static void loadRevoked() {
        revoked.clear();
        try (InputStream in = LicenseManager.class.getResourceAsStream("/assets/packmixer/revoked.txt")) {
            if (in == null) return;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                revoked.add(s.toUpperCase(java.util.Locale.ROOT));
            }
        } catch (Exception e) {
            PackMixerClient.LOGGER.warn("Nie można wczytać listy unieważnionych licencji: {}", e.toString());
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("packmixer").resolve("license.key");
    }

    private static void loadPublicKey() {
        try (InputStream in = LicenseManager.class.getResourceAsStream("/assets/packmixer/license_public.key")) {
            if (in == null) {
                PackMixerClient.LOGGER.error("Brak klucza publicznego licencji - mod zostanie zablokowany.");
                return;
            }
            String b64 = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (Exception e) {
            PackMixerClient.LOGGER.error("Nie można wczytać klucza publicznego licencji", e);
        }
    }
}
