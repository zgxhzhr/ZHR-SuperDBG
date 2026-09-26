package io.github.zgxhzhr.superdbg.loot;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * 全体类型掉落覆盖的存档级存储（{@code data/superdbg_loot_overrides.dat}）。
 * <p>
 * 键为实体类型注册名（如 {@code minecraft:cow}），值为该类型的掉落覆盖配置。
 * 存在即启用；停用 = 从 Map 移除并 {@link #setDirty()}。
 */
public class LootOverrideData extends SavedData {

    private static final String DATA_NAME = "superdbg_loot_overrides";

    private final Map<ResourceLocation, LootConfig> byType = new HashMap<>();

    public static LootOverrideData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(LootOverrideData::load, LootOverrideData::new, DATA_NAME);
    }

    public LootConfig get(ResourceLocation entityTypeId) {
        return byType.get(entityTypeId);
    }

    public void set(ResourceLocation entityTypeId, LootConfig config) {
        byType.put(entityTypeId, config);
        setDirty();
    }

    public void remove(ResourceLocation entityTypeId) {
        if (byType.remove(entityTypeId) != null) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        for (Map.Entry<ResourceLocation, LootConfig> e : byType.entrySet()) {
            tag.put(e.getKey().toString(), e.getValue().toNbt());
        }
        return tag;
    }

    private static LootOverrideData load(CompoundTag tag) {
        LootOverrideData data = new LootOverrideData();
        for (String key : tag.getAllKeys()) {
            ResourceLocation typeId = ResourceLocation.tryParse(key);
            if (typeId == null) {
                continue;
            }
            data.byType.put(typeId, LootConfig.fromNbt(tag.getCompound(key)));
        }
        return data;
    }
}
