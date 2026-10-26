package pl.packmixer.license;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Czysta logika licencji (bez zależności od Minecrafta - da się ją testować samą).
 *
 * Format klucza:  PMX1-<payload base64url>.<podpis Ed25519 base64url>
 * Payload:        serial=...;machine=XXXXXXXXXXXXXXXX;exp=<epochDay lub 0>;owner=...
 *
 * Klucz jest podpisany kluczem prywatnym autora i przypisany do jednego urządzenia (machine ID),
 * więc działa tylko na tym komputerze, dla którego został wystawiony.
 */
public final class LicenseCodec {
    public static final String PREFIX = "PMX1-";

    public record License(String serial, String machine, long expiresEpochDay, String owner) {}

    public enum Status { OK, BAD_FORMAT, BAD_SIGNATURE, WRONG_MACHINE, EXPIRED, REVOKED }

    public record Result(Status status, License license) {
        public boolean ok() {
            return status == Status.OK;
        }
    }

    private LicenseCodec() {}

    public static Result verify(String rawKey, PublicKey publicKey, String machineId, long todayEpochDay) {
        if (rawKey == null || publicKey == null) return new Result(Status.BAD_FORMAT, null);
        String key = rawKey.replaceAll("\\s+", "");
        if (!key.startsWith(PREFIX)) return new Result(Status.BAD_FORMAT, null);
        String body = key.substring(PREFIX.length());
        int dot = body.indexOf('.');
        if (dot <= 0 || dot == body.length() - 1) return new Result(Status.BAD_FORMAT, null);

        byte[] payload;
        byte[] sig;
        try {
            payload = Base64.getUrlDecoder().decode(body.substring(0, dot));
            sig = Base64.getUrlDecoder().decode(body.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return new Result(Status.BAD_FORMAT, null);
        }

        boolean valid;
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(publicKey);
            s.update(payload);
            valid = s.verify(sig);
        } catch (GeneralSecurityException e) {
            valid = false;
        }
        if (!valid) return new Result(Status.BAD_SIGNATURE, null);

        Map<String, String> f = parse(new String(payload, StandardCharsets.UTF_8));
        long exp = 0;
        try {
            exp = Long.parseLong(f.getOrDefault("exp", "0"));
        } catch (NumberFormatException ignored) {
            return new Result(Status.BAD_FORMAT, null);
        }
        License lic = new License(f.getOrDefault("serial", ""), f.getOrDefault("machine", ""), exp, f.getOrDefault("owner", ""));

        // machine=ANY oznacza licencję niezależną od komputera
        boolean anyMachine = "ANY".equalsIgnoreCase(lic.machine().trim());
        if (!anyMachine && !normalize(machineId).equals(normalize(lic.machine()))) return new Result(Status.WRONG_MACHINE, lic);
        if (lic.expiresEpochDay() != 0 && todayEpochDay > lic.expiresEpochDay()) return new Result(Status.EXPIRED, lic);
        return new Result(Status.OK, lic);
    }

    private static Map<String, String> parse(String payload) {
        Map<String, String> map = new HashMap<>();
        for (String part : payload.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) map.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return map;
    }

    /** Zostawia same znaki hex, wielkie litery. */
    public static String normalize(String id) {
        return id == null ? "" : id.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
    }

    /** ID urządzenia w formacie XXXX-XXXX-XXXX-XXXX (skrót z nazwy systemu, użytkownika i komputera). */
    public static String machineId() {
        try {
            String raw = String.join("|",
                    System.getProperty("os.name", ""),
                    System.getProperty("os.arch", ""),
                    System.getProperty("user.name", ""),
                    System.getProperty("user.home", ""),
                    hostName());
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02X", hash[i]));
            return sb.substring(0, 4) + "-" + sb.substring(4, 8) + "-" + sb.substring(8, 12) + "-" + sb.substring(12, 16);
        } catch (GeneralSecurityException e) {
            return "0000-0000-0000-0000";
        }
    }

    private static String hostName() {
        String h = System.getenv("COMPUTERNAME");
        if (h == null || h.isBlank()) h = System.getenv("HOSTNAME");
        if (h == null || h.isBlank()) {
            try {
                h = Files.readString(Path.of("/etc/hostname")).trim();
            } catch (Exception ignored) {
                h = null;
            }
        }
        if (h == null || h.isBlank()) {
            try {
                h = InetAddress.getLocalHost().getHostName();
            } catch (Exception ignored) {
                h = "unknown";
            }
        }
        return h;
    }
}
