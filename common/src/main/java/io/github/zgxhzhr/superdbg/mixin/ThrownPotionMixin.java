package io.github.zgxhzhr.superdbg.mixin;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrownPotion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 原版 {@link ThrownPotion#applySplash} 对瞬间效果只调一次
 * {@code applyInstantenousEffect}，从不调 {@code addEffect}，
 * 导致通过编辑器设置时长的喷溅型瞬间效果无法进入 activeEffects。
 */
@Mixin(ThrownPotion.class)
public abstract class ThrownPotionMixin {

    @Inject(
            method = "applySplash",
            at = @At("HEAD"),
            cancellable = true
    )
    private void superdbg$fixSplashInstantWithDuration(List<MobEffectInstance> effectInstances,
                                                      Entity target,
                                                      CallbackInfo ci) {
        ThrownPotion self = (ThrownPotion) (Object) this;
        Entity effectSource = self.getEffectSource();

        for (LivingEntity living : self.level().getEntitiesOfClass(
                LivingEntity.class,
                self.getBoundingBox().inflate(4.0D, 2.0D, 4.0D))) {
            if (!living.isAffectedByPotions()) continue;
            double d0 = self.distanceToSqr(living);
            if (d0 >= 16.0D) continue;

            // target 是 Entity，可能是 null，也可能是 LivingEntity
            double d1 = (living == target) ? 1.0D : 1.0D - Math.sqrt(d0) / 4.0D;

            for (MobEffectInstance inst : effectInstances) {
                MobEffect effect = inst.getEffect();
                if (effect.isInstantenous()) {
                    effect.applyInstantenousEffect(self, self.getOwner(), living,
                            inst.getAmplifier(), d1);
                    // 新增：有 duration 的瞬间效果也 addEffect
                    if (inst.getDuration() != 0) {
                        MobEffectInstance toAdd = new MobEffectInstance(
                                effect, inst.getDuration(), inst.getAmplifier(),
                                inst.isAmbient(), inst.isVisible());
                        living.addEffect(toAdd, effectSource);
                    }
                } else {
                    int dur = inst.mapDuration(p -> (int)(d1 * p + 0.5D));
                    MobEffectInstance toAdd = new MobEffectInstance(
                            effect, dur, inst.getAmplifier(),
                            inst.isAmbient(), inst.isVisible());
                    if (!toAdd.endsWithin(20)) {
                        living.addEffect(toAdd, effectSource);
                    }
                }
            }
        }

        ci.cancel();
    }
}
