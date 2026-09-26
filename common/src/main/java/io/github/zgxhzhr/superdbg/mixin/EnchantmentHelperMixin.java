package io.github.zgxhzhr.superdbg.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 放开附魔等级（{@code lvl}）NBT 序列化的双重瓶颈，使其支持 0-{@link Integer#MAX_VALUE}。
 * <p>
 * 原版两个瓶颈：
 * <ul>
 *   <li>{@link EnchantmentHelper#storeEnchantment}：
 *       {@code putShort("lvl", (short) pLevel)}，short 范围 -32768~32767，
 *       超过 32767 的等级在写入时被截断为负数，读出后被 clamp 到 0。</li>
 *   <li>{@link EnchantmentHelper#getEnchantmentLevel(CompoundTag)}：
 *       {@code Mth.clamp(getInt("lvl"), 0, 255)}，任何高于 255 的等级被钳到 255。</li>
 * </ul>
 * <p>
 * 本 Mixin 通过 {@link Inject} 在两个 static 方法的 HEAD 处完全接管实现：
 * <ul>
 *   <li>写入：{@code putShort} → {@code putInt}，支持 int 全范围</li>
 *   <li>读取：跳过 {@code Mth.clamp}，直接返回 NBT 中的原始 int
 *       （仅保留非负下限保护，拒绝非法负值）</li>
 * </ul>
 * 兼容旧存档：旧档以 ShortTag/ByteTag 存储，{@link CompoundTag#getInt} 同样能取到值。
 */
@Mixin(EnchantmentHelper.class)
public abstract class EnchantmentHelperMixin {

    /**
     * 写入端：用 {@code putInt} 代替 {@code putShort}，突破 short 32767 上限。
     * 完全重写 {@link EnchantmentHelper#storeEnchantment}。
     */
    @Inject(
            method = "storeEnchantment(Lnet/minecraft/resources/ResourceLocation;I)Lnet/minecraft/nbt/CompoundTag;",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void superdbg$storeLevelAsInt(ResourceLocation id, int level,
                                                 CallbackInfoReturnable<CompoundTag> cir) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", String.valueOf((Object) id));
        tag.putInt("lvl", level);
        cir.setReturnValue(tag);
    }

    /**
     * 读取端：跳过 {@code Mth.clamp(lvl, 0, 255)}，直接返回 NBT 中读到的原始 int。
     * 完全重写 {@link EnchantmentHelper#getEnchantmentLevel(CompoundTag)}。
     * 仅保留非负下限保护。
     */
    @Inject(
            method = "getEnchantmentLevel(Lnet/minecraft/nbt/CompoundTag;)I",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void superdbg$getLevelNoClamp(CompoundTag nbt,
                                                CallbackInfoReturnable<Integer> cir) {
        int level = nbt.getInt("lvl");
        cir.setReturnValue(Math.max(0, level));
    }
}
