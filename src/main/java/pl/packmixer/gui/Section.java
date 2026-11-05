package pl.packmixer.gui;

import pl.packmixer.pack.PackIndex;
import pl.packmixer.pack.PackIndex.Category;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Zakładki w bocznym menu. Tekstury są przypisywane do kategorii automatycznie po nazwie pliku. */
public enum Section {
    PACKS("Packi", "START", 0xFF5B8CFF),
    WEAPONS("Miecze i broń", "PRZEDMIOTY", 0xFFE5566A),
    TOOLS("Narzędzia", "PRZEDMIOTY", 0xFFFFC857),
    ARMOR("Zbroje", "PRZEDMIOTY", 0xFF4FD1E8),
    FOOD("Jedzenie", "PRZEDMIOTY", 0xFF9BDB4D),
    POTIONS("Mikstury", "PRZEDMIOTY", 0xFFB57BFF),
    MATERIALS("Materiały", "PRZEDMIOTY", 0xFFFF9F4A),
    ITEMS_OTHER("Inne przedmioty", "PRZEDMIOTY", 0xFF8D98B0),
    STONE("Kamień i rudy", "BLOKI", 0xFF9AA5B8),
    WOOD("Drewno", "BLOKI", 0xFFB9894F),
    NATURE("Natura", "BLOKI", 0xFF3DDC97),
    COLORED("Kolorowe", "BLOKI", 0xFFFF6FB5),
    REDSTONE("Redstone", "BLOKI", 0xFFFF4D4D),
    BLOCKS_OTHER("Inne bloki", "BLOKI", 0xFF8D98B0),
    MOBS("Moby", "ŚWIAT", 0xFF7BD88F),
    UI("GUI", "ŚWIAT", 0xFF5B8CFF),
    FONTS("Czcionki", "ŚWIAT", 0xFFE8ECF5),
    PAINT("Malarz", "NARZĘDZIA", 0xFFFF6FB5),
    PROFILES("Profile", "NARZĘDZIA", 0xFFFFC857);

    public final String label;
    public final String heading;
    public final int color;

    Section(String label, String heading, int color) {
        this.label = label;
        this.heading = heading;
        this.color = color;
    }

    public boolean isTextureSection() {
        return this != PACKS && this != PAINT && this != PROFILES;
    }

    public static Section of(String key) {
        return of(PackIndex.categoryOf(key), key);
    }

    public static Section of(Category c, String key) {
        String path = key.substring(key.indexOf(':') + 1);
        String n = baseName(path);
        return switch (c) {
            case ITEMS -> item(n);
            case BLOCKS -> block(n);
            case ENTITIES -> isEquipment(path) ? ARMOR : MOBS;
            case GUI -> UI;
            case FONTS -> FONTS;
        };
    }

    // ------------------------------------------------------------------ klasyfikacja po nazwie

