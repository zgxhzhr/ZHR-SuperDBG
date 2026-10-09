package io.github.zgxhzhr.superdbg.entity;

import io.github.zgxhzhr.superdbg.mixin.AttributeMapAccessor;
import io.github.zgxhzhr.superdbg.platform.Services;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实体编辑器的数据读写与原子写回。
 * <p>
 * 属性枚举：遍历平台注册表（含原版、Forge 内置、模组）并按
 * {@code AttributeMap.hasAttribute} 过滤，天然只包含该实体类型注册过的属性
 * （玩家没有召唤增援就不会显示）。编辑语义等同 /attribute base set。
 */
public final class EntityEditorService {

    private EntityEditorService() {
    }

    /** 车万女仆（Touhou Little Maid）实体类，未安装该模组时为 null */
    private static Class<?> maidClass;
    private static boolean maidClassResolved;

    /**
     * 是否为车万女仆（含子类/附属扩展女仆）。
     * 软检测：不硬依赖 TLM，未安装时返回 false。
     */
    public static boolean isTouhouMaid(net.minecraft.world.entity.LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        if (!maidClassResolved) {
            maidClassResolved = true;
            try {
                maidClass = Class.forName(
                        "com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid");
            } catch (ClassNotFoundException ignored) {
                maidClass = null;
            }
        }
        return maidClass != null && maidClass.isInstance(entity);
    }

    /**
     * 读取目标实体的全部属性基础值快照（按注册名排序保证界面顺序稳定）。
     * <p>
     * 遍历策略分两层，确保不遗漏任何属性：
     * <ol>
     *   <li>遍历 AttributeMap 内部 {@code attributes} Map：包含该实体持有的
     *       全部属性实例，包括动态注入的、非标准注册的属性（如神秘遗物的
     *       诅咒属性、其他模组运行时添加的属性）。</li>
     *   <li>遍历平台注册表兜底：防止部分属性实例未写入内部 Map 但
     *       hasAttribute 返回 true 的边界情况。</li>
     * </ol>
     */
    public static List<EntityEditorData.AttrEntry> readAttributes(LivingEntity target) {
        List<EntityEditorData.AttrEntry> out = new ArrayList<>();
        Set<ResourceLocation> seen = new HashSet<>();

        // 第一层：直接遍历 AttributeMap 内部 Map，覆盖动态/模组属性
        AttributeMap attributeMap = target.getAttributes();
        if (attributeMap instanceof AttributeMapAccessor accessor) {
            Map<Attribute, AttributeInstance> internal = accessor.superdbg$getAttributes();
            if (internal != null) {
                for (Map.Entry<Attribute, AttributeInstance> entry : internal.entrySet()) {
                    ResourceLocation rl = Services.PLATFORM.attributeId(entry.getKey());
                    if (rl == null) {
                        continue;
                    }
                    seen.add(rl);
                    out.add(new EntityEditorData.AttrEntry(rl, entry.getValue().getBaseValue()));
                }
            }
        }

        // 第二层：注册表兜底
        for (var it = Services.PLATFORM.allAttributes(); it.hasNext(); ) {
            Attribute attribute = it.next();
            if (!target.getAttributes().hasAttribute(attribute)) {
                continue;
            }
            ResourceLocation rl = Services.PLATFORM.attributeId(attribute);
            if (rl == null || seen.contains(rl)) {
                continue;
            }
            out.add(new EntityEditorData.AttrEntry(rl, target.getAttributes().getInstance(attribute).getBaseValue()));
        }

        out.sort(Comparator.comparing(EntityEditorData.AttrEntry::id));
        return out;
    }

    /**
     * 读取目标实体当前药水效果快照。
     */
    public static List<EntityEditorData.EffectEntry> readEffects(LivingEntity target) {
        List<EntityEditorData.EffectEntry> out = new ArrayList<>();
        for (MobEffectInstance inst : target.getActiveEffects()) {
            ResourceLocation rl = BuiltInRegistries.MOB_EFFECT.getKey(inst.getEffect());
            out.add(new EntityEditorData.EffectEntry(rl, inst.getAmplifier(), inst.getDuration()));
        }
        return out;
    }

