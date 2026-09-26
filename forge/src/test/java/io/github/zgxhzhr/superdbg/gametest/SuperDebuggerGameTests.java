package io.github.zgxhzhr.superdbg.gametest;

import io.github.zgxhzhr.superdbg.Constants;
import io.github.zgxhzhr.superdbg.entity.EntityEditorData;
import io.github.zgxhzhr.superdbg.entity.EntityEditorService;
import io.github.zgxhzhr.superdbg.item.ItemEditorData;
import io.github.zgxhzhr.superdbg.item.ItemEditorService;
import io.github.zgxhzhr.superdbg.item.EditableAttribute;
import io.github.zgxhzhr.superdbg.network.OpenEntityEditorPacket;
import io.github.zgxhzhr.superdbg.network.SubmitEntityEditorPacket;
import io.github.zgxhzhr.superdbg.network.SubmitItemEditorPacket;
import io.github.zgxhzhr.superdbg.network.UpdatePotionEffectPacket;
import io.github.zgxhzhr.superdbg.potion.PotionEffectData;
import io.github.zgxhzhr.superdbg.potion.PotionEditorProvider;
import io.github.zgxhzhr.superdbg.potion.PotionEditors;
import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.gametest.GameTestHolder;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 超级调试器模组的 GameTest 单元测试集合。
 * <p>
 * 覆盖核心逻辑边界值：amplifier 序列化往返、旧 byte 格式兼容、
 * 药水编辑器原子写回、网络包编解码。
 */
@GameTestHolder(Constants.MOD_ID)
public class SuperDebuggerGameTests {

    /**
     * PotionEffectData.validate 的边界值校验。
     */
    @GameTest(template = "empty")
    public void amplifierValidationBoundaries(GameTestHelper helper) {
        // 合法值不抛异常（含 0、255、256、32767、Integer.MAX_VALUE）
        new PotionEffectData(MobEffects.MOVEMENT_SPEED, 0, 100, false, true, true).validate();
        new PotionEffectData(MobEffects.MOVEMENT_SPEED, 255, 100, false, true, true).validate();
        new PotionEffectData(MobEffects.MOVEMENT_SPEED, 256, 100, false, true, true).validate();
        new PotionEffectData(MobEffects.MOVEMENT_SPEED, 32767, 100, false, true, true).validate();
        new PotionEffectData(MobEffects.MOVEMENT_SPEED, PotionEffectData.MAX_AMPLIFIER, 100, false, true, true).validate();
        new PotionEffectData(MobEffects.MOVEMENT_SPEED, 0, -1, false, true, true).validate();

        // amplifier 越界必须抛异常（int 上限本身已是 MAX_AMPLIFIER，只能测负值越界）
        boolean threwNeg = false;
        try {
            new PotionEffectData(MobEffects.MOVEMENT_SPEED, -1, 100, false, true, true).validate();
        } catch (IllegalArgumentException e) {
            threwNeg = true;
        }
        helper.assertTrue(threwNeg, "amplifier -1 应抛出 IllegalArgumentException");

        // duration 越界（非 -1 的负值）必须抛异常
        boolean threwDur = false;
        try {
            new PotionEffectData(MobEffects.MOVEMENT_SPEED, 0, -2, false, true, true).validate();
        } catch (IllegalArgumentException e) {
            threwDur = true;
        }
        helper.assertTrue(threwDur, "duration -2 应抛出 IllegalArgumentException");

        helper.succeed();
    }

