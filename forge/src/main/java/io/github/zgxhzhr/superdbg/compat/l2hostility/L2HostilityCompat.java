package io.github.zgxhzhr.superdbg.compat.l2hostility;

import io.github.zgxhzhr.superdbg.entity.EntityEditorData;
import io.github.zgxhzhr.superdbg.mixin.MobTraitCapAccessor;
import dev.xkmc.l2hostility.content.capability.mob.MobTraitCap;
import dev.xkmc.l2hostility.content.traits.base.MobTrait;
import dev.xkmc.l2hostility.init.registrate.LHTraits;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * L2Hostility（莱特兰·恶意）词条系统兼容层。
 * <p>
 * 本类是模组中<b>唯一</b>直接引用 L2Hostility 类型的地方。主代码仅在
 * {@link #isLoaded()} 为真时才调用本类，JVM 对方法体内类型为延迟加载，
 * 未安装 L2Hostility 时本类不会被加载，因此不会触发 NoClassDefFoundError。
 * <p>
 * 数据模型：词条是挂在 {@link LivingEntity} 上的 Capability，
 * {@code MobTraitCap.traits} 为 词条→等级 的有序表；另有一个难度等级 lv，
 * 决定敌人的生命缩放倍率。写入范式对齐官方 TraitAdderWand：
 * 改表 → trait.initialize/postInit → syncToClient。
 */
public final class L2HostilityCompat {

    /** L2Hostility 模组 id */
    public static final String MODID = "l2hostility";

    /** 实体编辑器允许的词条等级上限（0 表示无该词条） */
    public static final int MAX_TRAIT_LEVEL = 255;

    private L2HostilityCompat() {
    }

    /**
     * L2Hostility 是否安装。所有调用方必须先过此守卫。
     */
    public static boolean isLoaded() {
        return ModList.get().isLoaded(MODID);
    }

    /**
     * 目标实体是否拥有词条能力（在白名单标签中或实现 Enemy 接口）。
     */
    public static boolean isSupported(LivingEntity entity) {
        if (!isLoaded() || entity == null) {
            return false;
        }
        return MobTraitCap.HOLDER.isProper(entity);
    }

    /**
     * 读取目标的全部词条快照（全量已注册词条，按注册名排序）。
     * 等级为 0 表示目标当前未拥有该词条。
     */
    public static List<EntityEditorData.TraitEntry> readTraits(LivingEntity entity) {
        if (!isSupported(entity)) {
            return List.of();
        }
        MobTraitCap cap = MobTraitCap.HOLDER.get(entity);
        List<MobTrait> all = new ArrayList<>(LHTraits.TRAITS.get().getValues());
        all.sort(Comparator.comparing(t -> t.getRegistryName().toString()));
        List<EntityEditorData.TraitEntry> result = new ArrayList<>(all.size());
        for (MobTrait trait : all) {
            if (trait.isBanned()) {
                continue;
            }
            result.add(new EntityEditorData.TraitEntry(trait.getRegistryName(), cap.getTraitLevel(trait)));
        }
        return result;
    }

    /**
     * 读取难度等级；目标无词条能力时返回 -1（表示不参与写回）。
     */
    public static int readLevel(LivingEntity entity) {
        if (!isSupported(entity)) {
            return -1;
        }
        return MobTraitCap.HOLDER.get(entity).getLevel();
    }

    /**
     * 全量写回词条与难度等级。
     * <p>
     * 仅处理"提交值与现值不同"的词条，避免无谓地重复 initialize。
     * 移除词条时按官方法杖范式以等级 0 调用 initialize/postInit，
     * 让词条自行清理注册的修饰符/效果。对于从未自动初始化的实体
     * （女仆，自动初始化已被 Mixin 取消），首次写入后直接推进到
     * POST_INIT 阶段，词条 tick 才会运行。
     *
     * @param submitted 全量词条（未包含在列表中的词条保持不变）
     * @param level     难度等级，-1 表示不修改
     */
    public static void apply(LivingEntity entity, List<EntityEditorData.TraitEntry> submitted, int level) {
        if (!isSupported(entity) || submitted == null) {
            return;
        }
        MobTraitCap cap = MobTraitCap.HOLDER.get(entity);
        boolean wasInitialized = cap.isInitialized();

        for (EntityEditorData.TraitEntry entry : submitted) {
            MobTrait trait = LHTraits.TRAITS.get().getValue(entry.id());
            if (trait == null || trait.isBanned()) {
                continue;
            }
            int wanted = clampLevel(entry.level());
            int old = cap.getTraitLevel(trait);
            if (wanted == old) {
                continue;
            }
            if (wanted == 0) {
                // 当前处于词条自身 tick 调用栈时入待删队列，否则直接移除；
                // 提交包在网络线程转主线程后执行，不在 tick 栈内，直接移除。
                cap.removeTrait(trait);
                trait.initialize(entity, 0);
                trait.postInit(entity, 0);
            } else {
                cap.traits.put(trait, wanted);
                trait.initialize(entity, wanted);
                trait.postInit(entity, wanted);
            }
        }

        if (level >= 0) {
            // setLevel 内部按 L2Hostility 配置钳制并重算生命缩放修饰符（幂等）
            cap.setLevel(entity, Math.max(0, level));
        }

        if (!wasInitialized) {
            ((MobTraitCapAccessor) cap).superdbg$setStage(MobTraitCap.Stage.POST_INIT);
        }
        cap.syncToClient(entity);
    }

    private static int clampLevel(int raw) {
        if (raw < 0) {
            return 0;
        }
        return Math.min(raw, MAX_TRAIT_LEVEL);
    }
}
