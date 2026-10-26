package pl.packmixer.pack;

import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Skanuje packi w tle i buduje listę: tekstura -> w których packach istnieje. */
public final class PackIndex implements AutoCloseable {

    public enum Category {
        BLOCKS("Bloki", "textures/block"),
        ITEMS("Przedmioty", "textures/item"),
        ENTITIES("Moby", "textures/entity"),
        GUI("GUI", "textures/gui"),
        // tekstury znaków (ascii.png itd.) oraz definicje czcionek font/*.json
        FONTS("Czcionki", "textures/font", "font");

        public final String label;
        public final String[] prefixes;

        Category(String label, String... prefixes) {
            this.label = label;
            this.prefixes = prefixes;
        }

        /** PNG-i zawsze; pliki .json tylko z folderu font/. */
        public boolean accepts(String path) {
            return path.endsWith(".png") || (path.startsWith("font/") && path.endsWith(".json"));
        }
    }

    public static final class Source {
        public final String id;
        public final Text name;
        public final Text description;
        public final ResourcePack pack;
        public final boolean vanilla;
        public final int[] counts = new int[Category.values().length];

        Source(String id, Text name, Text description, ResourcePack pack) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.pack = pack;
            this.vanilla = id.equals("vanilla");
        }

        public String displayName() {
            if (vanilla) return "Vanilla (domyślne)";
            String n = name.getString();
            return n.startsWith("file/") ? n.substring(5) : n;
        }
    }

    public final List<Source> sources = new ArrayList<>();
    public volatile boolean done;
    public volatile int version;
    public volatile int scanned;

    private volatile Map<Category, TreeMap<String, List<String>>> result = new EnumMap<>(Category.class);
    private volatile boolean closed;

    public PackIndex(ResourcePackManager manager) {
        List<ResourcePackProfile> profiles = new ArrayList<>(manager.getProfiles());
        profiles.sort(Comparator.comparing(p -> p.getId().equals("vanilla") ? "" : p.getDisplayName().getString().toLowerCase(Locale.ROOT)));
        for (ResourcePackProfile p : profiles) {
            String id = p.getId();
            if (!id.equals("vanilla") && !id.startsWith("file/")) continue;
            try {
                sources.add(new Source(id, p.getDisplayName(), p.getDescription(), p.createResourcePack()));
            } catch (Exception ignored) {
            }
        }
        Thread t = new Thread(this::scan, "PackMixer-Scan");
        t.setDaemon(true);
        t.start();
    }

    private void scan() {
        Map<Category, TreeMap<String, List<String>>> out = new EnumMap<>(Category.class);
        for (Category c : Category.values()) out.put(c, new TreeMap<>());

        for (Source s : sources) {
            if (closed) return;
            try {
                for (String ns : s.pack.getNamespaces(ResourceType.CLIENT_RESOURCES)) {
                    for (Category c : Category.values()) {
                        TreeMap<String, List<String>> map = out.get(c);
                        for (String prefix : c.prefixes) {
                            s.pack.findResources(ResourceType.CLIENT_RESOURCES, ns, prefix, (id, supplier) -> {
                                if (!c.accepts(id.getPath())) return;
                                List<String> list = map.computeIfAbsent(id.toString(), k -> new ArrayList<>());
                                if (!list.contains(s.id)) {
                                    list.add(s.id);
                                    s.counts[c.ordinal()]++;
                                }
                            });
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            scanned++;
        }
        result = out;
        version++;
        done = true;
    }

    /** Tekstury danej kategorii: klucz -> lista id packów, które ją mają. */
    public Map<String, List<String>> textures(Category c) {
        Map<String, List<String>> m = result.get(c);
        return m == null ? Collections.emptyMap() : m;
    }

    public Source byId(String id) {
        if (id == null) return null;
        for (Source s : sources) if (s.id.equals(id)) return s;
        return null;
    }

    /** Pack, z którego tekstura byłaby wzięta bez miksu (najwyżsy włączony pack, który ją ma, inaczej vanilla). */
    public Source autoSource(String key, Collection<String> enabledIds) {
        List<String> has = textures(categoryOf(key)).get(key);
        if (has == null || has.isEmpty()) return null;
        List<String> order = new ArrayList<>(enabledIds);
        Collections.reverse(order);
        for (String id : order) if (has.contains(id)) return byId(id);
        return byId(has.contains("vanilla") ? "vanilla" : has.get(0));
    }

    public static Category categoryOf(String key) {
        for (Category c : Category.values()) {
            for (String prefix : c.prefixes) if (key.contains(":" + prefix + "/")) return c;
        }
        return Category.BLOCKS;
    }

    @Override
    public void close() {
        closed = true;
        for (Source s : sources) {
            try {
                s.pack.close();
            } catch (Exception ignored) {
            }
        }
    }
}
