package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.Constants;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.alchemy.PotionUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 原版 {@link PotionItem#finishUsingItem} 对瞬间效果只调一次
 * {@code applyInstantenousEffect}，从不调用 {@code addEffect}，
 * 导致有持续时间的瞬间效果不会进入 {@code activeEffects}，
 * HUD 不显示、tick Mixin 无法触发持续应用。
 * <p>
 * 重要：原版方法 RETURN 时 stack 已消耗为玻璃瓶，
 * 所以必须在 HEAD 先捕获效果列表到静态 ThreadLocal，
 * 再在 RETURN 时用缓存数据做 addEffect。
 */
@Mixin(PotionItem.class)
public class PotionItemMixin {

    /** 缓存当前消耗的药水效果列表（HEAD 捕获，RETURN 消费） */
    @Unique
    private static final ThreadLocal<List<MobEffectInstance>> superdbg$pendingEffects = new ThreadLocal<>();

    @Inject(
            method = "finishUsingItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD")
    )
    private void superdbg$captureEffects(ItemStack stack,
                                         net.minecraft.world.level.Level level,
                                         LivingEntity entityLiving,
                                         CallbackInfoReturnable<ItemStack> cir) {
        if (level.isClientSide) return;
        // HEAD: 药水还没被消耗，stack 里的 effects 完整
        superdbg$pendingEffects.set(PotionUtils.getMobEffects(stack));
    }

    @Inject(
            method = "finishUsingItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("RETURN")
    )
    private void superdbg$addInstantWithDuration(ItemStack stack,
                                                 net.minecraft.world.level.Level level,
                                                 LivingEntity entityLiving,
                                                 CallbackInfoReturnable<ItemStack> cir) {
        if (level.isClientSide) return;

        List<MobEffectInstance> effects = superdbg$pendingEffects.get();
        if (effects == null) return;
        superdbg$pendingEffects.remove();

        for (MobEffectInstance inst : effects) {
            // duration != 0 覆盖正数时长和 INFINITE_DURATION(-1)
            if (inst.getEffect().isInstantenous() && inst.getDuration() != 0) {
                entityLiving.addEffect(new MobEffectInstance(inst));
            }
        }
    }
}
