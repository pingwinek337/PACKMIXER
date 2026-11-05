package pl.packmixer.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Dla każdej wybranej tekstury przedmiotu/bloku dociąga z TEGO SAMEGO packa pliki towarzyszące:
 * definicję przedmiotu (items/*.json), modele (models/**.json), blockstate i tekstury użyte w tych modelach.
 * Dzięki temu pack z własnym modelem (np. 3D jabłko) działa w całości, a nie tylko jako podmieniony PNG.
 * Wywoływać w tle (czyta pliki z packów).
 */
public final class DerivedResolver {
    private DerivedResolver() {}

    public static Map<String, String> compute(ResourcePackManager manager, Map<String, String> overrides) {
        Map<String, String> derived = new LinkedHashMap<>();
        Map<String, ResourcePack> packs = new HashMap<>();
        try {
            for (Map.Entry<String, String> e : overrides.entrySet()) {
                Identifier id = Identifier.tryParse(e.getKey());
                if (id == null) continue;
                String path = id.getPath();
                String rel;
                boolean block;
                if (path.startsWith("textures/item/") && path.endsWith(".png")) {
                    rel = path.substring("textures/item/".length(), path.length() - 4);
                    block = false;
                } else if (path.startsWith("textures/block/") && path.endsWith(".png")) {
                    rel = path.substring("textures/block/".length(), path.length() - 4);
                    block = true;
                } else {
                    continue;
                }
                String packId = e.getValue();
                ResourcePack pack = packs.get(packId);
                if (pack == null) {
                    pack = openPack(manager, packId);
                    if (pack == null) continue;
                    packs.put(packId, pack);
                }

                String ns = id.getNamespace();
                Deque<String> queue = new ArrayDeque<>();
                queue.add(ns + ":items/" + rel + ".json");
                queue.add(ns + ":models/item/" + rel + ".json");
                if (block) {
                    queue.add(ns + ":models/block/" + rel + ".json");
                    queue.add(ns + ":blockstates/" + rel + ".json");
                }
                Set<String> seen = new HashSet<>();
                int guard = 0;
                while (!queue.isEmpty() && guard++ < 48) {
                    String key = queue.poll();
                    if (!seen.add(key)) continue;
                    Identifier fid = Identifier.tryParse(key);
                    if (fid == null) continue;
                    InputSupplier<InputStream> sup = pack.open(ResourceType.CLIENT_RESOURCES, fid);
                    if (sup == null) continue;
                    if (!overrides.containsKey(key)) derived.putIfAbsent(key, packId);
                    try (InputStream in = sup.get()) {
                        JsonElement root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        walk(root, pack, packId, overrides, derived, queue);
                    } catch (Exception ignored) {
                        // uszkodzony JSON w packu: pomijamy ten plik
                    }
                }
            }
        } finally {
            for (ResourcePack p : packs.values()) {
                try {
                    p.close();
                } catch (Exception ignored) {
                }
            }
        }
        return derived;
    }

    private static ResourcePack openPack(ResourcePackManager manager, String packId) {
        try {
            ResourcePackProfile p = manager.getProfile(packId);
            return p == null ? null : p.createResourcePack();
        } catch (Exception e) {
            return null;
        }
    }

    private static void walk(JsonElement el, ResourcePack pack, String packId, Map<String, String> overrides,
                             Map<String, String> derived, Deque<String> queue) {
        if (el.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                String k = e.getKey();
                JsonElement v = e.getValue();
                if (k.equals("textures") && v.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> t : v.getAsJsonObject().entrySet()) {
                        if (t.getValue().isJsonPrimitive() && t.getValue().getAsJsonPrimitive().isString()) {
                            addTexture(t.getValue().getAsString(), pack, packId, overrides, derived);
                        }
                    }
                } else if ((k.equals("parent") || k.equals("model") || k.equals("base"))
                        && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                    Identifier m = Identifier.tryParse(v.getAsString());
                    if (m != null) queue.add(m.getNamespace() + ":models/" + m.getPath() + ".json");
                } else {
                    walk(v, pack, packId, overrides, derived, queue);
                }
            }
        } else if (el.isJsonArray()) {
            for (JsonElement x : el.getAsJsonArray()) walk(x, pack, packId, overrides, derived, queue);
        }
    }

    private static void addTexture(String ref, ResourcePack pack, String packId, Map<String, String> overrides,
                                   Map<String, String> derived) {
        if (ref.startsWith("#")) return;
        Identifier t = Identifier.tryParse(ref);
        if (t == null) return;
        String key = t.getNamespace() + ":textures/" + t.getPath() + ".png";
        if (overrides.containsKey(key)) return;
        Identifier fid = Identifier.tryParse(key);
        if (fid != null && pack.open(ResourceType.CLIENT_RESOURCES, fid) != null) derived.putIfAbsent(key, packId);
    }
}
