package pl.packmixer.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import pl.packmixer.pack.PackIndex;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Ładuje miniatury tekstur z packów (z limitem na klatkę, żeby GUI się nie zacinało). */
public final class TexCache implements AutoCloseable {
    public record Thumb(Identifier id, int w, int h) {}

    private interface Opener {
        InputSupplier<InputStream> open() throws IOException;
    }

    private final Map<String, Thumb> loaded = new HashMap<>();
    private final Set<String> failed = new HashSet<>();
    private int counter;
    private int budget;

    public void beginFrame() {
        budget = 8;
    }

    public Thumb thumb(PackIndex.Source src, String key) {
        if (src == null) return null;
        return load("t|" + src.id + "|" + key, () -> {
            Identifier id = Identifier.tryParse(key);
            return id == null ? null : src.pack.open(ResourceType.CLIENT_RESOURCES, id);
        });
    }

    public Thumb icon(PackIndex.Source src) {
        if (src == null) return null;
        return load("i|" + src.id, () -> src.pack.openRoot("pack.png"));
    }

    private Thumb load(String cacheKey, Opener opener) {
        Thumb cached = loaded.get(cacheKey);
        if (cached != null) return cached;
        if (failed.contains(cacheKey) || budget <= 0) return null;
        budget--;
        try {
            InputSupplier<InputStream> supplier = opener.open();
            if (supplier == null) {
                failed.add(cacheKey);
                return null;
            }
            try (InputStream in = supplier.get()) {
                NativeImage img = NativeImage.read(in);
                int w = img.getWidth();
                int h = img.getHeight();
                NativeImageBackedTexture tex = new NativeImageBackedTexture(img);
                tex.setFilter(false, false);
                Identifier id = Identifier.of("packmixer", "dyn/" + (counter++));
                MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
                Thumb t = new Thumb(id, w, h);
                loaded.put(cacheKey, t);
                return t;
            }
        } catch (Exception e) {
            failed.add(cacheKey);
            return null;
        }
    }

    @Override
    public void close() {
        for (Thumb t : loaded.values()) {
            MinecraftClient.getInstance().getTextureManager().destroyTexture(t.id());
        }
        loaded.clear();
    }
}