    /**
     * 权限规则（纯函数，便于测试）：
     * <ul>
     *   <li>编辑自己：始终允许</li>
     *   <li>目标是有管理权限（&ge;2 级）的玩家：仅服务器所有者（4 级，编辑者 &ge;4）可打开；
     *       普通 OP 之间不能互相打开，非 OP 更不行</li>
     *   <li>目标是普通玩家：仅 OP（&ge;2 级）编辑者</li>
     *   <li>目标是非玩家生物：任何创造模式编辑者</li>
     * </ul>
     *
     * @param editorCreative 编辑者是否创造模式
     * @param editorPerm     编辑者权限等级（0/2/4）
     * @param isSelf         目标是否编辑者本人
     * @param targetIsPlayer 目标是否玩家
     * @param targetPerm     目标权限等级（非玩家传 0）
     */
    public static boolean canEdit(boolean editorCreative, int editorPerm,
                                  boolean isSelf, boolean targetIsPlayer, int targetPerm) {
        if (isSelf) {
            return true;
        }
        if (targetIsPlayer) {
            if (targetPerm >= 2 && editorPerm < 4) {
                return false; // 目标是 OP：OP 之间不能互相打开，只有服务器所有者能打开
            }
            return editorPerm >= 2;
        }
        return editorCreative;
    }

    /**
     * 判定编辑者能否编辑目标实体（服务端调用，把实体信息提取为纯函数参数）。
     */
    public static boolean canEdit(LivingEntity target, Player editor) {
        boolean isSelf = target == editor;
        boolean targetIsPlayer = target instanceof Player;
        int targetPerm = targetIsPlayer ? permissionLevel((Player) target) : 0;
        int editorPerm = permissionLevel(editor);
        return canEdit(editor.isCreative(), editorPerm, isSelf, targetIsPlayer, targetPerm);
    }

    /** 权限等级归一化：4＝服务器所有者，2＝OP，0＝普通玩家。 */
    private static int permissionLevel(Player player) {
        return player.hasPermissions(4) ? 4 : player.hasPermissions(2) ? 2 : 0;
    }

    /**
     * 全量原子写回（服务端调用，内部完成全部校验与钳制）。
     *
     * @param attrs   属性注册名 → 目标基础值（仅写入实体已注册的属性，其余忽略）
     * @param effects 效果列表（全量重建，先清空再逐项添加；无效条目丢弃）
     * @param remove  移除实体：玩家走死亡流程，其他实体直接移除
     */
    public static void apply(LivingEntity target,
                             Map<ResourceLocation, Double> attrs,
                             List<EntityEditorData.EffectEntry> effects,
                             boolean remove) {
        apply(target, attrs, effects, remove, -1.0F, false);
    }

