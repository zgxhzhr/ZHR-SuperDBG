package io.github.zgxhzhr.superdbg.mixin;

import dev.xkmc.l2hostility.content.capability.mob.MobTraitCap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * L2Hostility 词条能力的初始化阶段字段访问器。
 * <p>
 * 女仆等"仅手动赋予词条"的实体不会走 L2Hostility 的自动初始化流程
 * （由 {@code MobTraitCapInitMixin} 取消），实体编辑器首次写入词条后，
 * 需要把阶段从 PRE_INIT 直接推进到 POST_INIT，词条 tick 才会运行。
 * <p>
 * 本 Mixin 目标类来自可选依赖 L2Hostility，mixin 配置中
 * {@code defaultRequire:0}，未安装时自动跳过。
 */
@Mixin(MobTraitCap.class)
public interface MobTraitCapAccessor {

    @Accessor(value = "stage", remap = false)
    @Mutable
    void superdbg$setStage(MobTraitCap.Stage stage);
}
