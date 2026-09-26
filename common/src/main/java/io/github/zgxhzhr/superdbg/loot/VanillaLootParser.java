package io.github.zgxhzhr.superdbg.loot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zgxhzhr.superdbg.Constants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 把原版战利品表解析为可编辑掉落行的种子数据 {@link VanillaLootSnapshot}。
 * <p>
 * <b>为什么读 JSON 而不是运行时 LootTable 对象：</b>
 * 羊的生羊排不在 entities/sheep 表里，而是通过一个 {@code minecraft:loot_table}
 * 类型条目引用 entities/sheep/sheared；运行时该引用是个 lambda，拿不到被引用的表 id，
 * 对象反射/Mixin 都无法展开。直接读服务端 ResourceManager 里的战利品表 JSON
 * （路径 {@code loot_tables/<id>.json}）才能递归把嵌套表的物品摊平。
 * <p>
 * 概率折算（尽量还原"每次死亡的实际掉落率"）：
 * <ol>
 *   <li>池内顶层条目按 weight（缺省 1）抽一个；</li>
 *   <li>{@code random_chance} 概率直接乘入；{@code random_chance_with_looting}
 *       拆成基础概率 + 每级抢夺追加（与编辑行概率%/抢+% 同构）；</li>
 *   <li>alternatives/group/sequence 的 children、loot_table 引用表递归展开；</li>
 *   <li>tag/dynamic/empty 等无法对应具体物品的条目：权重计入总权重但不产生行；</li>
 *   <li>killed_by_player、sheared_or_was_charged 等无法静态求值的条件只置 conditional。</li>
 * </ol>
 * 数据包覆盖的 JSON 会被 ResourceManager 优先返回；代码动态注册、无 JSON 的表返回空。
 */
public final class VanillaLootParser {

    private VanillaLootParser() {
    }

    private static VanillaLootSnapshot empty() {
        return new VanillaLootSnapshot(List.of());
    }

    public static VanillaLootSnapshot parse(LivingEntity target) {
        if (target == null || target.getServer() == null) {
            return empty();
        }
        ResourceLocation tableId;
        try {
            tableId = target.getLootTable();
        } catch (Throwable t) {
            return empty();
        }
        if (tableId == null) {
            return empty();
        }
        MinecraftServer server = target.getServer();
        List<ItemRaw> raws = new ArrayList<>();
        Set<ResourceLocation> visited = new HashSet<>();
        try {
            parseTable(server, tableId, 1.0f, false, visited, raws);
        } catch (Throwable t) {
            Constants.LOG.warn("[SuperDbg] 解析原版战利品表失败: entity={}, table={}, err={}",
                    target, tableId, t.toString());
            return empty();
        }
        // JSON 递归过程中池边界会丢；为了界面仍能分组展示，所有物品归入一个池视图。
        // 概率已经折算成"每次死亡"的总概率，池投掷次数对阅读没有额外信息。
        List<VanillaLootSnapshot.ItemView> items = new ArrayList<>();
        for (ItemRaw raw : raws) {
            if (raw.item == Items.AIR) {
                continue;
            }
            items.add(new VanillaLootSnapshot.ItemView(
                    BuiltInRegistries.ITEM.getKey(raw.item),
                    raw.minCount, raw.maxCount,
                    round1(raw.chance * 100.0f),
                    round1(raw.lootingBonus * 100.0f),
                    raw.conditional));
        }
        if (items.isEmpty()) {
            return empty();
        }
        return new VanillaLootSnapshot(List.of(
                new VanillaLootSnapshot.PoolView(1, 1, items)));
    }

    // ==================== JSON 递归 ====================

