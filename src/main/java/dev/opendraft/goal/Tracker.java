package dev.opendraft.goal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Records what the local player does during one board, so goals can be checked later.
 * Everything here runs on the integrated server thread. A new board gets a fresh tracker,
 * so nothing done before the start counts. It's saved with the world, so leaving mid-board and
 * coming back carries on from where it was.
 *
 * <p>Facts are strings such as {@code kill:minecraft:bat} or {@code death_type:minecraft:drown};
 * goals.json refers to the same strings.
 */
public final class Tracker {
    private static final Set<String> VINES = Set.of("minecraft:vine", "minecraft:twisting_vines",
            "minecraft:twisting_vines_plant", "minecraft:weeping_vines", "minecraft:weeping_vines_plant",
            "minecraft:cave_vines", "minecraft:cave_vines_plant");
    private static final Set<String> HOSTILE_MOUNTS = Set.of("minecraft:skeleton_horse", "minecraft:zombie_horse",
            "minecraft:zombie_nautilus", "minecraft:camel_husk");

    public final int boardId;
    public final UUID player;
    public final Map<String, Object> baselines = new HashMap<>();

    private final Set<String> flags = new HashSet<>();
    private final Map<String, Set<String>> sets = new HashMap<>();
    private final Map<String, Integer> counters = new HashMap<>();

    private Set<String> lastEffects = new HashSet<>();
    private boolean climbedVine;
    private final boolean[] crossbowFirework = new boolean[2];
    private final List<Pending> pending = new ArrayList<>();
    private Entity pearl;
    private long pearlCrossedAt;
    private Set<String> bredBaseline;
    private long ticks;

    public Tracker(int boardId, UUID player) {
        this.boardId = boardId;
        this.player = player;
    }

    // ---- fact storage ---------------------------------------------------------------

    public boolean has(String flag) {
        return flags.contains(flag);
    }

    public Set<String> set(String name) {
        return sets.getOrDefault(name, Set.of());
    }

    public int counter(String name) {
        return counters.getOrDefault(name, 0);
    }

    void flag(String f) {
        flags.add(f);
    }

    void add(String set, String value) {
        sets.computeIfAbsent(set, k -> new HashSet<>()).add(value);
    }

    void inc(String counter, int by) {
        counters.merge(counter, by, Integer::sum);
    }

    void max(String counter, int value) {
        counters.merge(counter, value, Math::max);
    }

    // ---- saving with the world ------------------------------------------------------------

    /**
     * The baselines and everything recorded, so the board carries on where it was when the world is closed and
     * reopened mid-board (leaving and rejoining the match). What's only watched from tick to tick isn't kept.
     */
    public JsonObject save() {
        JsonObject o = new JsonObject();
        JsonArray f = new JsonArray();
        flags.forEach(f::add);
        o.add("flags", f);
        JsonObject s = new JsonObject();
        sets.forEach((k, v) -> s.add(k, strings(v)));
        o.add("sets", s);
        JsonObject c = new JsonObject();
        counters.forEach(c::addProperty);
        o.add("counters", c);
        JsonObject b = new JsonObject();
        baselines.forEach((k, v) -> {
            // The kinds Checks.baseline gives: a stat total, whether an advancement was done, a stat per key, advancements done.
            if (v instanceof Long l) b.addProperty(k, l);
            else if (v instanceof Boolean bool) b.addProperty(k, bool);
            else if (v instanceof Map<?, ?> m) {
                JsonObject values = new JsonObject();
                m.forEach((key, n) -> values.addProperty(String.valueOf(key), (Number) n));
                b.add(k, values);
            } else if (v instanceof Set<?> set) b.add(k, strings(set));
        });
        o.add("baselines", b);
        return o;
    }