    /**
     * 全量原子写回（服务端调用，内部完成全部校验与钳制）。
     *
     * @param attrs      属性注册名 → 目标基础值（仅写入实体已注册的属性，其余忽略）
     * @param effects    效果列表（全量重建，先清空再逐项添加；无效条目丢弃）
     * @param remove     移除实体：玩家走死亡流程，其他实体直接移除
     * @param health       目标当前血量；负数表示不修改当前血量
     * @param removalGuard 是否开启移除守卫（指令/其他模组清除失效，正常伤害仍可杀死）
     */
    public static void apply(LivingEntity target,
                             Map<ResourceLocation, Double> attrs,
                             List<EntityEditorData.EffectEntry> effects,
                             boolean remove,
                             float health,
                             boolean removalGuard) {
        if (target == null || !target.isAlive()) {
            return;
        }

        // ---- 移除守卫 ----
        RemovalGuard.set(target, removalGuard);

        // ---- 属性 ----
        for (Map.Entry<ResourceLocation, Double> e : attrs.entrySet()) {
            Attribute attribute = Services.PLATFORM.attributeById(e.getKey());
            if (attribute == null || !target.getAttributes().hasAttribute(attribute)) {
                continue;
            }
            AttributeInstance instance = target.getAttributes().getInstance(attribute);
            instance.setBaseValue(attribute.sanitizeValue(e.getValue() == null ? 0.0D : e.getValue()));
        }

        // 修改最大生命后，把当前血量钳到新上限内（调试器写入：绕过守卫并同步血量基准）
        AttributeInstance maxHealth = target.getAttributes().getInstance(Attributes.MAX_HEALTH);
        if (maxHealth != null && target.getHealth() > maxHealth.getValue()) {
            final float clampedToMax = (float) maxHealth.getValue();
            RemovalGuard.runWithoutGuard(() -> target.setHealth(clampedToMax));
        }

        // ---- 当前血量（用户显式编辑）----
        if (health >= 0.0F && maxHealth != null) {
            float clamped;
            if (isTouhouMaid(target)) {
                // 女仆：无论输入什么数字，当前血量一律回满到当前上限
                clamped = (float) maxHealth.getValue();
            } else {
                clamped = Math.max(0.0F, Math.min((float) maxHealth.getValue(), health));
            }
            // 调试器写入：绕过守卫并同步血量基准（否则下一 tick 会被当作非法降血回滚）
            final float clampedValue = clamped;
            RemovalGuard.runWithoutGuard(() -> target.setHealth(clampedValue));

            // 血量设为 0：用户意图是让实体死亡。
            // 包在 runWithoutGuard 下让守卫实体的 guardDie 不拦截（DYING 标记同时放行 takeOverDie），
            // 玩家目标的 die() 让原版完整执行触发 respawn/death screen。
            if (clamped <= 0.0F) {
                RemovalGuard.runWithoutGuard(() -> {
                    try {
                        RemovalGuard.DYING.set(true);
                        target.die(target.damageSources().generic());
                    } catch (Exception ignored) {
                    } finally {
                        RemovalGuard.DYING.set(false);
                    }
                });
                // 原版 die() 或 BYPASS 接管都已处理；若仍存活（如自定义 Boss 覆盖 isDeadOrDying），
                // 对非玩家走 forceRemoveEntity 做字段级摘除。
                if (!(target instanceof Player) && !target.isDeadOrDying()) {
                    forceRemoveEntity(target);
                }
            }
        }

        // ---- 效果（全量重建）----
        target.removeAllEffects();
        for (EntityEditorData.EffectEntry entry : effects) {
            MobEffect effect = BuiltInRegistries.MOB_EFFECT.get(entry.id());
            if (effect == null) {
                continue;
            }
            int amplifier = Math.max(0, Math.min(io.github.zgxhzhr.superdbg.potion.PotionEffectData.MAX_AMPLIFIER, entry.amplifier()));
            int duration = entry.duration();
            if (duration < MobEffectInstance.INFINITE_DURATION) {
                duration = MobEffectInstance.INFINITE_DURATION;
            }
            target.addEffect(new MobEffectInstance(effect, duration, amplifier));
        }

        // ---- 移除（激进模式：直接从世界实体索引中清除）----
        // 玩家 kill() 走 remove(KILLED)→setRemoved，会被移除守卫拦截，必须绕过。
        if (remove) {
            RemovalGuard.runWithoutGuard(() -> {
                // 先摘守卫标记：否则移除后会被 worldTickGuard 的重建兜底救回
                RemovalGuard.set(target, false);
                if (target instanceof Player player) {
                    // 玩家：触发死亡流程
                    player.setHealth(0.0F);
                    player.kill();
                } else if (!isProtectedMaid(target)) {
                    // 非玩家非女仆：从世界实体管理器中彻底移除
                    forceRemoveEntity(target);
                }
            });
        }
    }

