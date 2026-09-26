package io.github.zgxhzhr.superdbg.gift;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GiftPoolConfig} 的加权定位、区间/权重钳制与 NBT/网络序列化测试。
 * <p>
 * {@code roll(RandomSource)} 依赖物品注册表，留待 GameTest 覆盖。
 */
class GiftPoolConfigTest {

    private static final ResourceLocation DIAMOND = new ResourceLocation("minecraft", "diamond");
    private static final ResourceLocation IRON = new ResourceLocation("minecraft", "iron_ingot");
    private static final ResourceLocation GOLD = new ResourceLocation("minecraft", "gold_ingot");

    // ==================== normalizeRange ====================

    @Test
    void normalizeRange_clampsToAtLeastOne() {
        int[] r = GiftPoolConfig.normalizeRange(0, 0);
        assertEquals(1, r[0]);
        assertEquals(1, r[1]);
    }

    @Test
    void normalizeRange_negativeValuesBecomeOne() {
        int[] r = GiftPoolConfig.normalizeRange(-5, -10);
        assertEquals(1, r[0]);
        assertEquals(1, r[1]);
    }

    @Test
    void normalizeRange_clampsToMaxCount() {
        int[] r = GiftPoolConfig.normalizeRange(100, 999);
        assertEquals(GiftPoolConfig.MAX_COUNT, r[0]);
        assertEquals(GiftPoolConfig.MAX_COUNT, r[1]);
    }

    @Test
    void normalizeRange_swapsWhenMinGreaterThanMax() {
        int[] r = GiftPoolConfig.normalizeRange(10, 3);
        assertEquals(3, r[0]);
        assertEquals(10, r[1]);
    }

    @Test
    void normalizeRange_keepsValidRange() {
        int[] r = GiftPoolConfig.normalizeRange(2, 8);
        assertEquals(2, r[0]);
        assertEquals(8, r[1]);
    }

    // ==================== clampWeight ====================

    @Test
    void clampWeight_zeroAndNegativeBecomeOne() {
        assertEquals(1, GiftPoolConfig.clampWeight(0));
        assertEquals(1, GiftPoolConfig.clampWeight(-100));
    }

    @Test
    void clampWeight_clampsToMaxWeight() {
        assertEquals(GiftPoolConfig.MAX_WEIGHT,
                GiftPoolConfig.clampWeight(GiftPoolConfig.MAX_WEIGHT + 5000));
    }

    @Test
    void clampWeight_keepsValidWeight() {
        assertEquals(42, GiftPoolConfig.clampWeight(42));
        assertEquals(GiftPoolConfig.MAX_WEIGHT, GiftPoolConfig.clampWeight(GiftPoolConfig.MAX_WEIGHT));
    }

    // ==================== pickIndex ====================

    @Test
    void pickIndex_singleEntryAlwaysZero() {
        int[] weights = {5};
        for (int roll = -10; roll <= 10; roll++) {
            assertEquals(0, GiftPoolConfig.pickIndex(weights, 5, roll));
        }
    }

    @Test
    void pickIndex_hitsWeightBucketBoundaries() {
        int[] weights = {1, 2, 3};
        int total = 6;
        // 桶：[0,1) → 0；[1,3) → 1；[3,6) → 2
        assertEquals(0, GiftPoolConfig.pickIndex(weights, total, 0));
        assertEquals(1, GiftPoolConfig.pickIndex(weights, total, 1));
        assertEquals(1, GiftPoolConfig.pickIndex(weights, total, 2));
        assertEquals(2, GiftPoolConfig.pickIndex(weights, total, 3));
        assertEquals(2, GiftPoolConfig.pickIndex(weights, total, 5));
        // total-1 必须落在最后一个桶
        assertEquals(2, GiftPoolConfig.pickIndex(weights, total, total - 1));
    }

    @Test
    void pickIndex_negativeRollWrappedByFloorMod() {
        int[] weights = {1, 1};
        // floorMod(-1, 2) = 1 → 第二个桶
        assertEquals(1, GiftPoolConfig.pickIndex(weights, 2, -1));
        // floorMod(-2, 2) = 0 → 第一个桶
        assertEquals(0, GiftPoolConfig.pickIndex(weights, 2, -2));
        // floorMod(-3, 6) = 3（上面的 1/2/3 权重表）→ 第三个桶
        int[] three = {1, 2, 3};
        assertEquals(2, GiftPoolConfig.pickIndex(three, 6, -3));
    }

    @Test
    void pickIndex_rollAtOrAboveTotalWrapsAround() {
        int[] weights = {2, 3};
        assertEquals(0, GiftPoolConfig.pickIndex(weights, 5, 5));   // 5 % 5 = 0
        assertEquals(1, GiftPoolConfig.pickIndex(weights, 5, 9));   // 9 % 5 = 4
    }

