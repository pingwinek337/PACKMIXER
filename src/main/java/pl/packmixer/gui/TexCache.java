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
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Miniatury tekstur. Dekodowanie PNG odbywa się w tle (niski priorytet), a w głównym wątku
 * robimy tylko szybkie wgranie gotowego obrazka na GPU (maks. 2 na klatkę), żeby gra się nie zacinała.
 */
public final class TexCache implements AutoCloseable {
    public record Thumb(Identifier id, int w, int h) {}

    private interface Opener {
        InputSupplier<InputStream> open() throws IOException;
    }

    private record Ready(String key, NativeImage image) {}

    private static final long MAX_PIXELS = 2048L * 2048L;
    private static final int MAX_PENDING = 48;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "PackMixer-Decode");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private final Map<String, Thumb> loaded = new HashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private final Queue<Ready> ready = new ConcurrentLinkedQueue<>();
    private volatile boolean closed;
    private int counter;

    /** Wołać raz na klatkę w głównym wątku: wgrywa gotowe obrazki. */
    public void beginFrame() {
        int budget = 2;
        Ready r;
        while (budget > 0 && (r = ready.poll()) != null) {
            register(r);
            budget--;
        }
    }

    private void register(Ready r) {
        try {
            int w = r.image().getWidth();
            int h = r.image().getHeight();
            NativeImageBackedTexture tex = new NativeImageBackedTexture(r.image());
            tex.setFilter(false, false);
            Identifier id = Identifier.of("packmixer", "dyn/" + (counter++));
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
            loaded.put(r.key(), new Thumb(id, w, h));
        } catch (Exception e) {
            failed.add(r.key());
        } finally {
            pending.remove(r.key());
        }
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
        if (closed || failed.contains(cacheKey) || pending.size() >= MAX_PENDING || !pending.add(cacheKey)) return null;

        POOL.execute(() -> {
            try {
                if (closed) {
                    pending.remove(cacheKey);
                    return;
                }
                InputSupplier<InputStream> supplier = opener.open();
                if (supplier == null) {
                    failed.add(cacheKey);
                    pending.remove(cacheKey);
                    return;
                }
                try (InputStream in = supplier.get()) {
                    NativeImage img = NativeImage.read(in);
                    if ((long) img.getWidth() * img.getHeight() > MAX_PIXELS || closed) {
                        img.close();
                        failed.add(cacheKey);
                        pending.remove(cacheKey);
                        return;
                    }
                    ready.add(new Ready(cacheKey, img));
                }
            } catch (Throwable e) {
                failed.add(cacheKey);
                pending.remove(cacheKey);
            }
        });
        return null;
    }

    @Override
    public void close() {
        closed = true;
        Ready r;
        while ((r = ready.poll()) != null) r.image().close();
        for (Thumb t : loaded.values()) {
            MinecraftClient.getInstance().getTextureManager().destroyTexture(t.id());
        }
        loaded.clear();
    }
}
