package io.github.zgxhzhr.superdbg.event;

import io.github.zgxhzhr.superdbg.entity.RemovalGuard;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Set;

/**
 * TLM 收容类道具（魂符 / 相机 / 照片 / 胶片）交互女仆时的收魂白名单打标。
 * <p>
 * 主放行通道并不依赖本打标：收容调用栈全在 {@code com.github.tartaricacid}
 * 白名单包内，{@link RemovalGuard#isAbnormalRemoval} 判栈放行 discard 后会登记
 * DISMISSED，管理器即可正常摘除。本打标只作为收魂窗口（3 秒）内的附加放行
 * （跨维度等旁路拦截），并先还原真 UUID 供 TLM 写收容 NBT。
 * <p>
 * 两个历史坑：
 * <ul>
 *   <li>空魂符注册名是 <b>smart_slab_empty</b>（曾误写 smart_slab，打标从未命中）；</li>
 *   <li>TLM 在自定义 InteractMaidEvent 中 cancel，其他模组也可能 cancel 原生
 *       EntityInteract，必须 {@code receiveCanceled = true} 才能保证收到。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = io.github.zgxhzhr.superdbg.Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MaidSoulInteractHandler {

    private static final String NAMESPACE = "touhou_little_maid";

    /** 会收容/打包女仆的 TLM 道具 */
    private static final Set<ResourceLocation> CONTAIN_ITEMS = Set.of(
            new ResourceLocation(NAMESPACE, "smart_slab_empty"), // 空魂符（右键收容）
            new ResourceLocation(NAMESPACE, "camera"),           // 相机（拍照收容）
            new ResourceLocation(NAMESPACE, "photo"),            // 照片
            new ResourceLocation(NAMESPACE, "film")              // 胶片
    );

    private MaidSoulInteractHandler() {
    }

    @SubscribeEvent(receiveCanceled = true)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()) {
            return; // 只在服务端打标，守卫逻辑在服务端
        }
        if (!(event.getTarget() instanceof LivingEntity living)
                || !RemovalGuard.isMaidEntity(living)) {
            return;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(event.getItemStack().getItem());
        if (CONTAIN_ITEMS.contains(id)) {
            // 先还原真 UUID（TLM 收容读 maid.getUUID() 存 NBT 前需要真实身份），再打白名单
            RemovalGuard.restoreRealUuid(living);
            RemovalGuard.markLegit(living.getId()); // 按实体 ID 打标（稳定不依赖 UUID 状态）
            io.github.zgxhzhr.superdbg.Constants.LOG.debug("[SuperDbg] TLM 收容道具右键女仆，标记收魂: item={}, maid={}", id, living);
        }
    }
}
