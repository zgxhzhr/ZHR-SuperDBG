package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.loot.LootConfig;
import io.github.zgxhzhr.superdbg.loot.LootOverrideService;
import io.github.zgxhzhr.superdbg.platform.Services;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 掉落覆盖生效点：注入 {@code dropFromLootTable}（原版战利品表掉落）。
 * <p>
 * REPLACE 在 HEAD 取消原版掉落并改按覆盖配置生成；APPEND 在 TAIL 追加生成。
 * 装备掉落（dropEquipment）、经验（dropExperience）、特殊硬编码掉落
 * （dropCustomDeathLoot，如凋灵之星）均走原版路径不受影响。
 * <p>
 * 仅服务端生效；无覆盖配置时零干预。事件型路径（实体死亡），非每帧调用。
 */
@Mixin(value = LivingEntity.class, priority = 2000)
public abstract class LivingEntityLootMixin {

    @Inject(method = "dropFromLootTable(Lnet/minecraft/world/damagesource/DamageSource;Z)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void superdbg$lootReplace(DamageSource source, boolean playerKill, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide) {
            return;
        }
        LootConfig config = LootOverrideService.findConfig(self);
        if (config == null || config.mode() != LootConfig.Mode.REPLACE) {
            return;
        }
        int looting = Services.PLATFORM.getLootingLevel(self, source);
        LootOverrideService.spawnDrops(self, config, looting);
        ci.cancel();
    }

    @Inject(method = "dropFromLootTable(Lnet/minecraft/world/damagesource/DamageSource;Z)V",
            at = @At("TAIL"), require = 1)
    private void superdbg$lootAppend(DamageSource source, boolean playerKill, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide) {
            return;
        }
        LootConfig config = LootOverrideService.findConfig(self);
        if (config == null || config.mode() != LootConfig.Mode.APPEND) {
            return;
        }
        int looting = Services.PLATFORM.getLootingLevel(self, source);
        LootOverrideService.spawnDrops(self, config, looting);
    }
}
