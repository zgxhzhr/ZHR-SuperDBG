package io.github.zgxhzhr.superdbg.mixin;

import dev.xkmc.l2hostility.content.capability.chunk.RegionalDifficultyModifier;
import dev.xkmc.l2hostility.content.capability.mob.MobTraitCap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 阻止"手动词条型"实体在生成/区块难度就绪时被 L2Hostility 自动初始化。
 * <p>
 * 自动初始化会按区块难度缩放生命并随机生成词条；女仆与玩家的词条
 * 只允许通过实体编辑器手动赋予，保持"加什么有什么"。
 * <p>
 * 取消自动初始化后实体停留在 PRE_INIT 阶段，首次写入词条时由
 * {@link MobTraitCapAccessor} 直接推进到 POST_INIT。
 * <p>
 * 目标类来自可选依赖 L2Hostility，未安装时 Mixin 自动跳过。
 * 不直接引用女仆类（模组不硬依赖 TLM），按实体注册名判定。
 */
@Mixin(MobTraitCap.class)
public abstract class MobTraitCapInitMixin {

    @Inject(
            method = "init(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Ldev/xkmc/l2hostility/content/capability/chunk/RegionalDifficultyModifier;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void superdbg$skipManualTraitEntityAutoInit(Level level, LivingEntity entity,
                                                       RegionalDifficultyModifier data, CallbackInfo ci) {
        if (entity == null) {
            return;
        }
        if (entity instanceof Player) {
            ci.cancel();
            return;
        }
        var type = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (type != null && "touhou_little_maid:maid".equals(type.toString())) {
            ci.cancel();
        }
    }
}