    /** A tracker from {@link #save}. */
    public static Tracker load(JsonObject o, int boardId, UUID player) {
        Tracker t = new Tracker(boardId, player);
        o.getAsJsonArray("flags").forEach(e -> t.flags.add(e.getAsString()));
        o.getAsJsonObject("sets").entrySet().forEach(e -> {
            Set<String> values = new HashSet<>();
            e.getValue().getAsJsonArray().forEach(v -> values.add(v.getAsString()));
            t.sets.put(e.getKey(), values);
        });
        o.getAsJsonObject("counters").entrySet().forEach(e -> t.counters.put(e.getKey(), e.getValue().getAsInt()));
        o.getAsJsonObject("baselines").entrySet().forEach(e -> {
            JsonElement v = e.getValue();
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) {
                t.baselines.put(e.getKey(), v.getAsBoolean());
            } else if (v.isJsonPrimitive()) {
                t.baselines.put(e.getKey(), v.getAsLong());
            } else if (v.isJsonObject()) {
                Map<String, Integer> values = new HashMap<>();
                v.getAsJsonObject().entrySet().forEach(n -> values.put(n.getKey(), n.getValue().getAsInt()));
                t.baselines.put(e.getKey(), values);
            } else if (v.isJsonArray()) {
                Set<String> values = new HashSet<>();
                v.getAsJsonArray().forEach(n -> values.add(n.getAsString()));
                t.baselines.put(e.getKey(), values);
            }
        });
        return t;
    }

    private static JsonArray strings(Set<?> values) {
        JsonArray a = new JsonArray();
        for (Object v : values) a.add(String.valueOf(v));
        return a;
    }

    // ---- helpers ----------------------------------------------------------------------

    public static String id(EntityType<?> type) {
        return EntityType.getKey(type).toString();
    }

    public static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static String id(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /** The dye colour named in an id such as "minecraft:light_blue_cushion", longest name first. */
    static String colorIn(String id) {
        String best = null;
        for (DyeColor c : DyeColor.values()) {
            String n = c.getSerializedName();
            if (id.contains(n) && (best == null || n.length() > best.length())) best = n;
        }
        return best;
    }

    static String dimension(Level level) {
        if (level.dimension() == Level.NETHER) return "nether";
        if (level.dimension() == Level.END) return "end";
        if (level.dimension() == Level.OVERWORLD) return "overworld";
        return "other";
    }

    static String damageType(ServerLevel level, DamageSource source) {
        Identifier id = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getKey(source.type());
        return id == null ? "unknown" : id.toString();
    }

    // ---- polling, every server tick -------------------------------------------------------

    public void tick(ServerPlayer p) {
        ticks++;
        ServerLevel level = (ServerLevel) p.level();

        // Status effects: Draftout counts the moment an effect is gained.
        Set<String> now = new HashSet<>();
        for (MobEffectInstance inst : p.getActiveEffects()) {
            Identifier eid = BuiltInRegistries.MOB_EFFECT.getKey(inst.getEffect().value());
            if (eid != null) now.add(eid.toString());
        }
        boolean gained = false;
        for (String e : now) {
            if (!lastEffects.contains(e)) {
                flag("effect:" + e);
                gained = true;
            }
        }
        if (gained) max("effects_on_gain", now.size());
        lastEffects = now;

        // Vines: remember climbing until we touch the ground, for "Die by falling off Vines".
        if (p.onClimbable() && VINES.contains(id(level.getBlockState(p.blockPosition())))) climbedVine = true;
        else if (p.onGround() || p.isInWater()) climbedVine = false;

        // Crossbow loaded with a firework that is now empty means it was fired.
        InteractionHand[] hands = InteractionHand.values();
        for (int i = 0; i < hands.length; i++) {
            ItemStack s = p.getItemInHand(hands[i]);
            ChargedProjectiles cp = s.is(Items.CROSSBOW) ? s.get(DataComponents.CHARGED_PROJECTILES) : null;
            boolean loaded = cp != null && cp.contains(Items.FIREWORK_ROCKET);
            if (crossbowFirework[i] && s.is(Items.CROSSBOW) && (cp == null || cp.isEmpty())) flag("crossbow_firework");
            crossbowFirework[i] = loaded;
        }

        if (p.getItemBySlot(EquipmentSlot.HEAD).is(Items.CARVED_PUMPKIN)) inc("pumpkin_ticks", 1);
        if (p.getCooldowns().isOnCooldown(new ItemStack(Items.SHIELD))) flag("shield_disabled");

        Entity vehicle = p.getVehicle();
        if (vehicle != null) {
            flag("riding:" + id(vehicle.getType()));
            // Cushions: the seat entity itself, or whatever block the seat sits on or in.
            List<String> seats = new ArrayList<>(List.of(id(vehicle.getType())));
            for (BlockPos pos : new BlockPos[]{vehicle.blockPosition(), vehicle.blockPosition().below(), p.blockPosition()}) {
                seats.add(id(level.getBlockState(pos)));
            }
            for (String seat : seats) {
                if (!seat.contains("cushion")) continue;
                String color = colorIn(seat);
                if (color != null) flag("cushion:" + color);
            }
        }

        // Geysers launch you upward; look for one just below while moving up fast.
        if (p.getDeltaMovement().y > 0.5) {
            for (int dy = 0; dy <= 4; dy++) {
                if (id(level.getBlockState(p.blockPosition().below(dy))).contains("geyser")) {
                    flag("geyser");
                    break;
                }
            }
        }

        if (p.isScoping() && ticks % 2 == 0) spy(p, level);
        if (ticks % 10 == 0) {
            Identifier biome = level.registryAccess().lookupOrThrow(Registries.BIOME).getKey(level.getBiome(p.blockPosition()).value());
            if (biome != null) add("biomes", biome.toString());
            scanEntities(p, level);
        }
        if (ticks % 20 == 0) bred(p);
        runPending(level);
    }

    private void spy(ServerPlayer p, ServerLevel level) {
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getViewVector(1.0F).scale(128));
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : level.getEntities(p, new AABB(eye, end).inflate(1.0))) {
            if (!(e instanceof LivingEntity) || !e.isAlive()) continue;
            Optional<Vec3> hit = e.getBoundingBox().inflate(0.3).clip(eye, end);
            if (hit.isEmpty()) continue;
            double d = eye.distanceToSqr(hit.get());
            if (d < bestDist && p.hasLineOfSight(e)) {
                best = e;
                bestDist = d;
            }
        }
        if (best != null) {
            String type = id(best.getType());
            flag("spied:" + type);
            add("spied", type);
        }
    }

    private void scanEntities(ServerPlayer p, ServerLevel level) {
        Set<String> leashed = new HashSet<>();
        for (Entity e : level.getEntities(p, p.getBoundingBox().inflate(48))) {
            String type = id(e.getType());
            if (e instanceof Leashable l && l.isLeashed() && l.getLeashHolder() == p) {
                leashed.add(type);
                flag("leashed:" + type);
            }
            if (e instanceof OwnableEntity o && o.getOwner() == p) {
                flag("tamed:" + type);
                if (type.equals("minecraft:wolf") && e instanceof LivingEntity wolf
                        && wolf.getItemBySlot(EquipmentSlot.BODY).is(Items.WOLF_ARMOR)) {
                    flag("wolf_armor");
                }
            }
            if (e instanceof Mob m && m.getTarget() == p) flag("target:" + type);
            if (type.equals("minecraft:fox") && e instanceof LivingEntity fox
                    && fox.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.DIAMOND)) {
                flag("fox_diamond");
            }
            // Golems built from blocks appear next to the player; spawned ones are rare enough to ignore.
            if (type.equals("minecraft:copper_golem") && e.tickCount < 40 && e.distanceToSqr(p) < 100) {
                flag("constructed:" + type);
            }
        }
        max("leashed_unique", leashed.size());
    }

    /** Breeding has no event, but vanilla's "Two by Two" advancement records each species bred. */
    private void bred(ServerPlayer p) {
        AdvancementHolder adv = p.level().getServer().getAdvancements()
                .get(Identifier.withDefaultNamespace("husbandry/bred_all_animals"));
        if (adv == null) return;
        AdvancementProgress progress = p.getAdvancements().getOrStartProgress(adv);
        Set<String> done = new HashSet<>();
        for (String c : progress.getCompletedCriteria()) done.add(c.contains(":") ? c : "minecraft:" + c);
        if (bredBaseline == null) {
            bredBaseline = done;
            return;
        }
        for (String c : done) {
            if (!bredBaseline.contains(c)) {
                flag("bred:" + c);
                add("bred", c);
            }
        }
    }

    // ---- interaction events ---------------------------------------------------------------

    public void onKill(ServerPlayer p, LivingEntity victim, DamageSource source) {
        ServerLevel level = (ServerLevel) p.level();
        String type = id(victim.getType());
        flag("kill:" + type);
        flag("kill:" + type + "@" + dimension(level));
        flag("kill:" + type + "#" + damageType(level, source));
        inc("kills:" + type, 1);
        if (type.equals("minecraft:sheep")) {
            DyeColor color = victim.get(DataComponents.SHEEP_COLOR);
            if (color != null) flag("kill_sheep:" + color.getSerializedName());
        }
        boolean hostile = victim.getType().getCategory() == MobCategory.MONSTER;
        if (!hostile && HOSTILE_MOUNTS.contains(type)) {
            for (Entity rider : victim.getPassengers()) {
                if (rider.getType().getCategory() == MobCategory.MONSTER) hostile = true;
            }
        }
        if (hostile) add("hostile_kills", type);
    }

    public void onDeath(ServerPlayer p, DamageSource source) {
        ServerLevel level = (ServerLevel) p.level();
        String dmg = damageType(level, source);
        flag("death_type:" + dmg);
        if (source.getEntity() != null) flag("death_by:" + id(source.getEntity().getType()));
        if (source.getDirectEntity() != null) flag("death_direct:" + id(source.getDirectEntity().getType()));
        if (dmg.equals("minecraft:fall") && climbedVine) flag("death:fell_off_vines");
        climbedVine = false;
    }

    public void onUseEntity(ServerPlayer p, ItemStack held, Entity target) {
        String type = id(target.getType());
        if (held.is(Items.NAME_TAG) && held.has(DataComponents.CUSTOM_NAME) && target instanceof LivingEntity) {
            flag("named:" + type + ":" + held.getHoverName().getString());
        }
        if (held.is(Items.AMETHYST_SHARD) && target instanceof Allay allay && allay.isDancing()) flag("allay_dup");
        if (held.is(Items.ITEM_FRAME) && type.equals("minecraft:item_frame") && target instanceof ItemFrame frame
                && frame.getItem().isEmpty()) {
            flag("frame_in_frame");
        }
        if (type.equals("minecraft:armor_stand")) pending.add(new Pending("armor_stand", null, target, 5));
    }

    public void onUseBlock(ServerPlayer p, ItemStack held, BlockPos pos) {
        ServerLevel level = (ServerLevel) p.level();
        BlockState state = level.getBlockState(pos);
        String block = id(state);
        if ((held.is(Items.FLINT_AND_STEEL) || held.is(Items.FIRE_CHARGE)) && block.contains("candle")
                && state.hasProperty(BlockStateProperties.LIT) && !state.getValue(BlockStateProperties.LIT)) {
            flag("light_candle");
        }
        if (held.is(Items.GLOW_INK_SAC) && block.contains("sign")) {
            if (block.contains("crimson")) flag("glow_ink:crimson");
            if (block.contains("warped")) flag("glow_ink:warped");
        }
        if (state.hasProperty(ComposterBlock.LEVEL) && block.equals("minecraft:composter")) {
            int lvl = state.getValue(ComposterBlock.LEVEL);
            if (lvl == ComposterBlock.READY) {
                flag("composter_bonemeal");
            } else if (lvl < 7 && held.has(DataComponents.FOOD) && held.has(DataComponents.COMPOSTABLE)) {
                add("composted", id(held));
            }
        }
        switch (block) {
            case "minecraft:decorated_pot" -> pending.add(new Pending("pot", pos, null, 5));
            case "minecraft:campfire", "minecraft:soul_campfire" -> pending.add(new Pending("campfire", pos, null, 5));
            case "minecraft:chiseled_bookshelf" -> pending.add(new Pending("bookshelf", pos, null, 5));
            case "minecraft:suspicious_sand", "minecraft:suspicious_gravel" -> {
                if (held.is(Items.BRUSH)) pending.add(new Pending("brush", pos, null, 20 * 20));
            }
            default -> {
            }
        }
    }

    public void onAttack(ServerPlayer p, Entity target) {
        if (id(target.getType()).equals("minecraft:end_crystal")) flag("end_crystal");
    }

    public void onEntityChangedWorld(ServerPlayer p, Entity entity) {
        if (id(entity.getType()).equals("minecraft:ender_pearl") && entity instanceof TraceableEntity t && t.getOwner() == p) {
            pearl = entity;
            pearlCrossedAt = ticks;
        }
    }

    public void onPlayerChangedWorld(ServerPlayer p, ServerLevel origin, ServerLevel dest) {
        Set<String> dims = Set.of(dimension(origin), dimension(dest));
        if (pearl != null && dims.equals(Set.of("overworld", "nether")) && ticks - pearlCrossedAt < 20 * 60
                && pearl.level() == dest && pearl.position().distanceToSqr(p.position()) < 16) {
            flag("pearl_portal");
        }
    }

    /** From the menu mixin: the player took a result out of a workstation. */
    public void onTookResult(String station, ItemStack result) {
        if (station.equals("loom") && !id(result).endsWith("_banner")) return;
        flag("took:" + station);
    }

    /** From the menu mixin: the player took a potion out of a brewing stand. */
    public void onTookPotion(ItemStack potion) {
        flag("brewed_item:" + id(potion));
        PotionContents contents = potion.get(DataComponents.POTION_CONTENTS);
        if (contents != null && contents.potion().isPresent()) {
            Identifier pid = BuiltInRegistries.POTION.getKey(contents.potion().get().value());
            if (pid != null) flag("brewed:" + pid);
        }
    }

    /** From the item mixin: the player finished eating or drinking. */
    public void onConsumed(ServerPlayer p, ItemStack stack) {
        String item = id(stack);
        flag("ate:" + item);
        if (stack.has(DataComponents.FOOD)) add("consumed", item);
        if (stack.is(Items.POTION)) {
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            if (contents != null && contents.potion().isPresent()
                    && "minecraft:water".equals(String.valueOf(BuiltInRegistries.POTION.getKey(contents.potion().get().value())))) {
                flag("drank:water");
            }
        }
        if (stack.is(Items.MILK_BUCKET) && !p.getActiveEffects().isEmpty()) flag("milk_cleared");
    }

    // ---- delayed checks ---------------------------------------------------------------------

    /** Some actions only show their result a tick later, so we look again shortly after. */
    private record Pending(String kind, BlockPos pos, Entity entity, int[] left) {
        Pending(String kind, BlockPos pos, Entity entity, int ticks) {
            this(kind, pos, entity, new int[]{ticks});
        }
    }

    private void runPending(ServerLevel level) {
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending pd = it.next();
            if (pd.left()[0]-- <= 0) {
                it.remove();
                continue;
            }
            BlockEntity be = pd.pos() == null ? null : level.getBlockEntity(pd.pos());
            switch (pd.kind()) {
                case "pot" -> {
                    if (be instanceof Container c && !c.getItem(0).isEmpty()
                            && c.getItem(0).getCount() >= c.getItem(0).getMaxStackSize()) {
                        flag("pot_filled");
                    }
                }
                case "campfire" -> {
                    if (be instanceof CampfireBlockEntity cf && cf.getItems().stream().noneMatch(ItemStack::isEmpty)) {
                        flag("campfire_filled");
                    }
                }
                case "bookshelf" -> {
                    if (be instanceof Container c) {
                        boolean full = c.getContainerSize() > 0;
                        for (int i = 0; i < c.getContainerSize(); i++) full &= !c.getItem(i).isEmpty();
                        if (full) flag("bookshelf_filled");
                    }
                }
                case "armor_stand" -> {
                    if (pd.entity() instanceof LivingEntity stand) {
                        boolean full = true;
                        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                            full &= !stand.getItemBySlot(s).isEmpty();
                        }
                        if (full) flag("armor_stand_filled");
                    }
                }
                case "brush" -> {
                    String b = id(level.getBlockState(pd.pos()));
                    if (b.equals("minecraft:sand") || b.equals("minecraft:gravel")) {
                        flag("brushed");
                        it.remove();
                    }
                }
                default -> it.remove();
            }
        }
    }
}
