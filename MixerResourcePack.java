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

/**
 * Wirtualny pack o najwyższym priorytecie. Dla tekstur z miksu oddaje plik z wybranego packa,
 * dla całej reszty nic nie zwraca, więc działa normalna kolejność packów.
 */
public final class MixerResourcePack implements ResourcePack {
    private static final byte[] EMPTY_META = "{}".getBytes(StandardCharsets.UTF_8);

    private final ResourcePackManager manager;
    private final Map<String, String> overrides;
    private final Map<String, ResourcePack> opened = new HashMap<>();
    private final ResourcePackInfo info = new ResourcePackInfo(
            "packmixer_mix", Text.literal("Pack Mixer"), ResourcePackSource.NONE, Optional.empty());

    public MixerResourcePack(ResourcePackManager manager, Map<String, String> overrides) {
        this.manager = manager;
        this.overrides = overrides;
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
        String path = id.getPath();
        boolean meta = path.endsWith(".mcmeta");
        String basePath = meta ? path.substring(0, path.length() - ".mcmeta".length()) : path;
        String packId = overrides.get(id.getNamespace() + ":" + basePath);
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
        for (String key : overrides.keySet()) {
            Identifier id = Identifier.tryParse(key);
            if (id == null || !id.getNamespace().equals(namespace) || !id.getPath().startsWith(prefix)) continue;
            InputSupplier<InputStream> png = open(type, id);
            if (png != null) consumer.accept(id, png);
        }
    }

    @Override
    public Set<String> getNamespaces(ResourceType type) {
        Set<String> out = new HashSet<>();
        if (type != ResourceType.CLIENT_RESOURCES) return out;
        for (String key : overrides.keySet()) {
            int colon = key.indexOf(':');
            if (colon > 0) out.add(key.substring(0, colon));
        }
        return out;
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