    private static String baseName(String path) {
        String n = path.substring(path.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static boolean isEquipment(String path) {
        return path.contains("/equipment/") || path.contains("models/armor");
    }

    private static boolean endsAny(String n, String... ends) {
        for (String e : ends) if (n.endsWith(e)) return true;
        return false;
    }

    private static boolean startsAny(String n, String... starts) {
        for (String s : starts) if (n.startsWith(s)) return true;
        return false;
    }

    private static boolean containsAny(String n, String... parts) {
        for (String p : parts) if (n.contains(p)) return true;
        return false;
    }

    private static final Set<String> FOOD_EXACT = Set.of(
            "spider_eye", "rotten_flesh", "tropical_fish", "pufferfish", "dried_kelp", "pumpkin_pie", "melon_slice",
            "sweet_berries", "glow_berries", "honey_bottle", "chorus_fruit", "mushroom_stew", "rabbit_stew",
            "beetroot_soup", "suspicious_stew", "baked_potato", "poisonous_potato", "golden_carrot");
    private static final Set<String> FOOD_TOKENS = Set.of(
            "apple", "bread", "carrot", "potato", "beef", "porkchop", "chicken", "mutton", "rabbit", "cod", "salmon",
            "cooked", "cookie", "cake");
    private static final Set<String> MATERIAL_TOKENS = Set.of(
            "ingot", "nugget", "raw", "shard", "scrap", "dust", "crystals", "ball", "rod", "pearl", "tear", "string",
            "feather", "flint", "leather", "bone", "stick", "gunpowder", "coal", "charcoal", "diamond", "emerald",
            "quartz", "redstone", "lazuli", "brick", "paper", "book", "powder", "membrane", "scute", "wheat", "seeds");

    static Section item(String n) {
        if (endsAny(n, "_helmet", "_chestplate", "_leggings", "_boots", "_horse_armor") || n.equals("elytra")
                || n.equals("wolf_armor") || n.equals("turtle_scute")) {
            return n.equals("turtle_scute") ? MATERIALS : ARMOR;
        }
        if (n.endsWith("_sword") || startsAny(n, "bow", "crossbow", "arrow", "spectral_arrow", "tipped_arrow",
                "trident", "mace", "shield", "wind_charge")) return WEAPONS;
        if (endsAny(n, "_pickaxe", "_axe", "_shovel", "_hoe", "_bucket") || startsAny(n, "fishing_rod", "carrot_on_a_stick",
                "warped_fungus_on_a_stick") || Set.of("shears", "flint_and_steel", "brush", "spyglass", "compass",
                "recovery_compass", "clock", "lead", "name_tag", "bucket").contains(n)) return TOOLS;
        if (containsAny(n, "potion") || Set.of("experience_bottle", "dragon_breath", "ominous_bottle", "glass_bottle").contains(n)) return POTIONS;
        if (FOOD_EXACT.contains(n)) return FOOD;
        List<String> tokens = Arrays.asList(n.split("_"));
        for (String t : tokens) if (FOOD_TOKENS.contains(t)) return FOOD;
        for (String t : tokens) if (MATERIAL_TOKENS.contains(t)) return MATERIALS;
        return ITEMS_OTHER;
    }

    static Section block(String n) {
        if (containsAny(n, "wool", "concrete", "terracotta", "stained_glass", "carpet", "shulker", "glazed", "candle", "_bed", "banner")) return COLORED;
        if (n.endsWith("_ore") || n.contains("_ore_")) return STONE;
        if (containsAny(n, "redstone", "piston", "repeater", "comparator", "observer", "dispenser", "dropper", "hopper",
                "lever", "button", "rail", "tnt", "lamp", "daylight", "target", "note_block", "jukebox", "tripwire",
                "sculk_sensor", "lightning_rod")) return REDSTONE;
        if (containsAny(n, "planks", "_log", "log_", "_wood", "stripped_", "door", "trapdoor", "fence", "bookshelf",
                "crafting_table", "chest", "barrel", "ladder", "_sign")) return WOOD;
        if (containsAny(n, "stone", "cobble", "deepslate", "granite", "diorite", "andesite", "tuff", "basalt", "blackstone",
                "brick", "calcite", "obsidian", "netherrack", "purpur", "prismarine", "quartz", "copper", "iron", "gold",
                "diamond", "emerald", "lapis", "coal", "netherite", "amethyst", "furnace", "smoker")) return STONE;
        if (containsAny(n, "grass", "dirt", "sand", "gravel", "leaves", "flower", "sapling", "ice", "snow", "moss", "vine",
                "mushroom", "kelp", "coral", "water", "lava", "farmland", "mycelium", "podzol", "cactus", "pumpkin",
                "melon", "hay", "tulip", "rose", "fern", "azalea", "seagrass", "bamboo", "cave", "dripstone", "clay",
                "mud", "sponge", "bee", "honey", "turtle", "egg", "wheat", "carrots", "potatoes", "beetroots", "berry",
                "lily", "orchid", "allium", "poppy", "dandelion", "cornflower", "lilac", "peony", "sunflower")) return NATURE;
        return BLOCKS_OTHER;
    }
}
