package io.github.zgxhzhr.superdbg.mixin;

import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 覆盖 Jade 模组 {@code StatusEffectsProvider.getEffectName}，
 * 让 amplifier >= 10 的药水效果也显示等级。
 * amplifier 0-9 保持罗马数字（I-X），10+ 用阿拉伯数字。
 */
@Mixin(snownee.jade.addon.vanilla.StatusEffectsProvider.class)
public class JadeStatusEffectsProviderMixin {

    @Inject(
            method = "getEffectName(Lnet/minecraft/world/effect/MobEffectInstance;)Lnet/minecraft/network/chat/Component;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void superdbg$jadeAlwaysShowLevel(MobEffectInstance effect, CallbackInfoReturnable<Component> cir) {
        MutableComponent name = effect.getEffect().getDisplayName().copy();
        int amplifier = effect.getAmplifier();

        if (amplifier >= 0 && amplifier <= 9) {
            // 罗马数字 I-X（与原版、Jade 一致）
            name.append(CommonComponents.SPACE)
                    .append(Component.translatable("enchantment.level." + (amplifier + 1)));
        } else if (amplifier > 9) {
            // 阿拉伯数字 11, 12, ... 256
            name.append(CommonComponents.SPACE)
                    .append(Component.literal(String.valueOf(amplifier + 1)));
        }

        cir.setReturnValue(name);
    }
}