    /** 读一张表的所有池；reach 是到达本表时的上游选中概率（顶层为 1） */
    private static void parseTable(MinecraftServer server, ResourceLocation tableId,
                                   float reach, boolean inheritedConditional,
                                   Set<ResourceLocation> visited, List<ItemRaw> out) {
        if (!visited.add(tableId)) {
            return; // 防止数据包写出循环引用
        }
        JsonObject root = readLootJson(server, tableId);
        if (root == null || !root.has("pools")) {
            return;
        }
        JsonArray pools = root.getAsJsonArray("pools");
        for (JsonElement poolEl : pools) {
            if (!poolEl.isJsonObject()) {
                continue;
            }
            JsonObject pool = poolEl.getAsJsonObject();
            boolean poolConditional = inheritedConditional || hasNonChanceConditions(pool);
            JsonArray entries = pool.getAsJsonArray("entries");
            if (entries == null) {
                continue;
            }
            int totalWeight = 0;
            for (JsonElement e : entries) {
                if (e.isJsonObject()) {
                    totalWeight += Math.max(0, weightOf(e.getAsJsonObject()));
                }
            }
            if (totalWeight <= 0) {
                continue;
            }
            for (JsonElement e : entries) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject entry = e.getAsJsonObject();
                float entryReach = reach * Math.max(0, weightOf(entry)) / totalWeight;
                walkEntry(server, entry, entryReach, poolConditional, visited, out);
            }
        }
    }

    /** 递归展开一个条目 */
    private static void walkEntry(MinecraftServer server, JsonObject entry, float reach,
                                  boolean inheritedConditional, Set<ResourceLocation> visited,
                                  List<ItemRaw> out) {
        CondFold fold = foldConditions(entry);
        boolean conditional = inheritedConditional || fold.otherCondition;
        float baseReach = reach * fold.fixedFactor;
        String type = optString(entry, "type", "minecraft:item");

        switch (type) {
            case "minecraft:item" -> {
                ResourceLocation itemId = ResourceLocation.tryParse(optString(entry, "name", ""));
                Item item = itemId == null ? Items.AIR : BuiltInRegistries.ITEM.get(itemId);
                ItemRaw raw = new ItemRaw();
                raw.item = item;
                raw.chance = baseReach * fold.lootingBase;
                raw.lootingBonus = baseReach * fold.lootingPerLevel;
                raw.conditional = conditional;
                applyCount(entry, raw);
                out.add(raw);
            }
            case "minecraft:alternatives", "minecraft:group", "minecraft:sequence" -> {
                JsonArray children = entry.getAsJsonArray("children");
                if (children != null) {
                    for (JsonElement child : children) {
                        if (child.isJsonObject()) {
                            walkEntry(server, child.getAsJsonObject(), baseReach,
                                    conditional, visited, out);
                        }
                    }
                }
            }
            case "minecraft:loot_table" -> {
                // 1.20.1 字段名为 name；引用另一张表，整张表执行一次
                ResourceLocation refId = ResourceLocation.tryParse(optString(entry, "name", ""));
                if (refId != null) {
                    parseTable(server, refId, baseReach * fold.lootingBase,
                            conditional, visited, out);
                }
            }
            default -> {
                // tag / dynamic / empty：无法对应具体物品，权重已计入总分母
            }
        }
    }

    // ==================== 条件 / 数量 ====================

    /** 池级条件：random_chance 之外的任何条件都视为不可静态折算 */
    private static boolean hasNonChanceConditions(JsonObject pool) {
        JsonArray conditions = pool.getAsJsonArray("conditions");
        if (conditions == null) {
            return false;
        }
        for (JsonElement c : conditions) {
            if (c.isJsonObject() && !isChanceCondition(c.getAsJsonObject())) {
                return true;
            }
        }
        return false;
    }

    private static CondFold foldConditions(JsonObject entry) {
        CondFold fold = new CondFold();
        JsonArray conditions = entry.getAsJsonArray("conditions");
        if (conditions == null) {
            return fold;
        }
        for (JsonElement c : conditions) {
            if (!c.isJsonObject()) {
                continue;
            }
            JsonObject cond = c.getAsJsonObject();
            String type = optString(cond, "condition", "");
            switch (type) {
                case "minecraft:random_chance" ->
                        fold.fixedFactor *= optFloat(cond, "chance", 1.0f);
                case "minecraft:random_chance_with_looting" -> {
                    fold.lootingBase *= optFloat(cond, "chance", 1.0f);
                    fold.lootingPerLevel += optFloat(cond, "looting_multiplier", 0.0f);
                }
                default -> fold.otherCondition = true;
            }
        }
        return fold;
    }

    private static boolean isChanceCondition(JsonObject cond) {
        String type = optString(cond, "condition", "");
        return type.equals("minecraft:random_chance")
                || type.equals("minecraft:random_chance_with_looting");
    }

    /** 从 functions 里找 set_count 写数量范围；找不到默认 1 */
    private static void applyCount(JsonObject itemEntry, ItemRaw raw) {
        JsonArray functions = itemEntry.getAsJsonArray("functions");
        if (functions == null) {
            return;
        }
        for (JsonElement f : functions) {
            if (!f.isJsonObject()) {
                continue;
            }
            JsonObject fn = f.getAsJsonObject();
            if (!"minecraft:set_count".equals(optString(fn, "function", "")) || !fn.has("count")) {
                continue;
            }
            JsonElement count = fn.get("count");
            if (count.isJsonPrimitive()) {
                int v = (int) Math.floor(count.getAsFloat());
                raw.minCount = v;
                raw.maxCount = v;
            } else if (count.isJsonObject()) {
                JsonObject range = count.getAsJsonObject();
                // 数量函数的 min/max 通常是字面量数字
                raw.minCount = (int) Math.floor(optFloat(range, "min", 1.0f));
                raw.maxCount = (int) Math.ceil(optFloat(range, "max", 1.0f));
            }
            return;
        }
    }

    // ==================== IO / JSON 工具 ====================

    /** 从服务端资源管理器读 loot_tables/&lt;id&gt;.json（数据包覆盖优先） */
    private static JsonObject readLootJson(MinecraftServer server, ResourceLocation tableId) {
        ResourceLocation location = new ResourceLocation(
                tableId.getNamespace(), "loot_tables/" + tableId.getPath() + ".json");
        try {
            Optional<net.minecraft.server.packs.resources.Resource> resource =
                    server.getResourceManager().getResource(location);
            if (resource.isEmpty()) {
                return null;
            }
            try (Reader reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
            }
        } catch (Exception e) {
            Constants.LOG.warn("[SuperDbg] 读取战利品表 JSON 失败: {}", location, e);
            return null;
        }
    }

    private static int weightOf(JsonObject entry) {
        JsonElement w = entry.get("weight");
        return w != null && w.isJsonPrimitive() ? Math.max(0, w.getAsInt()) : 1;
    }

    private static String optString(JsonObject obj, String key, String def) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : def;
    }

    private static float optFloat(JsonObject obj, String key, float def) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsFloat() : def;
    }

    private static float round1(float v) {
        return Math.round(v * 10.0f) / 10.0f;
    }

    // ==================== 中间结构 ====================

    private static final class ItemRaw {
        Item item = Items.AIR;
        int minCount = 1;
        int maxCount = 1;
        /** 综合后的每次死亡掉落概率（0-1） */
        float chance = 1.0f;
        /** 每级抢夺追加的概率（0-1） */
        float lootingBonus = 0.0f;
        boolean conditional;
    }

    private static final class CondFold {
        float fixedFactor = 1.0f;
        float lootingBase = 1.0f;
        float lootingPerLevel = 0.0f;
        boolean otherCondition = false;
    }
}
