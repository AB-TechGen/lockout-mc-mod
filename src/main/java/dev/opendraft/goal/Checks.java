package dev.opendraft.goal;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides, for each goal type in goals.json, whether the goal exists in this version of
 * the game ({@link #available}) and whether the player has completed it ({@link #done}).
 */
public final class Checks {
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final Map<String, Resolved<Item>> ITEMS = new ConcurrentHashMap<>();

    private Checks() {
    }

    // ---- availability -------------------------------------------------------------------

    public static boolean available(Goal g, MinecraftServer server) {
        return whyUnavailable(g, server) == null;
    }

    /** Null when the goal can be played; otherwise a short reason, for /opendraft goals. */
    public static String whyUnavailable(Goal g, MinecraftServer server) {
        try {
            return switch (g.type) {
                case "item", "items_unique" -> {
                    Resolved<Item> r = items(g, "items");
                    int need = g.type.equals("items_unique") ? g.integer("count", 1) : 1;
                    yield r.values.size() >= need ? null : "missing items " + r.missing;
                }
                case "items_all", "wear_any" -> {
                    Resolved<Item> r = items(g, "items");
                    yield r.missing.isEmpty() && !r.values.isEmpty() ? null : "missing items " + r.missing;
                }
                case "wear" -> {
                    for (String slot : List.of("head", "chest", "legs", "feet")) {
                        Resolved<Item> r = items(g, slot);
                        if (r.values.isEmpty()) yield "missing items " + r.missing;
                    }
                    yield null;
                }
                case "inventory_full" -> g.str("item", null) == null || items(g, "item").missing.isEmpty() ? null : "missing item";
                case "stat", "stat_unique" -> stats(g).isEmpty() ? "no matching statistics" : null;
                case "advancement" -> advancement(g, server) == null ? "advancement not in this version" : null;
                case "flag" -> flagsAvailable(g, server);
                case "counter_sum" -> {
                    for (String c : g.list("counters")) {
                        if (entityExists(c.substring(c.indexOf(':') + 1))) yield null;
                    }
                    yield "none of the mobs exist";
                }
                case "set" -> {
                    if (g.str("set", "").equals("biomes")) {
                        Registry<?> biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
                        for (String b : g.list("only")) if (!biomes.containsKey(Identifier.parse(b))) yield "missing biome " + b;
                    }
                    yield null;
                }
                case "advancement_count", "dimension", "position", "counter", "hunger_empty", "stack_any", "xp_level",
                     "wear_special", "item_check" -> null;
                default -> "unknown goal type " + g.type;
            };
        } catch (RuntimeException e) {
            return "error: " + e.getMessage();
        }
    }

    private static String flagsAvailable(Goal g, MinecraftServer server) {
        if (g.obj("requires").has("entities")) {
            for (var e : g.obj("requires").getAsJsonArray("entities")) {
                if (!entityExists(e.getAsString())) return "missing " + e.getAsString();
            }
        }
        List<String> all = g.list("all");
        if (!all.isEmpty()) {
            for (String f : all) if (!flagExists(f, server)) return "missing " + f;
            return null;
        }
        for (String f : g.list("any")) if (flagExists(f, server)) return null;
        return "nothing on this goal exists in this version";
    }

    /** Checks the ids inside a fact string, so goals for content this version lacks are left off boards. */
    private static boolean flagExists(String flag, MinecraftServer server) {
        int c = flag.indexOf(':');
        if (c < 0) {
            if (flag.equals("geyser")) return idContaining("geyser");
            return true;
        }
        String kind = flag.substring(0, c);
        String rest = flag.substring(c + 1);
        return switch (kind) {
            case "kill", "death_by", "death_direct", "tamed", "target", "spied", "leashed", "riding", "constructed", "bred" ->
                    entityExists(rest.split("[@#]")[0]);
            case "named" -> entityExists(rest.substring(0, rest.lastIndexOf(':')));
            case "ate", "brewed_item" -> itemExists(rest);
            case "brewed" -> BuiltInRegistries.POTION.containsKey(Identifier.parse(rest));
            case "effect" -> BuiltInRegistries.MOB_EFFECT.containsKey(Identifier.parse(rest));
            case "death_type" -> server.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).containsKey(Identifier.parse(rest));
            case "cushion" -> idContaining("cushion");
            default -> true;
        };
    }

    /** Whether any block, item or entity id contains {@code word}. */
    public static boolean idContaining(String word) {
        return BuiltInRegistries.BLOCK.keySet().stream().anyMatch(k -> k.getPath().contains(word))
                || BuiltInRegistries.ITEM.keySet().stream().anyMatch(k -> k.getPath().contains(word))
                || BuiltInRegistries.ENTITY_TYPE.keySet().stream().anyMatch(k -> k.getPath().contains(word));
    }

    private static boolean entityExists(String id) {
        Identifier i = Identifier.tryParse(id);
        return i != null && BuiltInRegistries.ENTITY_TYPE.containsKey(i);
    }

    private static boolean itemExists(String id) {
        Identifier i = Identifier.tryParse(id);
        return i != null && BuiltInRegistries.ITEM.containsKey(i);
    }

    // ---- baselines (taken when the board starts) ---------------------------------------------

    /** Progress that existed before the board started, so it doesn't count. Null for state goals. */
    public static Object baseline(Goal g, ServerPlayer p) {
        return switch (g.type) {
            case "stat" -> statSum(g, p);
            case "stat_unique" -> statValues(g, p);
            case "advancement" -> {
                AdvancementHolder a = advancement(g, p.level().getServer());
                yield a != null && p.getAdvancements().getOrStartProgress(a).isDone();
            }
            case "advancement_count" -> doneAdvancements(p);
            default -> null;
        };
    }

    // ---- completion -----------------------------------------------------------------------------

    public static boolean done(Goal g, ServerPlayer p, Tracker t, Object base) {
        return switch (g.type) {
            case "flag" -> {
                List<String> all = g.list("all");
                if (!all.isEmpty()) {
                    boolean ok = true;
                    for (String f : all) ok &= t.has(f);
                    yield ok;
                }
                boolean any = false;
                for (String f : g.list("any")) any |= t.has(f);
                yield any;
            }
            case "set" -> {
                Set<String> s = t.set(g.str("set", ""));
                List<String> only = g.list("only");
                long n = only.isEmpty() ? s.size() : only.stream().filter(s::contains).count();
                yield n >= g.integer("count", 1);
            }
            case "counter" -> t.counter(g.str("counter", "")) >= g.integer("min", 1);
            case "counter_sum" -> {
                int sum = 0;
                for (String c : g.list("counters")) sum += t.counter(c);
                yield sum >= g.integer("min", 1);
            }
            case "stat" -> statSum(g, p) - (base instanceof Long l ? l : 0L) >= g.integer("min", 1);
            case "stat_unique" -> {
                @SuppressWarnings("unchecked")
                Map<String, Integer> before = base instanceof Map<?, ?> m ? (Map<String, Integer>) m : Map.of();
                int n = 0;
                for (Map.Entry<String, Integer> e : statValues(g, p).entrySet()) {
                    if (e.getValue() > before.getOrDefault(e.getKey(), 0)) n++;
                }
                yield n >= g.integer("count", 1);
            }
            case "advancement" -> {
                if (Boolean.TRUE.equals(base)) yield false;
                AdvancementHolder a = advancement(g, p.level().getServer());
                yield a != null && p.getAdvancements().getOrStartProgress(a).isDone();
            }
            case "advancement_count" -> {
                Set<String> now = doneAdvancements(p);
                if (base instanceof Set<?> before) now.removeAll(before);
                yield now.size() >= g.integer("count", 1);
            }
            case "item" -> {
                Set<Item> wanted = items(g, "items").values;
                Map<Item, Integer> totals = new HashMap<>();
                for (ItemStack s : inventory(p, true)) if (wanted.contains(s.getItem())) totals.merge(s.getItem(), s.getCount(), Integer::sum);
                int need = g.integer("count", 1);
                yield totals.values().stream().anyMatch(v -> v >= need);
            }
            case "items_all" -> {
                Set<Item> have = new HashSet<>();
                for (ItemStack s : inventory(p, true)) have.add(s.getItem());
                yield have.containsAll(items(g, "items").values);
            }
            case "items_unique" -> {
                Set<Item> wanted = items(g, "items").values;
                Set<Item> have = new HashSet<>();
                for (ItemStack s : inventory(p, true)) if (wanted.contains(s.getItem())) have.add(s.getItem());
                yield have.size() >= g.integer("count", 1);
            }
            case "stack_any" -> inventory(p, false).stream().anyMatch(s -> s.getCount() >= g.integer("count", 64));
            case "inventory_full" -> inventoryFull(g, p);
            case "item_check" -> itemCheck(g.str("check", ""), p);
            case "dimension" -> Tracker.dimension(p.level()).equals(g.str("dimension", ""));
            case "position" -> position(g.str("where", ""), p);
            case "xp_level" -> p.experienceLevel >= g.integer("level", 1);
            case "hunger_empty" -> p.getFoodData().getFoodLevel() <= 0;
            case "wear" -> {
                boolean ok = true;
                String[] names = {"head", "chest", "legs", "feet"};
                for (int i = 0; i < 4; i++) ok &= items(g, names[i]).values.contains(p.getItemBySlot(ARMOR[i]).getItem());
                yield ok;
            }
            case "wear_any" -> {
                Set<Item> wanted = items(g, "items").values;
                String dye = g.str("dyed", null);
                boolean ok = false;
                for (EquipmentSlot slot : ARMOR) {
                    ItemStack s = p.getItemBySlot(slot);
                    if (!wanted.contains(s.getItem())) continue;
                    ok |= dye == null || dyedAs(s, dye);
                }
                yield ok;
            }
            case "wear_special" -> wearSpecial(g.str("mode", ""), p);
            default -> false;
        };
    }

    /**
     * How far along a counting goal is, as "3/5", for the board's hover text. Null for goals that
     * are simply done or not (or need just one of something).
     */
    public static String progress(Goal g, ServerPlayer p, Tracker t, Object base) {
        long have;
        long need;
        switch (g.type) {
            case "flag" -> {
                List<String> all = g.list("all");
                if (all.size() < 2) return null;
                have = all.stream().filter(t::has).count();
                need = all.size();
            }
            case "set" -> {
                Set<String> s = t.set(g.str("set", ""));
                List<String> only = g.list("only");
                have = only.isEmpty() ? s.size() : only.stream().filter(s::contains).count();
                need = g.integer("count", 1);
            }
            case "counter" -> {
                have = t.counter(g.str("counter", ""));
                need = g.integer("min", 1);
            }
            case "counter_sum" -> {
                have = 0;
                for (String c : g.list("counters")) have += t.counter(c);
                need = g.integer("min", 1);
            }
            case "stat" -> {
                have = statSum(g, p) - (base instanceof Long l ? l : 0L);
                need = g.integer("min", 1);
            }
            case "stat_unique" -> {
                @SuppressWarnings("unchecked")
                Map<String, Integer> before = base instanceof Map<?, ?> m ? (Map<String, Integer>) m : Map.of();
                have = statValues(g, p).entrySet().stream().filter(e -> e.getValue() > before.getOrDefault(e.getKey(), 0)).count();
                need = g.integer("count", 1);
            }
            case "advancement_count" -> {
                Set<String> now = doneAdvancements(p);
                if (base instanceof Set<?> before) now.removeAll(before);
                have = now.size();
                need = g.integer("count", 1);
            }
            case "item" -> {
                Set<Item> wanted = items(g, "items").values;
                Map<Item, Integer> totals = new HashMap<>();
                for (ItemStack s : inventory(p, true)) if (wanted.contains(s.getItem())) totals.merge(s.getItem(), s.getCount(), Integer::sum);
                have = totals.values().stream().mapToInt(Integer::intValue).max().orElse(0);
                need = g.integer("count", 1);
            }
            case "items_all" -> {
                Set<Item> wanted = items(g, "items").values;
                Set<Item> got = new HashSet<>();
                for (ItemStack s : inventory(p, true)) if (wanted.contains(s.getItem())) got.add(s.getItem());
                have = got.size();
                need = wanted.size();
            }
            case "items_unique" -> {
                Set<Item> wanted = items(g, "items").values;
                Set<Item> got = new HashSet<>();
                for (ItemStack s : inventory(p, true)) if (wanted.contains(s.getItem())) got.add(s.getItem());
                have = got.size();
                need = g.integer("count", 1);
            }
            case "stack_any" -> {
                have = inventory(p, false).stream().mapToInt(ItemStack::getCount).max().orElse(0);
                need = g.integer("count", 64);
            }
            case "xp_level" -> {
                have = p.experienceLevel;
                need = g.integer("level", 1);
            }
            default -> {
                return null;
            }
        }
        if (need <= 1) return null;
        return Math.max(0, Math.min(have, need)) + "/" + need;
    }

    // ---- items ---------------------------------------------------------------------------------

    record Resolved<T>(Set<T> values, List<String> missing) {
    }

    /**
     * Resolves item patterns: "minecraft:x" exact, "#minecraft:tag" a tag, "*_suffix" or
     * "prefix_*" any vanilla item whose name ends or starts that way.
     */
    static Resolved<Item> items(Goal g, String field) {
        return ITEMS.computeIfAbsent(g.key + "/" + field, k -> {
            Set<Item> out = new LinkedHashSet<>();
            List<String> missing = new ArrayList<>();
            for (String pat : g.list(field)) {
                if (pat.startsWith("#")) {
                    TagKey<Item> tag = TagKey.create(Registries.ITEM, Identifier.parse(pat.substring(1)));
                    for (Item item : BuiltInRegistries.ITEM) if (item.getDefaultInstance().is(tag)) out.add(item);
                } else if (pat.startsWith("*") || pat.endsWith("*")) {
                    for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
                        if (matches(id, pat)) out.add(BuiltInRegistries.ITEM.getValue(id));
                    }
                } else if (itemExists(pat)) {
                    out.add(BuiltInRegistries.ITEM.getValue(Identifier.parse(pat)));
                } else {
                    missing.add(pat);
                }
            }
            out.remove(Items.AIR);
            return new Resolved<>(out, missing);
        });
    }

    private static boolean matches(Identifier id, String pat) {
        if (!id.getNamespace().equals("minecraft")) return false;
        String path = id.getPath();
        if (pat.equals("*")) return true;
        if (pat.startsWith("*")) return path.endsWith(pat.substring(1));
        return path.startsWith(pat.substring(0, pat.length() - 1));
    }

    /** Main inventory, plus offhand and armor when {@code withEquipment}. Empty stacks are skipped. */
    static List<ItemStack> inventory(ServerPlayer p, boolean withEquipment) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty()) out.add(s);
        }
        if (withEquipment) {
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                ItemStack s = p.getItemBySlot(slot);
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }

    private static boolean inventoryFull(Goal g, ServerPlayer p) {
        String only = g.str("item", null);
        Set<Item> seen = new HashSet<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty()) return false;
            if (only != null) {
                if (!Tracker.id(s).equals(only)) return false;
            } else if (!seen.add(s.getItem())) {
                return false;
            }
        }
        return true;
    }

    private static boolean itemCheck(String check, ServerPlayer p) {
        for (ItemStack s : inventory(p, true)) {
            switch (check) {
                case "locked_map" -> {
                    if (s.is(Items.FILLED_MAP)) {
                        MapItemSavedData data = MapItem.getSavedData(s, p.level());
                        if (data != null && data.locked) return true;
                    }
                }
                case "banner_shield" -> {
                    if (s.is(Items.SHIELD) && s.get(DataComponents.BASE_COLOR) != null) return true;
                }
                case "bundle_in_bundle" -> {
                    BundleContents contents = s.get(DataComponents.BUNDLE_CONTENTS);
                    if (contents != null) {
                        for (ItemStackTemplate inner : contents.items()) {
                            if (inner.create().has(DataComponents.BUNDLE_CONTENTS)) return true;
                        }
                    }
                }
                default -> {
                    return false;
                }
            }
        }
        return false;
    }

    private static boolean position(String where, ServerPlayer p) {
        Level level = p.level();
        String dim = Tracker.dimension(level);
        double y = p.getY();
        return switch (where) {
            case "nether_roof" -> dim.equals("nether") && y >= 128;
            case "height_limit" -> dim.equals("overworld") ? y >= 320 : y >= 256;
            case "bedrock" -> level.getBlockState(p.blockPosition().below()).is(net.minecraft.world.level.block.Blocks.BEDROCK)
                    && !(dim.equals("nether") && y >= 100);
            default -> false;
        };
    }

    private static boolean dyedAs(ItemStack s, String color) {
        DyedItemColor dyed = s.get(DataComponents.DYED_COLOR);
        DyeColor dye = DyeColor.byName(color, null);
        // One dye on undyed leather gives exactly that dye's colour.
        return dyed != null && dye != null && (dyed.rgb() & 0xFFFFFF) == (dye.getTextureDiffuseColor() & 0xFFFFFF);
    }

    private static boolean wearSpecial(String mode, ServerPlayer p) {
        ItemStack[] worn = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            worn[i] = p.getItemBySlot(ARMOR[i]);
            if (worn[i].isEmpty()) return false;
        }
        switch (mode) {
            case "enchanted" -> {
                for (ItemStack s : worn) if (!s.isEnchanted()) return false;
                return true;
            }
            case "dyed_distinct" -> {
                String[] pieces = {"minecraft:leather_helmet", "minecraft:leather_chestplate", "minecraft:leather_leggings", "minecraft:leather_boots"};
                Set<Integer> colors = new HashSet<>();
                for (int i = 0; i < 4; i++) {
                    DyedItemColor d = worn[i].get(DataComponents.DYED_COLOR);
                    if (!Tracker.id(worn[i]).equals(pieces[i]) || d == null) return false;
                    colors.add(d.rgb() & 0xFFFFFF);
                }
                return colors.size() == 4;
            }
            case "distinct_materials" -> {
                String[] suffix = {"_helmet", "_chestplate", "_leggings", "_boots"};
                Set<String> materials = new HashSet<>();
                for (int i = 0; i < 4; i++) {
                    String path = Tracker.id(worn[i]).substring("minecraft:".length());
                    if (!path.endsWith(suffix[i])) return false;
                    materials.add(path.substring(0, path.length() - suffix[i].length()));
                }
                return materials.size() == 4;
            }
            default -> {
                return false;
            }
        }
    }

    // ---- statistics ------------------------------------------------------------------------------

    private static final Map<String, List<Map.Entry<String, Stat<?>>>> STATS = new ConcurrentHashMap<>();

    /** Resolves the goal's statistic keys to vanilla statistics, keeping the key name for uniqueness. */
    static List<Map.Entry<String, Stat<?>>> stats(Goal g) {
        return STATS.computeIfAbsent(g.key, k -> {
            List<Map.Entry<String, Stat<?>>> out = new ArrayList<>();
            String type = g.str("stat", "custom");
            for (String pat : g.list("keys")) {
                if (!pat.contains(":") && !pat.contains("*")) pat = "minecraft:" + pat;
                boolean wild = pat.startsWith("*") || pat.endsWith("*");
                switch (type) {
                    case "custom" -> {
                        Identifier id = Identifier.tryParse(pat);
                        if (id != null && BuiltInRegistries.CUSTOM_STAT.containsKey(id)) {
                            out.add(Map.entry(pat, Stats.CUSTOM.get(BuiltInRegistries.CUSTOM_STAT.getValue(id))));
                        }
                    }
                    case "mined" -> {
                        for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
                            if (wild ? matches(id, pat) : id.toString().equals(pat)) {
                                out.add(Map.entry(id.toString(), Stats.BLOCK_MINED.get(BuiltInRegistries.BLOCK.getValue(id))));
                            }
                        }
                    }
                    case "used", "crafted", "broken" -> {
                        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
                            if (!(wild ? matches(id, pat) : id.toString().equals(pat))) continue;
                            Item item = BuiltInRegistries.ITEM.getValue(id);
                            Stat<?> stat = switch (type) {
                                case "used" -> Stats.ITEM_USED.get(item);
                                case "crafted" -> Stats.ITEM_CRAFTED.get(item);
                                default -> Stats.ITEM_BROKEN.get(item);
                            };
                            out.add(Map.entry(id.toString(), stat));
                        }
                    }
                    default -> {
                    }
                }
            }
            return out;
        });
    }

    private static long statSum(Goal g, ServerPlayer p) {
        long sum = 0;
        for (Map.Entry<String, Stat<?>> e : stats(g)) sum += p.getStats().getValue(e.getValue());
        return sum;
    }

    private static Map<String, Integer> statValues(Goal g, ServerPlayer p) {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, Stat<?>> e : stats(g)) {
            int v = p.getStats().getValue(e.getValue());
            if (v > 0) out.merge(e.getKey(), v, Integer::sum);
        }
        return out;
    }

    // ---- advancements -------------------------------------------------------------------------------

    private static final Map<String, Identifier> ADVANCEMENT_BY_TITLE = new ConcurrentHashMap<>();

    /** Finds the goal's advancement by id, or by its English title when the id moved between versions. */
    static AdvancementHolder advancement(Goal g, MinecraftServer server) {
        var manager = server.getAdvancements();
        for (String id : g.list("advancements")) {
            AdvancementHolder h = manager.get(Identifier.parse(id));
            if (h != null) return h;
        }
        String title = g.str("title", null);
        if (title == null) return null;
        Identifier cached = ADVANCEMENT_BY_TITLE.get(title);
        if (cached != null) return manager.get(cached);
        for (AdvancementHolder h : manager.getAllAdvancements()) {
            if (h.value().display().isPresent() && title.equalsIgnoreCase(titleOf(h.value().display().get()))) {
                ADVANCEMENT_BY_TITLE.put(title, h.id());
                return h;
            }
        }
        return null;
    }

    /**
     * DisplayInfo's accessors have been renamed between versions (getTitle or title, getType or type),
     * so look them up reflectively instead of tying the build to one name.
     */
    public static String titleOf(Object display) {
        Object t = invoke(display, "title", "getTitle");
        return t instanceof net.minecraft.network.chat.Component c ? c.getString() : "";
    }

    /** "task", "goal" or "challenge", which picks the chat message and its colour. */
    public static String frameOf(Object display) {
        Object t = invoke(display, "getType", "type", "frame", "getFrame");
        return t == null ? "task" : t.toString().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean announces(Object display) {
        Object b = invoke(display, "shouldAnnounceChat", "announceChat", "announceToChat");
        return !(b instanceof Boolean) || (Boolean) b;
    }

    private static final Map<String, java.lang.reflect.Method> METHODS = new ConcurrentHashMap<>();

    private static Object invoke(Object target, String... names) {
        for (String name : names) {
            String k = target.getClass().getName() + "#" + name;
            java.lang.reflect.Method m = METHODS.get(k);
            if (m == null) {
                try {
                    m = target.getClass().getMethod(name);
                } catch (NoSuchMethodException e) {
                    continue;
                }
                METHODS.put(k, m);
            }
            try {
                return m.invoke(target);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    /** Completed advancements that vanilla announces in chat, by id, with their display info. */
    public static Map<String, Object> announcedAdvancements(ServerPlayer p) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (AdvancementHolder h : p.level().getServer().getAdvancements().getAllAdvancements()) {
            if (h.value().display().isEmpty()) continue;
            if (!p.getAdvancements().getOrStartProgress(h).isDone()) continue;
            Object display = h.value().display().get();
            if (announces(display)) out.put(h.id().toString(), display);
        }
        return out;
    }

    /** Advancements with a toast (not recipe unlocks) that the player has completed. */
    private static Set<String> doneAdvancements(ServerPlayer p) {
        Set<String> out = new HashSet<>();
        for (AdvancementHolder h : p.level().getServer().getAdvancements().getAllAdvancements()) {
            if (h.value().display().isEmpty()) continue;
            AdvancementProgress progress = p.getAdvancements().getOrStartProgress(h);
            if (progress.isDone()) out.add(h.id().toString());
        }
        return out;
    }
}