    @Test
    void pickIndex_invalidArgsReturnZero() {
        assertEquals(0, GiftPoolConfig.pickIndex(null, 10, 0));
        assertEquals(0, GiftPoolConfig.pickIndex(new int[0], 10, 0));
        assertEquals(0, GiftPoolConfig.pickIndex(new int[]{1}, 0, 0));
        assertEquals(0, GiftPoolConfig.pickIndex(new int[]{1}, -1, 0));
    }

    // ==================== 紧凑构造器 ====================

    @Test
    void constructor_nullEntriesBecomesEmptyImmutable() {
        GiftPoolConfig config = new GiftPoolConfig(null);
        assertTrue(config.entries().isEmpty());
        assertThrows(UnsupportedOperationException.class, () ->
                config.entries().add(new GiftPoolConfig.GiftEntry(DIAMOND, 1, 1, 1, null)));
    }

    @Test
    void constructor_defensiveCopiesEntryList() {
        GiftPoolConfig.GiftEntry entry =
                new GiftPoolConfig.GiftEntry(DIAMOND, 1, 2, 3, null);
        List<GiftPoolConfig.GiftEntry> mutable = new java.util.ArrayList<>(List.of(entry));
        GiftPoolConfig config = new GiftPoolConfig(mutable);
        mutable.clear();
        assertEquals(1, config.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> config.entries().clear());
    }

    // ==================== NBT ====================

    @Test
    void nbtRoundTrip_preservesEntriesAndTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Mark", "enchanted");
        GiftPoolConfig original = new GiftPoolConfig(List.of(
                new GiftPoolConfig.GiftEntry(DIAMOND, 2, 5, 10, tag),
                new GiftPoolConfig.GiftEntry(IRON, 1, 1, 1, null)));

        GiftPoolConfig restored = GiftPoolConfig.fromNbt(original.toNbt());

        assertEquals(2, restored.entries().size());
        GiftPoolConfig.GiftEntry first = restored.entries().get(0);
        assertEquals(DIAMOND, first.item());
        assertEquals(2, first.minCount());
        assertEquals(5, first.maxCount());
        assertEquals(10, first.weight());
        assertEquals("enchanted", first.tag().getString("Mark"));
        GiftPoolConfig.GiftEntry second = restored.entries().get(1);
        assertEquals(IRON, second.item());
        assertNull(second.tag());
    }

    @Test
    void nbtRoundTrip_emptyListStaysEmpty() {
        GiftPoolConfig restored = GiftPoolConfig.fromNbt(new GiftPoolConfig(List.of()).toNbt());
        assertTrue(restored.entries().isEmpty());
    }

    @Test
    void fromNbt_sanitizesClampsAndSkipsInvalidIds() {
        CompoundTag root = new CompoundTag();
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();

        CompoundTag bad = new CompoundTag();
        bad.putString("Id", "not:a:valid:rl");
        bad.putInt("Min", 1);
        bad.putInt("Max", 1);
        bad.putInt("Weight", 1);
        list.add(bad);

        CompoundTag clamped = new CompoundTag();
        clamped.putString("Id", GOLD.toString());
        clamped.putInt("Min", 9);
        clamped.putInt("Max", 2);
        clamped.putInt("Weight", -5);
        list.add(clamped);

        root.put("Entries", list);

        GiftPoolConfig restored = GiftPoolConfig.fromNbt(root);
        assertEquals(1, restored.entries().size());
        GiftPoolConfig.GiftEntry e = restored.entries().get(0);
        assertEquals(GOLD, e.item());
        assertEquals(2, e.minCount());
        assertEquals(9, e.maxCount());
        assertEquals(1, e.weight());
    }

    // ==================== 网络 ====================

    @Test
    void network_nullConfigRoundTripsToNull() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        GiftPoolConfig.write(buf, null);
        assertNull(GiftPoolConfig.read(buf));
    }

    @Test
    void network_roundTripsEntriesAndTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("CustomModelData", 7);
        GiftPoolConfig original = new GiftPoolConfig(List.of(
                new GiftPoolConfig.GiftEntry(DIAMOND, 1, 64, 1000, tag),
                new GiftPoolConfig.GiftEntry(IRON, 3, 3, 1, null)));

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        GiftPoolConfig.write(buf, original);
        GiftPoolConfig restored = GiftPoolConfig.read(buf);

        assertEquals(2, restored.entries().size());
        GiftPoolConfig.GiftEntry first = restored.entries().get(0);
        assertEquals(DIAMOND, first.item());
        assertEquals(1, first.minCount());
        assertEquals(64, first.maxCount());
        assertEquals(1000, first.weight());
        assertEquals(7, first.tag().getInt("CustomModelData"));
        GiftPoolConfig.GiftEntry second = restored.entries().get(1);
        assertEquals(IRON, second.item());
        assertEquals(3, second.minCount());
        assertNull(second.tag());
    }
}
