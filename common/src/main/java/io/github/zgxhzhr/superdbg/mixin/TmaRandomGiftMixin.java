package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.gift.GiftPoolService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TMA（车万女仆羁绊附属）「随机礼物」回礼池按女仆个体自定义的生效点。
 * <p>
 * 注入 {@code RandomGiftService.rollGiftStack(ServerLevel, EntityMaid)} HEAD：
 * 该女仆 persistentData 中存在非空自定义礼物池时，直接返回池中按权重抽取的
 * 物品堆（支持数量区间与 NBT，如附魔书）；否则原样放行，TMA 继续走
 * 其全局标签池（{@code touhou_maid_affection:bond_random_gift_pool}）+
 * 自动注册表候选的默认抽取。
 * <p>
 * 零编译依赖：目标类与 {@code EntityMaid} 参数类型均不在编译类路径中。
 * <ul>
 *   <li>{@link Pseudo} + 字符串 targets：未安装 TMA 时整个 mixin 静默跳过，
 *       不报错（部分测试端未安装 TMA/TLM）；</li>
 *   <li>女仆参数用 {@link Coerce} 标注的 {@link Object} 软类型：目标类存在时
 *       Mixin 对 @Inject 处理器做精确描述符校验，裸 Object 会抛
 *       InvalidInjectionException（Expected EntityMaid but found Object）；
 *       @Coerce 让校验改走 canCoerce 放行，注入时实参本就是 EntityMaid 实例，
 *       向 Object 传参为合法向上转型，回调内再按 {@link LivingEntity} 判定；</li>
 *   <li>{@code remap=false}：TMA 自有类/方法名不参与 SRG 重映射；</li>
 *   <li>{@code require=0}：目标缺失（未装 TMA）时不得导致启动失败，
 *       注入是否生效以游戏内实际回礼行为为准。</li>
 * </ul>
 * 事件型路径（每次实际投递礼物时调用一次），非每帧热点。
 */
@Pseudo
@Mixin(targets = "com.github.touhoumaidaffection.bond.service.RandomGiftService",
        remap = false, priority = 2000)
public abstract class TmaRandomGiftMixin {

    @Inject(method = "rollGiftStack", at = @At("HEAD"), cancellable = true,
            remap = false, require = 0)
    private static void superdbg$overrideGiftPool(ServerLevel level, @Coerce Object maid,
                                                  CallbackInfoReturnable<ItemStack> cir) {
        if (!(maid instanceof LivingEntity living)) {
            return;
        }
        ItemStack gift = GiftPoolService.roll(living);
        if (!gift.isEmpty()) {
            cir.setReturnValue(gift);
        }
    }
}
