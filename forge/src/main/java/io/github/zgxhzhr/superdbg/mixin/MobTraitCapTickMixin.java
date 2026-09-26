package io.github.zgxhzhr.superdbg.mixin;

import dev.xkmc.l2hostility.content.capability.mob.MobTraitCap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "手动词条型"实体（女仆、玩家）的词条保留 Mixin：
 * <ol>
 *   <li>已雇佣女仆词条保留：L2Hostility 默认 {@code allowTraitOnOwnable=false} 时，
 *       {@code MobTraitCap.tick} 每 tick 清空"主人是玩家的生物"的词条；
 *       把女仆的 getOwner() 重定向为 null，仅作用于该清空条件判定。
 *       玩家不是 OwnableEntity，不受此逻辑影响。</li>
 *   <li>阶段懒恢复：stage 不随 NBT 持久化，实体重载后回到 PRE_INIT，
 *       普通生物靠 tick 中的自动初始化兜底（女仆与玩家该流程已被
 *       {@link MobTraitCapInitMixin} 取消）。这里在 tick 开头发现目标
 *       已被手动赋予词条（或调过难度等级）却停在 PRE_INIT 时，
 *       直接恢复到 POST_INIT，保证重进世界后词条继续运行。</li>
 * </ol>
 * 不直接引用女仆类（模组不硬依赖 TLM），按实体注册名判定。
 * 目标类来自可选依赖 L2Hostility，未安装时 Mixin 自动跳过。
 */
@Mixin(MobTraitCap.class)
public abstract class MobTraitCapTickMixin {

    @Redirect(
            method = "tick(Lnet/minecraft/world/entity/LivingEntity;)V",
            remap = false,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/OwnableEntity;getOwner()Lnet/minecraft/world/entity/LivingEntity;",
                    remap = true
            )
    )
    private LivingEntity superdbg$keepMaidTraits(OwnableEntity own) {
        // OwnableEntity 实现类均为 Entity（如 TamableAnimal），按实体注册名判定女仆
        if (own instanceof net.minecraft.world.entity.Entity entity) {
            var type = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
            if (type != null && "touhou_little_maid:maid".equals(type.toString())) {
                return null;
            }
        }
        return own.getOwner();
    }

    @Inject(
            method = "tick(Lnet/minecraft/world/entity/LivingEntity;)V",
            at = @At("HEAD"),
            remap = false
    )
    private void superdbg$restoreManualTraitStage(LivingEntity mob, CallbackInfo ci) {
        if (mob == null || !MobTraitCapTickMixin.superdbg$isManualTraitEntity(mob)) {
            return;
        }
        MobTraitCap self = (MobTraitCap) (Object) this;
        // traits 非空或调过难度等级即代表被手动编辑过；此时 PRE_INIT 是重载后的丢失状态
        if (!self.isInitialized() && (!self.traits.isEmpty() || self.getLevel() > 0)) {
            ((MobTraitCapAccessor) self).superdbg$setStage(MobTraitCap.Stage.POST_INIT);
        }
    }

    /**
     * 女仆与玩家是"手动词条型"实体：自动 init 已被取消，
     * 词条只能通过实体编辑器手动赋予，需要 stage 懒恢复兜底。
     */
    private static boolean superdbg$isManualTraitEntity(LivingEntity entity) {
        if (entity instanceof Player) {
            return true;
        }
        var type = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return type != null && "touhou_little_maid:maid".equals(type.toString());
    }
}
