package io.github.zgxhzhr.superdbg.loot;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LootConfig} 的 NBT / 网络序列化与钳制规则测试。
 */
class LootConfigTest {

    private static final ResourceLocation IRON = new ResourceLocation("minecraft", "iron_ingot");
    private static final ResourceLocation DIAMOND = new ResourceLocation("minecraft", "diamond");

    private static LootConfig sampleConfig() {
        return new LootConfig(LootConfig.Mode.APPEND, List.of(
                new LootConfig.LootEntry(IRON, 1, 3, 50.0F, 10.0F, null),
                new LootConfig.LootEntry(DIAMOND, 0, 1, 5.5F, 0.0F, null)
        ));
    }

    // ==================== Mode ====================

    @Test
    void modeByName_unknownFallsBackToReplace() {
        assertEquals(LootConfig.Mode.APPEND, LootConfig.Mode.byName("APPEND"));
        assertEquals(LootConfig.Mode.REPLACE, LootConfig.Mode.byName("REPLACE"));
        assertEquals(LootConfig.Mode.REPLACE, LootConfig.Mode.byName("no_such_mode"));
        assertEquals(LootConfig.Mode.REPLACE, LootConfig.Mode.byName(""));
    }

    // ==================== NBT ====================

    @Test
    void nbtRoundTrip_preservesData() {
        LootConfig original = sampleConfig();
        LootConfig restored = LootConfig.fromNbt(original.toNbt());

        assertEquals(original.mode(), restored.mode());
        assertEquals(original.entries().size(), restored.entries().size());
        for (int i = 0; i < original.entries().size(); i++) {
            LootConfig.LootEntry a = original.entries().get(i);
            LootConfig.LootEntry b = restored.entries().get(i);
            assertEquals(a.item(), b.item());
            assertEquals(a.minCount(), b.minCount());
            assertEquals(a.maxCount(), b.maxCount());
            assertEquals(a.chance(), b.chance(), 1e-6);
            assertEquals(a.lootingChanceBonus(), b.lootingChanceBonus(), 1e-6);
        }
    }

    @Test
    void fromNbt_skipsInvalidItemId() {
        CompoundTag tag = sampleConfig().toNbt();
        var list = tag.getList("Entries", net.minecraft.nbt.Tag.TAG_COMPOUND);
        list.getCompound(0).putString("Id", "!!!非法注册名!!!");
        // 两条中一条非法 → 只剩钻石那条
        LootConfig restored = LootConfig.fromNbt(tag);
        assertEquals(1, restored.entries().size());
        assertEquals(DIAMOND, restored.entries().get(0).item());
    }

    @Test
    void fromNbt_normalizesInvertedRange() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Mode", "REPLACE");
        var list = new net.minecraft.nbt.ListTag();
        CompoundTag entry = new CompoundTag();
        entry.putString("Id", IRON.toString());
        entry.putInt("Min", 9);
        entry.putInt("Max", 2);
        entry.putFloat("Chance", 100.0F);
        entry.putFloat("LootChance", 0.0F);
        list.add(entry);
        tag.put("Entries", list);

