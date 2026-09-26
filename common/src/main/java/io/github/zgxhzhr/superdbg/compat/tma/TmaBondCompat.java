package io.github.zgxhzhr.superdbg.compat.tma;

import io.github.zgxhzhr.superdbg.entity.EntityEditorData;
import io.github.zgxhzhr.superdbg.platform.Services;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Touhou Maid Affection（车万女仆羁绊附属）+ TLM 好感度软兼容层。
 * <p>
 * 零编译依赖：TLM/TMA 类型全部通过反射与 NBT 字面量访问，未安装时本类
 * 除 {@link #LOADED} 检测外不会被实际调用。
 * <p>
 * 数据分布（两个独立系统）：
 * <ul>
 *   <li><b>TLM 好感度</b>：挂在女仆实体 SynchedEntityData 上的 int 点数
 *       （0-384，{@code EntityMaid.getFavorability/setFavorability}），
 *       等级由点数派生：&lt;64=0、&lt;192=1、&lt;384=2、其余=3。</li>
 *   <li><b>TMA 羁绊</b>：挂在<b>主人玩家</b> persistentData 的
 *       {@code touhou_maid_affection.bond -> maids -> <女仆UUID>} 子 compound 中，
 *       含 BondLevel（TMA 每次同步时会按好感度等级覆写的缓存）、BondUnlocked、
 *       BondAbilities（能力 id → bool）、RandomGiftQueue（待发礼物数）。</li>
 * </ul>
 */
public final class TmaBondCompat {

    public static final String MODID = "touhou_maid_affection";

    /** TMA 已加载（TLM 本体由 {@code EntityEditorService.isTouhouMaid} 单独检测） */
    public static final boolean LOADED = detect();

    // ---- NBT 字面量（与 TMA BondKeys 完全一致，改动需同步）----
    private static final String ROOT = "touhou_maid_affection.bond";
    private static final String MAIDS = "maids";
    private static final String BOND_LEVEL = "BondLevel";
    private static final String BOND_UNLOCKED = "BondUnlocked";
    private static final String BOND_ABILITIES = "BondAbilities";
    /** TMA 当前能力数据版本；写入能力时必须带上，否则 TMA 迁移逻辑会把能力全部重置为 false */
    private static final String BOND_ABILITY_VERSION = "BondAbilityVersion";
    private static final int CURRENT_ABILITY_VERSION = 2;
    private static final String RANDOM_GIFT_QUEUE = "RandomGiftQueue";

    /** TMA 羁绊解锁阈值（BondConfig.DEFAULT_UNLOCK_LEVEL） */
    public static final int UNLOCK_LEVEL = 3;
    /** TLM 好感度点数上限（FavorabilityManager.LEVEL_3_POINT） */
    public static final int MAX_FAVORABILITY = 384;

    /** TMA 默认能力 id 的规范展示顺序（BondAbilityManager.registerDefaults + YSM 附属） */
    private static final List<String> CANONICAL_ABILITIES = List.of(
            "lap_pillow", "emergency_heal", "morning_kiss", "random_gift", "ysm_action");

    private static volatile Class<?> maidClass;
    private static Method getFavorability;
    private static Method setFavorability;
    private static Method getOwnerUuid;

    private TmaBondCompat() {
    }

    private static boolean detect() {
        try {
            return Services.PLATFORM.isModLoaded(MODID);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Class<?> maidClass() {
        if (maidClass == null) {
            synchronized (TmaBondCompat.class) {
                if (maidClass == null) {
                    try {
                        maidClass = Class.forName(
                                "com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid");
                    } catch (ClassNotFoundException e) {
                        maidClass = void.class;
                    }
                }
            }
        }
        return maidClass == void.class ? null : maidClass;
    }

    private static boolean isMaid(LivingEntity entity) {
        Class<?> c = maidClass();
        return c != null && c.isInstance(entity);
    }

    // ==================== 反射读取 ====================

    private static int readFavorability(LivingEntity maid) {
        try {
            if (getFavorability == null) {
                getFavorability = maidClass().getMethod("getFavorability");
            }
            return (int) getFavorability.invoke(maid);
        } catch (Exception e) {
            return 0;
        }
    }

    private static void writeFavorability(LivingEntity maid, int value) {
        try {
            if (setFavorability == null) {
                setFavorability = maidClass().getMethod("setFavorability", int.class);
            }
            setFavorability.invoke(maid, Math.max(0, Math.min(MAX_FAVORABILITY, value)));
        } catch (Exception ignored) {
        }
    }

    private static UUID readOwnerUuid(LivingEntity maid) {
        try {
            if (getOwnerUuid == null) {
                getOwnerUuid = resolveOwnerUuidMethod();
            }
            return (UUID) getOwnerUuid.invoke(maid);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 映射无关地解析 {@code TamableAnimal.getOwnerUUID()}。
     * <p>
     * 关键坑：开发环境（Mojmap）方法名是 getOwnerUUID，但 Forge 生产环境原版方法
     * 被重混淆为 SRG 名（1.20.1 为 m_21835_），按名字反射在整合包里必然失败。
     * 因此直接在 TamableAnimal 上找「零参数、返回 UUID」的声明方法，
     * TamableAnimal 中满足该签名的只有 getOwnerUUID 一个。
     */
    private static synchronized Method resolveOwnerUuidMethod() throws NoSuchMethodException {
        if (getOwnerUuid != null) {
            return getOwnerUuid;
        }
        try {
            Class<?> tamable = Class.forName("net.minecraft.world.entity.TamableAnimal");
            for (Method m : tamable.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == UUID.class) {
                    m.setAccessible(true);
                    getOwnerUuid = m;
                    return m;
                }
            }
        } catch (ClassNotFoundException ignored) {
            // 极端环境回退：Mojmap 直查
        }
        Method fallback = maidClass().getMethod("getOwnerUUID");
        getOwnerUuid = fallback;
        return fallback;
    }

    // ==================== NBT 访问 ====================

    /** 取在线主人玩家；主人不在线（羁绊数据在离线存档里）时返回 null */
    private static ServerPlayer findOwner(LivingEntity maid) {
        UUID owner = readOwnerUuid(maid);
        if (owner == null) {
            return null;
        }
        if (!(maid.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        MinecraftServer server = serverLevel.getServer();
        return server == null ? null : server.getPlayerList().getPlayer(owner);
    }

    private static CompoundTag maidBondTag(ServerPlayer owner, LivingEntity maid, boolean create) {
        CompoundTag persistent = persistentData(owner);
        if (persistent == null) {
            return new CompoundTag();
        }
        if (!persistent.contains(ROOT, Tag.TAG_COMPOUND)) {
            if (!create) {
                return new CompoundTag();
            }
            persistent.put(ROOT, new CompoundTag());
        }
        CompoundTag root = persistent.getCompound(ROOT);
        if (!root.contains(MAIDS, Tag.TAG_COMPOUND)) {
            if (!create) {
                return new CompoundTag();
            }
            root.put(MAIDS, new CompoundTag());
        }
        CompoundTag maids = root.getCompound(MAIDS);
        String key = maid.getUUID().toString();
        if (maids.contains(key, Tag.TAG_COMPOUND)) {
            return maids.getCompound(key);
        }
        if (!create) {
            return new CompoundTag();
        }
        CompoundTag tag = new CompoundTag();
        maids.put(key, tag);
        return tag;
    }

    /**
     * 通过反射调用 Forge 扩展方法 {@code Entity.getPersistentData()}，
     * 兼容 multi-loader common 模块编译（与 RemovalGuard 同法）。
     */
    private static CompoundTag persistentData(ServerPlayer player) {
        try {
            return (CompoundTag) net.minecraft.world.entity.Entity.class
                    .getMethod("getPersistentData").invoke(player);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    // ==================== 快照 ====================

    /**
     * 读取女仆的好感度/羁绊调试快照。非女仆实体返回 {@code null}。
     */
    public static EntityEditorData.BondSnapshot snapshot(LivingEntity target) {
        if (target == null || !isMaid(target)) {
            return null;
        }
        int favorability = readFavorability(target);

        int bondLevel = 0;
        boolean bondUnlocked = false;
        int giftQueue = 0;
        Map<String, Boolean> abilityMap = new LinkedHashMap<>();
        boolean tma = LOADED;

        if (tma) {
            ServerPlayer owner = findOwner(target);
            if (owner != null) {
                CompoundTag tag = maidBondTag(owner, target, false);
                bondLevel = tag.getInt(BOND_LEVEL);
                bondUnlocked = tag.getBoolean(BOND_UNLOCKED);
                giftQueue = Math.max(0, tag.getInt(RANDOM_GIFT_QUEUE));
                CompoundTag abilities = tag.getCompound(BOND_ABILITIES);
                for (String id : abilities.getAllKeys()) {
                    abilityMap.put(id, abilities.getBoolean(id));
                }
            }
        }

        // 能力列表按规范顺序输出，NBT 中缺失的能力以 false 补入，保证界面顺序稳定
        List<EntityEditorData.BondAbilityEntry> abilities = new ArrayList<>();
        for (String id : CANONICAL_ABILITIES) {
            abilities.add(new EntityEditorData.BondAbilityEntry(id, abilityMap.getOrDefault(id, false)));
        }
        // NBT 里可能有规范表之外的扩展能力（附属注册），追加在后面
        for (Map.Entry<String, Boolean> e : abilityMap.entrySet()) {
            if (!CANONICAL_ABILITIES.contains(e.getKey())) {
                abilities.add(new EntityEditorData.BondAbilityEntry(e.getKey(), e.getValue()));
            }
        }

        return new EntityEditorData.BondSnapshot(true, tma, favorability,
                bondLevel, bondUnlocked, giftQueue, abilities);
    }

    /**
     * 全量写回调试修改。
     * <ul>
     *   <li>TLM 好感度点数：反射写女仆实体（钳制 0-384）；</li>
     *   <li>TMA 羁绊：TMA 未加载/主人离线时跳过，仅写在线主人 persistentData；
     *       BondUnlocked 直接取界面值（调试可强开），BondLevel 取界面值，
     *       能力写入时带 BondAbilityVersion=2 防止 TMA 迁移逻辑清空。</li>
     * </ul>
     */
    public static void apply(LivingEntity target, EntityEditorData.BondSnapshot snap) {
        if (snap == null || !snap.present() || !isMaid(target)) {
            return;
        }
        writeFavorability(target, snap.favorability());

        if (!LOADED) {
            return;
        }
        ServerPlayer owner = findOwner(target);
        if (owner == null) {
            return;
        }
        CompoundTag tag = maidBondTag(owner, target, true);
        tag.putInt(BOND_LEVEL, Math.max(0, snap.bondLevel()));
        tag.putBoolean(BOND_UNLOCKED, snap.bondUnlocked());
        tag.putInt(RANDOM_GIFT_QUEUE, Math.max(0, snap.giftQueue()));

        CompoundTag abilities = tag.getCompound(BOND_ABILITIES);
        for (EntityEditorData.BondAbilityEntry entry : snap.abilities()) {
            if (entry.id() != null && !entry.id().isBlank()) {
                abilities.putBoolean(entry.id(), entry.unlocked());
            }
        }
        tag.put(BOND_ABILITIES, abilities);
        tag.putInt(BOND_ABILITY_VERSION, CURRENT_ABILITY_VERSION);
        // persistentData 随玩家存档自动落盘，无需手动 markDirty
    }

    /**
     * 由 TLM 好感度点数派生等级：&lt;64=0、&lt;192=1、&lt;384=2、其余=3。
     */
    public static int favorabilityLevel(int points) {
        if (points < 64) {
            return 0;
        }
        if (points < 192) {
            return 1;
        }
        if (points < MAX_FAVORABILITY) {
            return 2;
        }
        return 3;
    }
}
