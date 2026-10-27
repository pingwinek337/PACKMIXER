package pl.packmixer.pack;

import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.metadata.ResourceMetadataSerializer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Wirtualny pack o najwyższym priorytecie. Dla tekstur z miksu oddaje plik z wybranego packa,
 * dla całej reszty nic nie zwraca, więc działa normalna kolejność packów.
 *
 * Wybory są zindeksowane po przestrzeni nazw (TreeMap po ścieżce), więc wyszukiwanie zasobów
 * podczas przeładowania nie przechodzi za każdym razem po całej liście.
 */
public final class MixerResourcePack implements ResourcePack {
    private static final byte[] EMPTY_META = "{}".getBytes(StandardCharsets.UTF_8);
    private static final int MCMETA = ".mcmeta".length();

    private final ResourcePackManager manager;
    private final Map<String, TreeMap<String, String>> byNamespace = new HashMap<>();
    private final Map<String, ResourcePack> opened = new HashMap<>();
    private final ResourcePackInfo info = new ResourcePackInfo(
            "packmixer_mix", Text.literal("Pack Mixer"), ResourcePackSource.NONE, Optional.empty());

    public MixerResourcePack(ResourcePackManager manager, Map<String, String> overrides) {
        this.manager = manager;
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            String key = e.getKey();
            int colon = key.indexOf(':');
            if (colon <= 0) continue;
            byNamespace.computeIfAbsent(key.substring(0, colon), k -> new TreeMap<>())
                    .put(key.substring(colon + 1), e.getValue());
        }
    }

    private synchronized @Nullable ResourcePack source(String packId) {
        ResourcePack p = opened.get(packId);
        if (p != null) return p;
        ResourcePackProfile profile = manager.getProfile(packId);
        if (profile == null) return null;
        p = profile.createResourcePack();
        opened.put(packId, p);
        return p;
    }

    @Override
    public @Nullable InputSupplier<InputStream> openRoot(String... segments) {
        return null;
    }

    @Override
    public @Nullable InputSupplier<InputStream> open(ResourceType type, Identifier id) {
        if (type != ResourceType.CLIENT_RESOURCES) return null;
        TreeMap<String, String> map = byNamespace.get(id.getNamespace());
        if (map == null) return null;
        String path = id.getPath();
        boolean meta = path.endsWith(".mcmeta");
        String basePath = meta ? path.substring(0, path.length() - MCMETA) : path;
        String packId = map.get(basePath);
        if (packId == null) return null;
        ResourcePack src = source(packId);
        if (src == null) return null;

        if (!meta) return src.open(type, id);

        // .mcmeta (animacje): jeśli wybrany pack nie ma własnego, zwracamy pusty,
        // żeby animacja z innego packa nie "przeciekła" na nową teksturę.
        if (src.open(type, Identifier.of(id.getNamespace(), basePath)) == null) return null;
        InputSupplier<InputStream> own = src.open(type, id);
        return own != null ? own : () -> new ByteArrayInputStream(EMPTY_META);
    }

    @Override
    public void findResources(ResourceType type, String namespace, String prefix, ResultConsumer consumer) {
        if (type != ResourceType.CLIENT_RESOURCES) return;
        TreeMap<String, String> map = byNamespace.get(namespace);
        if (map == null) return;
        for (Map.Entry<String, String> e : map.tailMap(prefix, true).entrySet()) {
            String path = e.getKey();
            if (!path.startsWith(prefix)) break; // klucze posortowane: koniec zakresu
            Identifier id = Identifier.tryParse(namespace + ":" + path);
            if (id == null) continue;
            InputSupplier<InputStream> supplier = open(type, id);
            if (supplier != null) consumer.accept(id, supplier);
        }
    }

    @Override
    public Set<String> getNamespaces(ResourceType type) {
        if (type != ResourceType.CLIENT_RESOURCES) return new HashSet<>();
        return new HashSet<>(byNamespace.keySet());
    }

    @Override
    public <T> @Nullable T parseMetadata(ResourceMetadataSerializer<T> metadataSerializer) {
        return null;
    }

    @Override
    public ResourcePackInfo getInfo() {
        return info;
    }

    @Override
    public synchronized void close() {
        for (ResourcePack p : opened.values()) {
            try {
                p.close();
            } catch (Exception ignored) {
            }
        }
        opened.clear();
    }
}