    /**
     * 激进移除实体（实体编辑器专用，绕过防移除容器门禁）。
     * <p>
     * 完整删除由
     * {@link io.github.zgxhzhr.superdbg.mixin.PersistentEntitySectionManagerMixin} 在<strong>下一 tick 原位</strong>
     * 执行（section multimap 摘除 → stopTicking → stopTracking → scoreboard → knownUuids
     * → levelCallback 置 NULL → removeSectionIfEmpty）。
     * <p>
     * 本方法只做准备工作：
     * <ol>
     *   <li>常见第三方 Boss 的死亡免疫标志位（按字段名尝试置位，不存在则跳过）</li>
     *   <li>直写 removed/removalReason（对覆写 isRemoved() 的 Boss 无效也无所谓）</li>
     *   <li>第三方复活名册摘除 + Boss 血条清理</li>
     *   <li>markForceRemoved（容器门禁白名单钥匙）+ 乘客一并标记 + stopRiding</li>
     * </ol>
     * <strong>不要传送目标</strong>：fullDelete 依赖实体当前 blockPosition 取 EntitySection，
     * 送到未加载区块会导致 section=null，实体残留于 multimap 无法摘除。
     */
    private static void forceRemoveEntity(LivingEntity target) {
        io.github.zgxhzhr.superdbg.Constants.LOG.info(
                "[管理员主动操作·强制移除] 开始（编辑器唯一合法删除途径，守卫按流程放行，并非对抗失败）: {} uuid={} class={}",
                target, target.getUUID(), target.getClass().getName());

        // 1. 解除常见第三方 Boss 的死亡免疫/反作弊标志（字段级，不存在则跳过，不触发回调）
        setFieldIfExists(target, "apocalypseDeath", true);
        setFieldIfExists(target, "antiCheat", false);

        // 2. 直写 removed/removalReason 字段
        RemovalGuard.forceWriteRemoved(target, Entity.RemovalReason.DISCARDED);

        // 3. 复活名册摘除 + Boss 血条清理
        io.github.zgxhzhr.superdbg.compat.ThirdPartyBossCompat.beforeForceRemove(target);

        // 4. 标记 FORCE_REMOVE + 处理骑乘链；BYPASS 下执行，所有守卫容器直接放行
        RemovalGuard.runWithoutGuard(() -> {
            RemovalGuard.markForceRemoved(target);
            try { target.stopRiding(); } catch (Exception ignored) {}
            for (Entity passenger : new java.util.ArrayList<>(target.getPassengers())) {
                RemovalGuard.markForceRemoved(passenger);
            }
        });

        // 5. 首轮高伤害补刀（setHealth(0)+genericKill+generic+die）：
        //    对死亡免疫/字段自愈型 Boss 先打一轮，没打死也由下一 tick 管理器做字段级摘除；
        //    之后每 tick 管理器 tick HEAD 会持续补刀直到实体消失
        RemovalGuard.damageToKill(target);

        io.github.zgxhzhr.superdbg.Constants.LOG.info(
                "[管理员主动操作·强制移除] 已标记：下一 tick 由区块管理器原位彻底删除（本次删除是主动操作，完成后守卫随之解除）: pos=({},{},{}) isRemoved()={}",
                target.getX(), target.getY(), target.getZ(), target.isRemoved());
    }

