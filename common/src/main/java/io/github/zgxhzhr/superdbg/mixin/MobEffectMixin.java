package io.github.zgxhzhr.superdbg.mixin;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * 修复 {@link MobEffect#applyInstantenousEffect} 中
 * {@code 4 << amplifier} / {@code 6 << amplifier} 的整数溢出。
 * <p>
 * Java 的 int 左移只取低 5 位偏移量，当 amplifier >= 31 时
 * {@code 4L << 31} 溢出为负数，导致瞬间治疗/伤害计算出 0 或负值，
 * 被 {@code heal} / {@code hurt} 直接忽略。
 * <p>
 * 本 Mixin 在方法入口处用 64 位长整数安全计算期望值，
 * 若结果超过 {@link Integer#MAX_VALUE} 则封顶。
 * <p>
 * 同时修复 {@link MobEffect#applyEffectTick} 中凋零/中毒/生命恢复
 * 三种效果伤害或恢复量恒为 1.0F、amplifier 仅影响频率的问题——
 * 在 amplifier ≥ 6 时这三种效果已每 tick 应用，
 * 但每次应用量仍是 1.0F，导致高级别效果与 0 级效果感知差异极小。
 * 本 Mixin 让每次应用的伤害/恢复量随 amplifier 线性增长（1.0F + amplifier），
 * 与原版瞬时效果使用 {@code 4 << amplifier} 的指数增长相比更温和，
 * 但仍保证 amplifier 提升有实质效果。
 */
@Mixin(MobEffect.class)
public class MobEffectMixin {

    @Inject(
            method = "applyInstantenousEffect(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/LivingEntity;ID)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void superdbg$fixInstantOverflow(@Nullable Entity source,
                                             @Nullable Entity indirectSource,
                                             LivingEntity target,
                                             int amplifier,
                                             double health,
                                             CallbackInfo ci) {
        MobEffect self = (MobEffect) (Object) this;

        // 只处理 HEAL 和 HARM（其他效果最终走 applyEffectTick，不受溢出影响）
        if (self != MobEffects.HEAL && self != MobEffects.HARM) {
            return;
        }

        boolean shouldHeal = (self == MobEffects.HEAL) != target.isInvertedHealAndHarm();
        long baseMultiplier = shouldHeal ? 4L : 6L;

        // 安全计算：用 long 避免溢出，然后封顶到 Integer.MAX_VALUE
        long amount;
        if (amplifier >= 62) {
            amount = Integer.MAX_VALUE;
        } else {
            amount = baseMultiplier * (1L << amplifier);
            if (amount > Integer.MAX_VALUE) {
                amount = Integer.MAX_VALUE;
            } else if (amount < 0) {
                amount = 0;
            }
        }

        float finalAmount = (float) (health * (double) amount + 0.5D);

        if (finalAmount > 0.0F) {
            if (shouldHeal) {
                target.heal(finalAmount);
            } else {
                target.hurt(target.damageSources().magic(), finalAmount);
            }
        }

        ci.cancel();
    }

    /**
     * 凋零/中毒/生命恢复：让每次应用的伤害或恢复量随 amplifier 线性增长。
     * <p>
     * 原版 {@code applyEffectTick} 中这三种效果每次应用固定 1.0F，
     * amplifier 仅通过 {@code isDurationEffectTick} 的
     * {@code 40>>amp} / {@code 25>>amp} / {@code 50>>amp} 控制频率，
     * amp ≥ 6 时已每 tick 应用，但单次量仍是 1.0F，导致高级别效果感知极弱。
     * <p>
     * 本注入在方法 HEAD 处接管三种效果，单次伤害/恢复量改为
     * {@code 1.0F + amplifier}（amp 0 = 1，amp 6 = 7，amp 255 = 256），
     * 用 long 计算防溢出封顶到 {@link Float#MAX_VALUE}。
     * 其它效果（HUNGER/SATURATION/HEAL/HARM 等）原路返回走原版逻辑。
     * <p>
     * 注意：
     * <ul>
     *   <li>POISON 仍受亡灵生物免疫等原版机制保护（{@code EntityEventEvents$MobEffectEvent.Applicable}
     *       在 applyEffectTick 调用前已过滤，不会进入此方法）。</li>
     *   <li>POISON 保留 {@code getHealth() > 1.0F} 下限保护（不会把生物毒到死）。</li>
     *   <li>REGENERATION 保留 {@code getHealth() < getMaxHealth()} 上限保护。</li>
     * </ul>
     */
    @Inject(
            method = "applyEffectTick(Lnet/minecraft/world/entity/LivingEntity;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void superdbg$scalingPerTickEffects(LivingEntity target, int amplifier, CallbackInfo ci) {
        MobEffect self = (MobEffect) (Object) this;

        // 仅处理这三种效果，其它效果原路放行
        if (self != MobEffects.WITHER && self != MobEffects.POISON && self != MobEffects.REGENERATION) {
            return;
        }

        // 单次应用量：1.0F + amplifier，用 long 防溢出
        long amount = 1L + (long) amplifier;
        float finalAmount = amount > (long) Float.MAX_VALUE ? Float.MAX_VALUE : (float) amount;

        if (self == MobEffects.WITHER) {
            target.hurt(target.damageSources().wither(), finalAmount);
        } else if (self == MobEffects.POISON) {
            // 保留原版"不毒到死"的下限保护
            if (target.getHealth() > 1.0F) {
                target.hurt(target.damageSources().magic(), finalAmount);
            }
        } else { // REGENERATION
            // 保留原版"不超过最大血量"的上限保护
            if (target.getHealth() < target.getMaxHealth()) {
                target.heal(finalAmount);
            }
        }

        ci.cancel();
    }
}