    /**
     * MobEffectInstance 的 amplifier 经 NBT 写入后再读出，
     * 0-{@link PotionEffectData#MAX_AMPLIFIER} 必须保持一致。
     * 验证 Mixin 将 Amplifier 从 ByteTag 升级为 IntTag，并放开两端 byte 瓶颈。
     */
    @GameTest(template = "empty")
    public void amplifierNbtRoundTrip(GameTestHelper helper) {
        int[] values = {0, 1, 127, 128, 255, 256, 32767, 65535, 100000, Integer.MAX_VALUE};
        for (int amp : values) {
            MobEffectInstance inst = new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 100, amp);
            CompoundTag tag = inst.save(new CompoundTag());

            helper.assertTrue(tag.contains("Amplifier", Tag.TAG_INT),
                    "Amplifier 应为 IntTag，实际 amplifier=" + amp);

            MobEffectInstance loaded = MobEffectInstance.load(tag);
            helper.assertTrue(loaded != null && loaded.getAmplifier() == amp,
                    "往返读取 amplifier 不一致：期望 " + amp + "，实际 "
                            + (loaded == null ? "null" : loaded.getAmplifier()));
        }
        helper.succeed();
    }

    /**
     * 旧存档使用 ByteTag 存储 Amplifier（带符号），读取时必须按无符号解释。
     * 例如 byte(-56) 对应无符号 200。
     */
    @GameTest(template = "empty")
    public void oldByteFormatCompatibility(GameTestHelper helper) {
        CompoundTag tag = new CompoundTag();
        tag.putByte("Id", (byte) 1); // Speed (id=1)
        tag.putByte("Amplifier", (byte) 200); // 有符号 byte = -56
        tag.putInt("Duration", 100);

        MobEffectInstance loaded = MobEffectInstance.load(tag);
        helper.assertTrue(loaded != null && loaded.getAmplifier() == 200,
                "旧 ByteTag 格式应按无符号读取：期望 200，实际 "
                        + (loaded == null ? "null" : loaded.getAmplifier()));
        helper.succeed();
    }

    /**
     * 凋零/中毒/生命恢复的单次伤害或恢复量随 amplifier 线性增长（1.0F + amplifier）。
     * <p>
     * 原版三种效果每次应用固定 1.0F，amp 6 与 amp 255 单次量相同，
     * 仅通过频率控制（40>>amp 等）影响效果，amp 6+ 已每 tick 应用，
     * 但伤害仍只有 1/tick，导致高级别效果感知极弱。
     * 本 Mixin 改后单次量 = 1 + amplifier。
     */
    @GameTest(template = "empty")
    public void witherDamageScalesWithAmplifier(GameTestHelper helper) {
        // 僵尸默认 20 HP，避免伤害被最大血量钳制（羊 8 HP 时 amp 10 的 11 伤害会被截到 8）
        Zombie zombie0 = new Zombie(EntityType.ZOMBIE, helper.getLevel());
        zombie0.setPos(0, 0, 0);
        float hp0 = zombie0.getHealth();

        Zombie zombie10 = new Zombie(EntityType.ZOMBIE, helper.getLevel());
        zombie10.setPos(0, 0, 0);
        float hp10 = zombie10.getHealth();

        // 凋零 amp 0 与 amp 10，各应用一次 applyEffectTick
        zombie0.addEffect(new MobEffectInstance(MobEffects.WITHER, 200, 0));
        zombie10.addEffect(new MobEffectInstance(MobEffects.WITHER, 200, 10));

        MobEffect wither = MobEffects.WITHER;
        // 直接调用 applyEffectTick 触发本 Mixin 的接管逻辑
        wither.applyEffectTick(zombie0, 0);
        wither.applyEffectTick(zombie10, 10);

        float dmg0 = hp0 - zombie0.getHealth();
        float dmg10 = hp10 - zombie10.getHealth();

        // amp 0 单次伤害 = 1.0F + 0 = 1.0F
        helper.assertTrue(Math.abs(dmg0 - 1.0F) < 0.01F,
                "amp 0 凋零单次伤害应为 1.0F，实际 " + dmg0);
        // amp 10 单次伤害 = 1.0F + 10 = 11.0F（与原版固定 1.0F 形成实质差异）
        helper.assertTrue(Math.abs(dmg10 - 11.0F) < 0.01F,
                "amp 10 凋零单次伤害应为 11.0F，实际 " + dmg10);
        helper.assertTrue(dmg10 > dmg0,
                "amp 10 伤害应大于 amp 0 伤害");
        helper.succeed();
    }

    /**
     * PotionItemEditor.writeEffect 原子写回：修改 amplifier 与 duration 后再读取，
     * 数值必须一致，且基础药水被置空避免叠加。
     */
    @GameTest(template = "empty")
    public void potionEditorWriteEffect(GameTestHelper helper) {
        ItemStack potion = new ItemStack(Items.POTION);
        PotionUtils.setPotion(potion, Potions.SWIFTNESS);

        PotionEditorProvider editor = PotionEditors.find(potion);
        helper.assertTrue(editor != null, "应为药水找到编辑器");

        // 修改第 0 个效果：amplifier=200，永久
        editor.writeEffect(potion, 0, 200, -1);

        List<PotionEffectData> effects = editor.readEffects(potion);
        helper.assertTrue(effects.size() == 1, "应只有 1 个效果，实际 " + effects.size());
        helper.assertTrue(effects.get(0).amplifier() == 200,
                "amplifier 应为 200，实际 " + effects.get(0).amplifier());
        helper.assertTrue(effects.get(0).isInfinite(),
                "duration 应为永久（-1），实际 " + effects.get(0).duration());

        // 基础药水必须已置空，避免与自定义效果叠加
        helper.assertTrue(PotionUtils.getPotion(potion) == Potions.EMPTY,
                "基础药水应被置为 EMPTY 以避免叠加");
        helper.succeed();
    }

    /**
     * UpdatePotionEffectPacket 的编码/解码往返一致性。
     */
    @GameTest(template = "empty")
    public void packetEncodeDecodeRoundTrip(GameTestHelper helper) {
        UpdatePotionEffectPacket original = new UpdatePotionEffectPacket(3, 200, -1);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.encode(buf);
        UpdatePotionEffectPacket decoded = UpdatePotionEffectPacket.decode(buf);

        helper.assertTrue(decoded.effectIndex() == 3,
                "effectIndex 应为 3，实际 " + decoded.effectIndex());
        helper.assertTrue(decoded.amplifier() == 200,
                "amplifier 应为 200，实际 " + decoded.amplifier());
        helper.assertTrue(decoded.duration() == -1,
                "duration 应为 -1，实际 " + decoded.duration());
        helper.succeed();
    }

    /**
     * 三种药水类型（普通/喷溅/滞留）都应被编辑器识别。
     */
    @GameTest(template = "empty")
    public void allPotionTypesEditable(GameTestHelper helper) {
        helper.assertTrue(PotionEditors.canEdit(new ItemStack(Items.POTION)),
                "普通药水应可编辑");
        helper.assertTrue(PotionEditors.canEdit(new ItemStack(Items.SPLASH_POTION)),
                "喷溅药水应可编辑");
        helper.assertTrue(PotionEditors.canEdit(new ItemStack(Items.LINGERING_POTION)),
                "滞留药水应可编辑");
        helper.assertFalse(PotionEditors.canEdit(new ItemStack(Items.APPLE)),
                "苹果不应可编辑");
        helper.succeed();
    }

    // ==================== 物品编辑器 ====================

    private static final ResourceLocation ATTACK_DAMAGE_ID =
            BuiltInRegistries.ATTRIBUTE.getKey(Attributes.ATTACK_DAMAGE);
    private static final ResourceLocation ATTACK_SPEED_ID =
            BuiltInRegistries.ATTRIBUTE.getKey(Attributes.ATTACK_SPEED);
    private static final ResourceLocation SHARPNESS_ID =
            BuiltInRegistries.ENCHANTMENT.getKey(Enchantments.SHARPNESS);

    private static void assertDouble(GameTestHelper helper, double expected, double actual, String msg) {
        helper.assertTrue(Math.abs(expected - actual) < 1.0E-6D,
                msg + "：期望 " + expected + "，实际 " + actual);
    }

    /**
     * 打开钻石剑不做修改直接提交，默认属性（物品攻击伤害加成 6、攻击速度 -2.4）必须原样保留。
     * 注：玩家手持时总攻击力 = 玩家基础 1 + 物品 6 = 7。
     * 回归：NBT 修饰符会完全替换默认修饰符，写回时必须合并默认值。
     */
    @GameTest(template = "empty")
    public void itemEditorKeepsDefaultAttributes(GameTestHelper helper) {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        ItemEditorData data = ItemEditorService.read(sword);

        assertDouble(helper, 6.0D, data.attributes.get(ATTACK_DAMAGE_ID), "钻石剑默认攻击伤害加成");
        assertDouble(helper, -2.4D, data.attributes.get(ATTACK_SPEED_ID), "钻石剑默认攻击速度");

        // 原样提交后再读
        ItemEditorService.apply(sword, data);
        ItemEditorData reread = ItemEditorService.read(sword);
        assertDouble(helper, 6.0D, reread.attributes.get(ATTACK_DAMAGE_ID), "提交后攻击伤害加成");
        assertDouble(helper, -2.4D, reread.attributes.get(ATTACK_SPEED_ID), "提交后攻击速度");
        helper.succeed();
    }

    /**
     * 修改钻石剑攻击伤害为 20：读回必须是 20，攻击速度默认值保留。
     */
    @GameTest(template = "empty")
    public void itemEditorModifyAttribute(GameTestHelper helper) {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        ItemEditorData data = ItemEditorService.read(sword);
        data.attributes.put(ATTACK_DAMAGE_ID, 20.0D);
        ItemEditorService.apply(sword, data);

        ItemEditorData reread = ItemEditorService.read(sword);
        assertDouble(helper, 20.0D, reread.attributes.get(ATTACK_DAMAGE_ID), "修改后攻击伤害");
        assertDouble(helper, -2.4D, reread.attributes.get(ATTACK_SPEED_ID), "攻击速度应保留默认");
        helper.succeed();
    }

    /**
     * 任意物品可附加任意附魔：皮靴附锋利 11，NBT 必须写入且可被原版读取。
     */
    @GameTest(template = "empty")
    public void itemEditorFreeEnchantment(GameTestHelper helper) {
        ItemStack boots = new ItemStack(Items.LEATHER_BOOTS);
        ItemEditorData data = ItemEditorService.read(boots);
        data.enchantments.put(SHARPNESS_ID, 11);
        ItemEditorService.apply(boots, data);

        int level = EnchantmentHelper.getEnchantments(boots).getOrDefault(Enchantments.SHARPNESS, 0);
        helper.assertTrue(level == 11, "皮靴锋利等级应为 11，实际 " + level);

        // 等级 0 / 留空等价于移除
        ItemEditorData data2 = ItemEditorService.read(boots);
        data2.enchantments.remove(SHARPNESS_ID);
        ItemEditorService.apply(boots, data2);
        helper.assertTrue(EnchantmentHelper.getEnchantments(boots).isEmpty(),
                "移除后不应有附魔");
        helper.succeed();
    }

    /**
     * EnchantmentHelperMixin 已放开原版 putShort 写入与 Mth.clamp 读取双重瓶颈，
     * 编辑器上限对齐到 {@link Integer#MAX_VALUE}：
     * <ul>
     *   <li>32767（原版 short 上限）可正常写入并读回</li>
     *   <li>{@link ItemEditorService#MAX_ENCHANT_LEVEL} 也能写入并读回</li>
     * </ul>
     */
    @GameTest(template = "empty")
    public void enchantLevelBeyondVanillaCap(GameTestHelper helper) {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);

        // 32767（原版 short 上限）可写入并读回
        ItemEditorData data = ItemEditorService.read(sword);
        data.enchantments.put(SHARPNESS_ID, 32767);
        ItemEditorService.apply(sword, data);
        int level32767 = EnchantmentHelper.getEnchantments(sword).getOrDefault(Enchantments.SHARPNESS, 0);
        helper.assertTrue(level32767 == 32767,
                "锋利 32767 应可写入并读回，实际 " + level32767);

        // Integer.MAX_VALUE 也能写入并读回
        ItemEditorData data2 = ItemEditorService.read(sword);
        data2.enchantments.put(SHARPNESS_ID, ItemEditorService.MAX_ENCHANT_LEVEL);
        ItemEditorService.apply(sword, data2);
        int levelMax = EnchantmentHelper.getEnchantments(sword).getOrDefault(Enchantments.SHARPNESS, 0);
        helper.assertTrue(levelMax == ItemEditorService.MAX_ENCHANT_LEVEL,
                "锋利 Integer.MAX_VALUE 应可写入并读回，实际 " + levelMax);
        helper.succeed();
    }

    /**
     * 调试斧：金斧开启调试后名称变为"调试斧"、带标记，且附魔写入被强制拒绝；
     * 关闭后标记移除。
     */
    @GameTest(template = "empty")
    public void itemEditorGoldenAxeDebug(GameTestHelper helper) {
        ItemStack axe = new ItemStack(Items.GOLDEN_AXE);
        ItemEditorData data = ItemEditorService.read(axe);
        data.debug = true;
        data.enchantments.put(SHARPNESS_ID, 5); // 试图在开启调试的同时附魔
        ItemEditorService.apply(axe, data);

        helper.assertTrue(axe.getTag() != null && axe.getTag().getBoolean(ItemEditorService.DEBUG_TAG),
                "应存在调试标记");
        helper.assertTrue(ItemEditorService.DEBUG_NAME.equals(axe.getHoverName().getString()),
                "名称应为调试斧，实际 " + axe.getHoverName().getString());
        helper.assertTrue(EnchantmentHelper.getEnchantments(axe).isEmpty(),
                "调试斧不应能被附加附魔");

        // 关闭调试
        data.debug = false;
        ItemEditorService.apply(axe, data);
        helper.assertTrue(axe.getTag() == null || !axe.getTag().getBoolean(ItemEditorService.DEBUG_TAG),
                "关闭后调试标记应移除");
        helper.assertTrue(!axe.hasCustomHoverName() || !ItemEditorService.DEBUG_NAME.equals(axe.getHoverName().getString()),
                "关闭后不应再叫调试斧");
        helper.succeed();
    }

    /**
     * 非金斧物品的 debug 字段必须被忽略。
     */
    @GameTest(template = "empty")
    public void itemEditorDebugIgnoredForOtherItems(GameTestHelper helper) {
        ItemStack stick = new ItemStack(Items.STICK);
        ItemEditorData data = ItemEditorService.read(stick);
        data.debug = true;
        ItemEditorService.apply(stick, data);
        helper.assertTrue(stick.getTag() == null || !stick.getTag().getBoolean(ItemEditorService.DEBUG_TAG),
                "非金斧不应写入调试标记");
        helper.succeed();
    }

    /**
     * 无法破坏 + 重命名写回验证。
     */
    @GameTest(template = "empty")
    public void itemEditorNameAndUnbreakable(GameTestHelper helper) {
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        ItemEditorData data = ItemEditorService.read(pickaxe);
        data.customName = "测试之镐";
        data.unbreakable = true;
        ItemEditorService.apply(pickaxe, data);

        helper.assertTrue("测试之镐".equals(pickaxe.getHoverName().getString()),
                "名称应为测试之镐，实际 " + pickaxe.getHoverName().getString());
        helper.assertTrue(pickaxe.getTag().getBoolean("Unbreakable"), "应为无法破坏");

        // 清空名称、取消无法破坏
        data.customName = "";
        data.unbreakable = false;
        ItemEditorService.apply(pickaxe, data);
        helper.assertTrue(!pickaxe.hasCustomHoverName(), "名称应被移除");
        helper.assertTrue(!pickaxe.getTag().getBoolean("Unbreakable"), "无法破坏应被移除");
        helper.succeed();
    }

    /**
     * SubmitItemEditorPacket 编解码往返。
     */
    @GameTest(template = "empty")
    public void submitItemPacketRoundTrip(GameTestHelper helper) {
        Map<ResourceLocation, Integer> ench = Map.of(SHARPNESS_ID, 11);
        Map<ResourceLocation, Double> attrs = Map.of(ATTACK_DAMAGE_ID, 20.5D);
        SubmitItemEditorPacket original =
                new SubmitItemEditorPacket("测试", true, false, ench, attrs);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.encode(buf);
        SubmitItemEditorPacket decoded = SubmitItemEditorPacket.decode(buf);

        helper.assertTrue(Objects.equals("测试", decoded.customName()), "名称往返");
        helper.assertTrue(decoded.unbreakable(), "unbreakable 往返");
        helper.assertTrue(!decoded.debug(), "debug 往返");
        helper.assertTrue(decoded.enchantments().get(SHARPNESS_ID) == 11, "附魔往返");
        assertDouble(helper, 20.5D, decoded.attributes().get(ATTACK_DAMAGE_ID), "属性往返");
        helper.succeed();
    }

    // ==================== 实体编辑器 ====================

    private static final ResourceLocation MAX_HEALTH_ID =
            BuiltInRegistries.ATTRIBUTE.getKey(Attributes.MAX_HEALTH);
    private static final ResourceLocation SPEED_ID =
            BuiltInRegistries.ATTRIBUTE.getKey(Attributes.MOVEMENT_SPEED);
    private static final ResourceLocation SPEED_EFFECT_ID =
            BuiltInRegistries.MOB_EFFECT.getKey(MobEffects.MOVEMENT_SPEED);

    /**
     * 属性快照：僵尸应有 max_health 与 movement_speed（原版注册属性），
     * 且修改 baseValue 后读回一致。
     */
    @GameTest(template = "empty")
    public void entityEditorSnapshotRoundTrip(GameTestHelper helper) {
        Zombie zombie = new Zombie(helper.getLevel());

        var attrs = EntityEditorService.readAttributes(zombie);
        boolean hasMaxHealth = attrs.stream().anyMatch(a -> a.id().equals(MAX_HEALTH_ID));
        boolean hasSpeed = attrs.stream().anyMatch(a -> a.id().equals(SPEED_ID));
        helper.assertTrue(hasMaxHealth, "僵尸属性快照应含 max_health");
        helper.assertTrue(hasSpeed, "僵尸属性快照应含 movement_speed");
        // 僵尸没有玩家专属属性之外的缺失：快照不应为空
        helper.assertTrue(!attrs.isEmpty(), "属性快照不应为空");

        // 修改 max_health 基础值为 40 后读回
        zombie.getAttributes().getInstance(Attributes.MAX_HEALTH)
                .setBaseValue(40.0D);
        var reread = EntityEditorService.readAttributes(zombie);
        double base = reread.stream().filter(a -> a.id().equals(MAX_HEALTH_ID))
                .findFirst().orElseThrow().baseValue();
        assertDouble(helper, 40.0D, base, "修改后 max_health 基础值");
        helper.succeed();
    }

    /**
     * 属性写入经 sanitizeValue 钳制：上限已被模组提升到 int 最大值，
     * 超大值钳到 2147483647；负值仍受原版下限保护（最小生命 1）。
     */
    @GameTest(template = "empty")
    public void entityEditorAttributeClamp(GameTestHelper helper) {
        // 模组启动时已把属性上限放宽到 int 最大值
        double maxHealthCap = ((net.minecraft.world.entity.ai.attributes.RangedAttribute)
                Attributes.MAX_HEALTH).getMaxValue();
        helper.assertTrue(maxHealthCap == (double) Integer.MAX_VALUE,
                "max_health 上限应为 int 最大值，实际 " + maxHealthCap);

        Zombie zombie = new Zombie(helper.getLevel());

        // 远超 int 最大值 → 钳到 2147483647
        EntityEditorService.apply(zombie, Map.of(MAX_HEALTH_ID, 1.0E15), List.of(), false);
        double maxHealth = zombie.getAttribute(Attributes.MAX_HEALTH).getBaseValue();
        helper.assertTrue(maxHealth == (double) Integer.MAX_VALUE,
                "超大血量应被钳到 int 最大值，实际 " + maxHealth);

        // 负数 → 钳到属性下限（max_health 为 1）
        EntityEditorService.apply(zombie, Map.of(MAX_HEALTH_ID, -100.0D), List.of(), false);
        double clampedMin = zombie.getAttribute(Attributes.MAX_HEALTH).getBaseValue();
        helper.assertTrue(clampedMin == 1.0D, "负血量应被钳到下限 1，实际 " + clampedMin);

        // int 最大值本身可正常写入（旧上限 1024 已废除）
        EntityEditorService.apply(zombie, Map.of(SPEED_ID, (double) Integer.MAX_VALUE), List.of(), false);
        double speed = zombie.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue();
        helper.assertTrue(speed == (double) Integer.MAX_VALUE,
                "速度应可写到 int 最大值，实际 " + speed);
        helper.succeed();
    }

    /**
     * 修改 max_health 基础值后，当前血量必须被钳到新上限内。
     */
    @GameTest(template = "empty")
    public void entityEditorMaxHealthClampCurrent(GameTestHelper helper) {
        Zombie zombie = new Zombie(helper.getLevel());
        zombie.setHealth(40.0F); // 先把当前血量顶到超出默认上限

        EntityEditorService.apply(zombie, Map.of(MAX_HEALTH_ID, 10.0D), List.of(), false);
        helper.assertTrue(zombie.getHealth() <= 10.0F,
                "当前血量应被钳到新上限 10 内，实际 " + zombie.getHealth());
        helper.succeed();
    }

    /**
     * 当前血量直接编辑：精确写入、超上限钳制、传负数表示不修改。
     */
    @GameTest(template = "empty")
    public void entityEditorEditCurrentHealth(GameTestHelper helper) {
        Zombie zombie = new Zombie(helper.getLevel());

        // 上限 100，写入 50
        EntityEditorService.apply(zombie, Map.of(MAX_HEALTH_ID, 100.0D), List.of(), false, 50.0F, false);
        helper.assertTrue(zombie.getHealth() == 50.0F, "当前血量应精确写入 50，实际 " + zombie.getHealth());

        // 超过上限的值钳到上限
        EntityEditorService.apply(zombie, Map.of(), List.of(), false, 999.0F, false);
        helper.assertTrue(zombie.getHealth() == 100.0F, "当前血量应钳到上限 100，实际 " + zombie.getHealth());

        // 负值表示不修改当前血量
        EntityEditorService.apply(zombie, Map.of(), List.of(), false, -1.0F, false);
        helper.assertTrue(zombie.getHealth() == 100.0F, "负数血量参数不应改变当前血量，实际 " + zombie.getHealth());

        // 写入 0
        EntityEditorService.apply(zombie, Map.of(), List.of(), false, 0.0F, false);
        helper.assertTrue(zombie.getHealth() == 0.0F, "当前血量应可写为 0，实际 " + zombie.getHealth());
        helper.succeed();
    }

    /**
     * 效果全量重建：空列表清空现有效果；写入永久高等级效果读回一致。
     */
    @GameTest(template = "empty")
    public void entityEditorEffectRebuild(GameTestHelper helper) {
        Zombie zombie = new Zombie(helper.getLevel());
        zombie.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200, 1));

        // 空效果列表 → 清空
        EntityEditorService.apply(zombie, Map.of(), List.of(), false);
        helper.assertTrue(zombie.getActiveEffects().isEmpty(), "空列表应清空全部效果");

        // 永久速度 200 级 → 读回一致
        var effects = List.of(new EntityEditorData.EffectEntry(SPEED_EFFECT_ID, 200, -1));
        EntityEditorService.apply(zombie, Map.of(), effects, false);
        helper.assertTrue(zombie.getActiveEffects().size() == 1, "应有 1 个效果");
        MobEffectInstance inst = zombie.getActiveEffects().iterator().next();
        helper.assertTrue(inst.getAmplifier() == 200, "等级应为 200，实际 " + inst.getAmplifier());
        helper.assertTrue(inst.getDuration() == -1, "时长应为永久 -1，实际 " + inst.getDuration());
        helper.succeed();
    }

    /**
     * 移除分支：非玩家实体 discard 后应处于已移除状态。
     */
    @GameTest(template = "empty")
    public void entityEditorRemoveBranch(GameTestHelper helper) {
        Zombie zombie = new Zombie(helper.getLevel());
        EntityEditorService.apply(zombie, Map.of(), List.of(), true);
        helper.assertTrue(zombie.isRemoved(), "移除后实体应处于已移除状态");
        helper.succeed();
    }

    /**
     * 权限矩阵（纯函数）：
     * 自己始终可调；OP 只能被本人调；普通玩家仅 OP 可调；生物任何创造玩家可调。
     */
    @GameTest(template = "empty")
    public void entityEditorPermissionMatrix(GameTestHelper helper) {
        // 目标是自己：无论权限与模式
        helper.assertTrue(EntityEditorService.canEdit(true, 0, true, true, 4), "自己（无权限）可调");
        helper.assertTrue(EntityEditorService.canEdit(false, 0, true, true, 4), "自己（非创造）可调");

        // 目标是 OP（>=2 级）玩家：只有本人能调，其他玩家包括其他 OP 一律拒绝
        helper.assertTrue(!EntityEditorService.canEdit(true, 4, false, true, 4), "其他 OP 不能调 OP");
        helper.assertTrue(!EntityEditorService.canEdit(true, 0, false, true, 2), "普通创造玩家不能调 OP");

        // 目标是普通玩家：仅 OP 编辑者
        helper.assertTrue(EntityEditorService.canEdit(true, 2, false, true, 0), "OP 可调普通玩家");
        helper.assertTrue(!EntityEditorService.canEdit(true, 0, false, true, 0), "非 OP 不能调普通玩家");

        // 目标是非玩家生物：任何创造玩家可调，非创造不可
        helper.assertTrue(EntityEditorService.canEdit(true, 0, false, false, 0), "创造玩家可调生物");
        helper.assertTrue(!EntityEditorService.canEdit(false, 4, false, false, 0), "非创造不能调生物");

        // 目标是非玩家实体时 isSelf 恒 false，targetPerm 无意义传 0
        helper.assertTrue(EntityEditorService.canEdit(true, 4, false, false, 0), "OP 也可调生物");
        helper.succeed();
    }

    /**
     * OpenEntityEditorPacket / SubmitEntityEditorPacket / 快照编解码往返。
     */
    @GameTest(template = "empty")
    public void entityEditorPacketRoundTrip(GameTestHelper helper) {
        // Open 包
        FriendlyByteBuf openBuf = new FriendlyByteBuf(Unpooled.buffer());
        new OpenEntityEditorPacket(42).encode(openBuf);
        helper.assertTrue(OpenEntityEditorPacket.decode(openBuf).entityId() == 42, "Open 包 entityId 往返");

        openBuf.clear();
        new OpenEntityEditorPacket(-1).encode(openBuf);
        helper.assertTrue(OpenEntityEditorPacket.decode(openBuf).entityId() == -1, "Open 包 -1（自己）往返");

        // Submit 包
        Map<ResourceLocation, Double> attrs = Map.of(MAX_HEALTH_ID, 40.0D);
        List<EntityEditorData.EffectEntry> effects =
                List.of(new EntityEditorData.EffectEntry(SPEED_EFFECT_ID, 255, -1));
        List<EntityEditorData.TraitEntry> traits =
                List.of(new EntityEditorData.TraitEntry(
                        new ResourceLocation("l2hostility", "fire"), 255));
        FriendlyByteBuf subBuf = new FriendlyByteBuf(Unpooled.buffer());
        List<EntityEditorData.TradeEntry> trades = List.of(new EntityEditorData.TradeEntry(
                new ResourceLocation("minecraft", "emerald"), 2, null,
                true, new ResourceLocation("minecraft", "stick"), 1, null,
                new ResourceLocation("minecraft", "diamond"), 1, null,
                12, 5));
        CompoundTag lootTag = new CompoundTag();
        lootTag.putString("Mark", "loot");
        io.github.zgxhzhr.superdbg.loot.LootConfig entityLoot = new io.github.zgxhzhr.superdbg.loot.LootConfig(
                io.github.zgxhzhr.superdbg.loot.LootConfig.Mode.APPEND, List.of(
                new io.github.zgxhzhr.superdbg.loot.LootConfig.LootEntry(
                        new ResourceLocation("minecraft", "gold_ingot"), 1, 3, 50.0F, 10.0F, lootTag)));
        CompoundTag giftTag = new CompoundTag();
        giftTag.putString("Mark", "gift");
        io.github.zgxhzhr.superdbg.gift.GiftPoolConfig giftPool =
                new io.github.zgxhzhr.superdbg.gift.GiftPoolConfig(List.of(
                        new io.github.zgxhzhr.superdbg.gift.GiftPoolConfig.GiftEntry(
                                new ResourceLocation("minecraft", "diamond"), 1, 3, 5, giftTag)));
        new SubmitEntityEditorPacket(7, attrs, effects, traits, 12, 25.0F, true, false, List.of(), null, trades,
                true, entityLoot, null, true, giftPool).encode(subBuf);
        SubmitEntityEditorPacket decoded = SubmitEntityEditorPacket.decode(subBuf);
        helper.assertTrue(decoded.entityId() == 7, "Submit 包 entityId 往返");
        assertDouble(helper, 40.0D, decoded.attrs().get(MAX_HEALTH_ID), "Submit 包属性往返");
        helper.assertTrue(decoded.effects().get(0).amplifier() == 255, "Submit 包效果等级往返");
        helper.assertTrue(decoded.effects().get(0).duration() == -1, "Submit 包效果时长往返");
        helper.assertTrue(decoded.traits().get(0).level() == 255, "Submit 包词条等级往返");
        helper.assertTrue(decoded.hostilityLevel() == 12, "Submit 包难度等级往返");
        helper.assertTrue(decoded.health() == 25.0F, "Submit 包当前血量往返");
        helper.assertTrue(decoded.removeEntity(), "Submit 包移除标志往返");
        helper.assertTrue(decoded.trades() != null && decoded.trades().size() == 1
                        && decoded.trades().get(0).hasCostB()
                        && "diamond".equals(decoded.trades().get(0).result().getPath())
                        && decoded.trades().get(0).countA() == 2,
                "Submit 包交易列表往返");
        helper.assertTrue(decoded.hasLootPage()
                        && decoded.entityLoot() != null
                        && decoded.entityLoot().mode() == io.github.zgxhzhr.superdbg.loot.LootConfig.Mode.APPEND
                        && decoded.entityLoot().entries().size() == 1
                        && decoded.entityLoot().entries().get(0).maxCount() == 3
                        && decoded.entityLoot().entries().get(0).chance() == 50.0F
                        && "loot".equals(decoded.entityLoot().entries().get(0).tag().getString("Mark"))
                        && decoded.typeLoot() == null,
                "Submit 包掉落覆盖往返");
        helper.assertTrue(decoded.hasGiftPage()
                        && decoded.giftPool() != null
                        && decoded.giftPool().entries().size() == 1
                        && decoded.giftPool().entries().get(0).weight() == 5
                        && decoded.giftPool().entries().get(0).maxCount() == 3
                        && "gift".equals(decoded.giftPool().entries().get(0).tag().getString("Mark")),
                "Submit 包女仆回礼池往返");

        // 快照编解码（entityId 先写，removable + 血量 + 属性/效果/词条/难度，与 extraData 顺序一致）
        FriendlyByteBuf snapBuf = new FriendlyByteBuf(Unpooled.buffer());
        snapBuf.writeVarInt(9);
        EntityEditorData.writeSnapshot(snapBuf, false, 18.5F,
                List.of(new EntityEditorData.AttrEntry(MAX_HEALTH_ID, 40.5D)),
                List.of(new EntityEditorData.EffectEntry(SPEED_EFFECT_ID, 3, 200)),
                List.of(new EntityEditorData.TraitEntry(
                        new ResourceLocation("l2hostility", "regeneration"), 3)),
                5, true, List.of(), null, null, null, null,
                new io.github.zgxhzhr.superdbg.loot.VanillaLootSnapshot(List.of()),
                null);
        EntityEditorData snapshot = EntityEditorData.readSnapshot(snapBuf, snapBuf.readVarInt());
        helper.assertTrue(snapshot.entityId() == 9, "快照 entityId 往返");
        helper.assertTrue(!snapshot.removable(), "快照 removable=false（女仆）往返");
        helper.assertTrue(snapshot.health() == 18.5F, "快照当前血量往返");
        assertDouble(helper, 40.5D, snapshot.attrs().get(0).baseValue(), "快照属性往返");
        helper.assertTrue(snapshot.effects().get(0).duration() == 200, "快照效果往返");
        helper.assertTrue(snapshot.traits().get(0).level() == 3, "快照词条往返");
        helper.assertTrue(snapshot.hostilityLevel() == 5, "快照难度等级往返");
        helper.succeed();
    }

    /**
     * 物品属性上限：全部放宽到 int 最大值，下限保留。
     */
    @GameTest(template = "empty")
    public void itemEditorAttributeMaxExpanded(GameTestHelper helper) {
        // 攻击伤害（原上限 2048）：超大值钳到 int 最大值
        var attackDamage = EditableAttribute.ALL.stream()
                .filter(e -> e.attribute() == Attributes.ATTACK_DAMAGE).findFirst().orElseThrow();
        assertDouble(helper, (double) Integer.MAX_VALUE,
                attackDamage.normalize(1.0E12), "攻击伤害上限");
        assertDouble(helper, 0.0D, attackDamage.normalize(-5.0D), "攻击伤害下限仍为 0");

        // 击退抗性（原上限 1）也放开
        var knockback = EditableAttribute.ALL.stream()
                .filter(e -> e.attribute() == Attributes.KNOCKBACK_RESISTANCE).findFirst().orElseThrow();
        assertDouble(helper, (double) Integer.MAX_VALUE,
                knockback.normalize((double) Integer.MAX_VALUE), "击退抗性上限");

        // int 最大值可原样写入钻石剑并读回
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        ItemEditorData data = ItemEditorService.read(sword);
        data.attributes.put(ATTACK_DAMAGE_ID, (double) Integer.MAX_VALUE);
        ItemEditorService.apply(sword, data);
        ItemEditorData reread = ItemEditorService.read(sword);
        assertDouble(helper, (double) Integer.MAX_VALUE,
                reread.attributes.get(ATTACK_DAMAGE_ID), "钻石剑攻击伤害写入 int 最大值");
        helper.succeed();
    }

    /**
     * 女仆保护：普通实体/空引用判定为非女仆；移除逻辑对普通僵尸仍生效。
     * （女仆实体依赖 TLM 环境，true 分支无法在无 TLM 测试环境构造，
     * 服务端提交包另有 isProtectedMaid 兜底。）
     */
    @GameTest(template = "empty")
    public void entityEditorMaidProtection(GameTestHelper helper) {
        Zombie zombie = new Zombie(helper.getLevel());
        helper.assertTrue(!EntityEditorService.isProtectedMaid(zombie), "僵尸不是受保护女仆");
        helper.assertTrue(!EntityEditorService.isProtectedMaid(null), "空实体不是女仆");

        // 普通生物仍可正常移除
        EntityEditorService.apply(zombie, Map.of(), List.of(), true);
        helper.assertTrue(zombie.isRemoved(), "普通僵尸应可被移除");
        helper.succeed();
    }
}