    /** 在目标类及其父类中查找指定字段名，找到则设置值。 */
    private static void setFieldIfExists(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = findField(target.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                field.set(target, value);
            }
        } catch (Exception ignored) {}
    }

    /** 在类层次结构中向上查找字段。 */
    private static java.lang.reflect.Field findField(Class<?> clazz, String fieldName) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 目标是否为受保护的女仆（Touhou Little Maid 模组）。
     * <p>
     * 模组不硬依赖 TLM，通过实体类型注册名判定，
     * TLM 未安装时注册表中不存在该 id，自然永远返回 false。
     */
    public static boolean isProtectedMaid(Entity entity) {
        if (entity == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return "touhou_little_maid".equals(id.getNamespace()) && "maid".equals(id.getPath());
    }

    // ==================== 村民交易 ====================

    /**
     * 读取村民类实体（{@link AbstractVillager}：村民/流浪商人）的交易列表。
     * 非村民类实体返回 {@code null}（界面不显示交易页签）；无交易的村民返回空列表。
     */
    public static List<EntityEditorData.TradeEntry> readTrades(LivingEntity target) {
        if (!(target instanceof AbstractVillager merchant)) {
            return null;
        }
        MerchantOffers offers = merchant.getOffers();
        List<EntityEditorData.TradeEntry> out = new ArrayList<>(offers.size());
        for (MerchantOffer offer : offers) {
            ItemStack a = offer.getCostA();
            ItemStack b = offer.getCostB();
            ItemStack r = offer.getResult();
            if (a.isEmpty() || r.isEmpty()) {
                continue;
            }
            ResourceLocation idA = BuiltInRegistries.ITEM.getKey(a.getItem());
            ResourceLocation idR = BuiltInRegistries.ITEM.getKey(r.getItem());
            boolean hasB = !b.isEmpty();
            out.add(new EntityEditorData.TradeEntry(
                    idA, a.getCount(), a.getTag() == null ? null : a.getTag().copy(),
                    hasB, hasB ? BuiltInRegistries.ITEM.getKey(b.getItem()) : null, b.getCount(),
                    hasB && b.getTag() != null ? b.getTag().copy() : null,
                    idR, r.getCount(),
                    r.getTag() == null ? null : r.getTag().copy(),
                    offer.getMaxUses(), offer.getXp()));
        }
        return out;
    }

    /**
     * 全量替换村民类实体的交易列表。
     * <p>
     * 直接重写 {@link AbstractVillager#getOffers()}（其底层是可写的
     * {@link MerchantOffers}）。无效物品（空气/未注册）的条目跳过；
     * 数量钳制 1-64。{@code trades} 为 {@code null} 表示目标非村民，不处理。
     */
    public static void applyTrades(LivingEntity target, List<EntityEditorData.TradeEntry> trades) {
        if (trades == null || !(target instanceof AbstractVillager merchant)) {
            return;
        }
        MerchantOffers offers = merchant.getOffers();
        offers.clear();
        for (EntityEditorData.TradeEntry t : trades) {
            Item itemA = BuiltInRegistries.ITEM.get(t.costA());
            Item itemR = BuiltInRegistries.ITEM.get(t.result());
            if (itemA == Items.AIR || itemR == Items.AIR) {
                continue;
            }
            int countA = clampTradeCount(t.countA());
            int countR = clampTradeCount(t.countR());
            ItemStack stackA = new ItemStack(itemA, countA);
            if (t.costATag() != null) {
                stackA.setTag(t.costATag().copy());
            }
            ItemStack costB = ItemStack.EMPTY;
            if (t.hasCostB() && t.costB() != null) {
                Item itemB = BuiltInRegistries.ITEM.get(t.costB());
                if (itemB != Items.AIR) {
                    costB = new ItemStack(itemB, clampTradeCount(t.countB()));
                    if (t.costBTag() != null) {
                        costB.setTag(t.costBTag().copy());
                    }
                }
            }
            ItemStack stackR = new ItemStack(itemR, countR);
            if (t.resultTag() != null) {
                stackR.setTag(t.resultTag().copy());
            }
            int maxUses = Math.max(1, Math.min(9999, t.maxUses()));
            int xp = Math.max(0, Math.min(1000, t.xp()));
            try {
                offers.add(new MerchantOffer(
                        stackA, costB, stackR,
                        0, maxUses, xp, 0.05F, 0));
            } catch (Exception e) {
                // 个别模组物品在构造 MerchantOffer 时校验异常，跳过该条不影响其余
            }
        }
        // 通知实体内部交易缓存刷新（如需要），村民下次打开交易界面即读到新列表
        merchant.setTradingPlayer(null);
    }

    private static int clampTradeCount(int count) {
        return Math.max(1, Math.min(64, count));
    }

    // ==================== 掉落覆盖 ====================

    /** 读取本实体当前生效的掉落覆盖配置；无覆盖返回 null */
    public static io.github.zgxhzhr.superdbg.loot.LootConfig readEntityLoot(LivingEntity target) {
        net.minecraft.nbt.CompoundTag forgeData =
                io.github.zgxhzhr.superdbg.loot.LootOverrideService.persistentData(target);
        if (!forgeData.contains(io.github.zgxhzhr.superdbg.loot.LootConfig.NBT_KEY,
                net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            return null;
        }
        return io.github.zgxhzhr.superdbg.loot.LootConfig.fromNbt(
                forgeData.getCompound(io.github.zgxhzhr.superdbg.loot.LootConfig.NBT_KEY));
    }

    /** 读取该实体类型全体的掉落覆盖配置；无覆盖或拿不到服务器时返回 null */
    public static io.github.zgxhzhr.superdbg.loot.LootConfig readTypeLoot(LivingEntity target) {
        if (target.getServer() == null) {
            return null;
        }
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        return io.github.zgxhzhr.superdbg.loot.LootOverrideData.get(target.getServer()).get(typeId);
    }

    /**
     * 掉落覆盖写回（服务端，校验与钳制在此完成）。
     * <p>
     * 语义：{@code entityLoot}/{@code typeLoot} 为 null 表示该作用域<strong>删除覆盖</strong>；
     * {@code hasLootPage} 为 false（玩家目标无掉落页）时整体跳过，防止误删类型配置。
     * 无效物品条目丢弃；数量/概率/抢夺加成按 {@code LootMath} 钳制；条目数上限 64。
     */
    public static void applyLoot(LivingEntity target, boolean hasLootPage,
                                 io.github.zgxhzhr.superdbg.loot.LootConfig entityLoot,
                                 io.github.zgxhzhr.superdbg.loot.LootConfig typeLoot) {
        if (!hasLootPage) {
            return;
        }
        // 本实体：写 persistentData 或删键
        net.minecraft.nbt.CompoundTag forgeData =
                io.github.zgxhzhr.superdbg.loot.LootOverrideService.persistentData(target);
        if (entityLoot == null) {
            forgeData.remove(io.github.zgxhzhr.superdbg.loot.LootConfig.NBT_KEY);
        } else {
            io.github.zgxhzhr.superdbg.loot.LootConfig cleaned = sanitizeLoot(entityLoot);
            forgeData.put(io.github.zgxhzhr.superdbg.loot.LootConfig.NBT_KEY,
                    cleaned.toNbt());
        }
        // 全类型：写 SavedData 或移除条目
        if (target.getServer() != null) {
            ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
            io.github.zgxhzhr.superdbg.loot.LootOverrideData data =
                    io.github.zgxhzhr.superdbg.loot.LootOverrideData.get(target.getServer());
            if (typeLoot == null) {
                data.remove(typeId);
            } else {
                data.set(typeId, sanitizeLoot(typeLoot));
            }
        }
    }

    // ==================== 女仆回赠礼物池（TMA 随机礼物） ====================

    /**
     * 读取目标女仆的自定义回礼池配置；非女仆或无配置返回 null。
     * <p>
     * 页签是否展示由客户端按 TMA 加载状态决定，此处只做存储读取；
     * TMA 未安装时配置即使存在也只是安静地留在存档中，无任何副作用。
     */
    public static io.github.zgxhzhr.superdbg.gift.GiftPoolConfig readGiftPool(LivingEntity target) {
        if (!isTouhouMaid(target)) {
            return null;
        }
        return io.github.zgxhzhr.superdbg.gift.GiftPoolService.findPool(target);
    }

    /**
     * 回礼池写回（服务端，校验与钳制在此完成）。
     * <p>
     * 语义：{@code hasGiftPage} 为 false（非女仆/未装 TMA 的目标无赠礼页）时整体跳过；
     * {@code giftPool} 为 null 或条目为空表示删除自定义池，回退 TMA 默认全局池。
     * 无效物品条目丢弃；数量 1-64、权重 1-1000、条目数上限 64；NBT 原样保留。
     */
    public static void applyGiftPool(LivingEntity target, boolean hasGiftPage,
                                     io.github.zgxhzhr.superdbg.gift.GiftPoolConfig giftPool) {
        if (!hasGiftPage || !isTouhouMaid(target)) {
            return;
        }
        net.minecraft.nbt.CompoundTag forgeData =
                io.github.zgxhzhr.superdbg.loot.LootOverrideService.persistentData(target);
        io.github.zgxhzhr.superdbg.gift.GiftPoolConfig cleaned =
                giftPool == null ? null : sanitizeGiftPool(giftPool);
        if (cleaned == null || cleaned.entries().isEmpty()) {
            forgeData.remove(io.github.zgxhzhr.superdbg.gift.GiftPoolConfig.NBT_KEY);
        } else {
            forgeData.put(io.github.zgxhzhr.superdbg.gift.GiftPoolConfig.NBT_KEY,
                    cleaned.toNbt());
        }
    }

    /** 服务端不信任客户端：逐条校验并钳制，返回清理后的配置副本 */
    private static io.github.zgxhzhr.superdbg.gift.GiftPoolConfig sanitizeGiftPool(
            io.github.zgxhzhr.superdbg.gift.GiftPoolConfig config) {
        List<io.github.zgxhzhr.superdbg.gift.GiftPoolConfig.GiftEntry> cleaned = new ArrayList<>();
        for (io.github.zgxhzhr.superdbg.gift.GiftPoolConfig.GiftEntry e : config.entries()) {
            if (cleaned.size() >= io.github.zgxhzhr.superdbg.gift.GiftPoolConfig.MAX_ENTRIES) {
                break;
            }
            Item item = BuiltInRegistries.ITEM.get(e.item());
            if (item == Items.AIR) {
                continue;
            }
            int[] range = io.github.zgxhzhr.superdbg.gift.GiftPoolConfig
                    .normalizeRange(e.minCount(), e.maxCount());
            cleaned.add(new io.github.zgxhzhr.superdbg.gift.GiftPoolConfig.GiftEntry(
                    e.item(), range[0], range[1],
                    io.github.zgxhzhr.superdbg.gift.GiftPoolConfig.clampWeight(e.weight()),
                    e.tag() == null ? null : e.tag().copy()));
        }
        return new io.github.zgxhzhr.superdbg.gift.GiftPoolConfig(cleaned);
    }

    /** 服务端不信任客户端：逐条校验并钳制，返回清理后的配置副本 */
    private static io.github.zgxhzhr.superdbg.loot.LootConfig sanitizeLoot(
            io.github.zgxhzhr.superdbg.loot.LootConfig config) {
        List<io.github.zgxhzhr.superdbg.loot.LootConfig.LootEntry> cleaned = new ArrayList<>();
        for (io.github.zgxhzhr.superdbg.loot.LootConfig.LootEntry e : config.entries()) {
            if (cleaned.size() >= io.github.zgxhzhr.superdbg.loot.LootMath.MAX_ENTRIES) {
                break;
            }
            Item item = BuiltInRegistries.ITEM.get(e.item());
            if (item == Items.AIR) {
                continue;
            }
            int[] range = io.github.zgxhzhr.superdbg.loot.LootMath.normalizeRange(
                    e.minCount(), e.maxCount());
            cleaned.add(new io.github.zgxhzhr.superdbg.loot.LootConfig.LootEntry(
                    e.item(), range[0], range[1],
                    io.github.zgxhzhr.superdbg.loot.LootMath.clampChance(e.chance()),
                    io.github.zgxhzhr.superdbg.loot.LootMath.clampChance(e.lootingChanceBonus()),
                    e.tag() == null ? null : e.tag().copy()));
        }
        return new io.github.zgxhzhr.superdbg.loot.LootConfig(config.mode(), cleaned);
    }
}