        LootConfig restored = LootConfig.fromNbt(tag);
        assertEquals(2, restored.entries().get(0).minCount());
        assertEquals(9, restored.entries().get(0).maxCount());
    }

    @Test
    void fromNbt_clampsChance() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Mode", "REPLACE");
        var list = new net.minecraft.nbt.ListTag();
        CompoundTag entry = new CompoundTag();
        entry.putString("Id", IRON.toString());
        entry.putInt("Min", 1);
        entry.putInt("Max", 1);
        entry.putFloat("Chance", 250.0F);
        entry.putFloat("LootChance", -5.0F);
        list.add(entry);
        tag.put("Entries", list);

        LootConfig restored = LootConfig.fromNbt(tag);
        assertEquals(100.0F, restored.entries().get(0).chance(), 1e-6);
        assertEquals(0.0F, restored.entries().get(0).lootingChanceBonus(), 1e-6);
    }

    @Test
    void nbtRoundTrip_preservesEntryTag() {
        CompoundTag entryTag = new CompoundTag();
        entryTag.putString("Mark", "enchanted");
        LootConfig config = new LootConfig(LootConfig.Mode.REPLACE, List.of(
                new LootConfig.LootEntry(DIAMOND, 1, 2, 100.0F, 0.0F, entryTag)));

        LootConfig restored = LootConfig.fromNbt(config.toNbt());

        assertEquals(1, restored.entries().size());
        assertEquals("enchanted", restored.entries().get(0).tag().getString("Mark"));

        // 修改恢复出的 tag 不应反向污染原配置（深拷贝隔离）
        restored.entries().get(0).tag().putString("Mark", "tampered");
        assertEquals("enchanted", config.entries().get(0).tag().getString("Mark"));
    }

    // ==================== 网络 ====================

    @Test
    void networkRoundTrip_preservesEntryTag() {
        CompoundTag entryTag = new CompoundTag();
        entryTag.putInt("CustomModelData", 9);
        LootConfig config = new LootConfig(LootConfig.Mode.APPEND, List.of(
                new LootConfig.LootEntry(IRON, 1, 3, 50.0F, 0.0F, entryTag),
                new LootConfig.LootEntry(DIAMOND, 1, 1, 10.0F, 0.0F, null)));

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        LootConfig.write(buf, config);
        LootConfig restored = LootConfig.read(buf);

        assertEquals(9, restored.entries().get(0).tag().getInt("CustomModelData"));
        assertNull(restored.entries().get(1).tag());
        buf.release();
    }

    @Test
    void networkRoundTrip_preservesData() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        LootConfig.write(buf, sampleConfig());
        LootConfig restored = LootConfig.read(buf);
        assertEquals(sampleConfig(), restored);
        buf.release();
    }

    @Test
    void networkWrite_nullBecomesNull() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        LootConfig.write(buf, null);
        assertNull(LootConfig.read(buf));
        buf.release();
    }

    @Test
    void networkRead_clampsAndNormalizes() {
        // 直接手搓 buf 数据：越界数量、越界概率、倒置区间应在读入时被钳制/规整
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBoolean(true);
        buf.writeEnum(LootConfig.Mode.REPLACE);
        buf.writeVarInt(1);
        buf.writeResourceLocation(IRON);
        buf.writeVarInt(5000);   // min 超上限
        buf.writeVarInt(3);      // max 正常但 < min → 规整后交换
        buf.writeFloat(999.0F);  // 概率超上限
        buf.writeFloat(-1.0F);   // 负概率
        buf.writeNbt(null);      // 无物品 NBT
        LootConfig restored = LootConfig.read(buf);
        assertEquals(3, restored.entries().get(0).minCount());
        assertEquals(999, restored.entries().get(0).maxCount());
        assertEquals(100.0F, restored.entries().get(0).chance(), 1e-6);
        assertEquals(0.0F, restored.entries().get(0).lootingChanceBonus(), 1e-6);
        assertNull(restored.entries().get(0).tag());
        buf.release();
    }

    @Test
    void networkRead_truncatesBeyondMaxEntries() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBoolean(true);
        buf.writeEnum(LootConfig.Mode.REPLACE);
        int declared = LootMath.MAX_ENTRIES + 10;
        buf.writeVarInt(declared);
        for (int i = 0; i < declared; i++) {
            buf.writeResourceLocation(IRON);
            buf.writeVarInt(1);
            buf.writeVarInt(1);
            buf.writeFloat(100.0F);
            buf.writeFloat(0.0F);
            buf.writeNbt(null);
        }
        LootConfig restored = LootConfig.read(buf);
        assertEquals(LootMath.MAX_ENTRIES, restored.entries().size());
        // 声明的全部条目都被消费（buf 无残留），避免后续字段错位
        assertEquals(0, buf.readableBytes());
        buf.release();
    }

    @Test
    void entriesAreDefensiveCopies() {
        // record 的 List 直接暴露，写回 NBT 不应受调用方后续修改影响
        LootConfig config = sampleConfig();
        CompoundTag tag = config.toNbt();
        assertTrue(tag.getList("Entries", net.minecraft.nbt.Tag.TAG_COMPOUND).size() > 0);
    }
}
