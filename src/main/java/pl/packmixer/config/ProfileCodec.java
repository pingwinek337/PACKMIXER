package pl.packmixer.config;

import com.google.gson.Gson;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Kod profilu do wysłania znajomemu: PMXP1-<gzip+base64>. Zawiera nazwę i wybory (tekstura -> pack).
 * Kod od obcej osoby traktujemy jako niezaufany: limity rozmiaru i walidacja każdego wpisu.
 */
public final class ProfileCodec {
    public static final String PREFIX = "PMXP1-";
    private static final int MAX_ENTRIES = 60_000;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Gson GSON = new Gson();

    private static final class Payload {
        int v = 1;
        String name = "";
        Map<String, String> o = new LinkedHashMap<>();
    }

    public record Decoded(String name, Map<String, String> overrides) {}

    private ProfileCodec() {}

    public static String encode(String name, Map<String, String> overrides) {
        Payload p = new Payload();
        p.name = name;
        p.o = new LinkedHashMap<>(overrides);
        byte[] json = GSON.toJson(p).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray());
    }

    /** Zwraca null, jeśli kod jest nieprawidłowy. */
    public static Decoded decode(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("\\s+", "");
        if (!s.startsWith(PREFIX)) return null;
        try {
            byte[] gz = Base64.getUrlDecoder().decode(s.substring(PREFIX.length()));
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
                byte[] buf = in.readNBytes(MAX_BYTES + 1);
                if (buf.length > MAX_BYTES) return null;
                Payload p = GSON.fromJson(new String(buf, StandardCharsets.UTF_8), Payload.class);
                if (p == null || p.o == null || p.o.size() > MAX_ENTRIES) return null;
                Map<String, String> clean = new LinkedHashMap<>();
                for (Map.Entry<String, String> e : p.o.entrySet()) {
                    String k = e.getKey();
                    String v = e.getValue();
                    if (k == null || v == null || k.length() > 300 || v.length() > 200) continue;
                    if (Identifier.tryParse(k) == null) continue;
                    if (!(v.equals("vanilla") || v.startsWith("file/"))) continue;
                    clean.put(k, v);
                }
                return new Decoded(ProfileStore.clean(p.name == null ? "" : p.name), clean);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
