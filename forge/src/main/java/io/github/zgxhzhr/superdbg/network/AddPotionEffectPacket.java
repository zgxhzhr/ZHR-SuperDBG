package io.github.zgxhzhr.superdbg.network;

import io.github.zgxhzhr.superdbg.Constants;
import io.github.zgxhzhr.superdbg.potion.PotionEditorProvider;
import io.github.zgxhzhr.superdbg.potion.PotionEditors;
import io.github.zgxhzhr.superdbg.potion.PotionEffectData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.function.Supplier;

/**
 * 客户端→服务端：向副手药水追加一条新的药水效果（原有全部效果保持不变）。
 *
 * @param effect    要添加的效果类型（解码时注册 id 不存在则为 null，服务端直接丢弃）
 * @param amplifier 等级（0-{@link PotionEffectData#MAX_AMPLIFIER}）
 * @param duration  时长（-1 永久，或非负 tick）
 */
public record AddPotionEffectPacket(MobEffect effect, int amplifier, int duration) {

    /** 效果 id 缺失时的占位符：解码后查不到对应效果，服务端据此丢弃。 */
    private static final ResourceLocation MISSING_ID = new ResourceLocation("minecraft", "empty");

    /**
     * 编码到网络缓冲区。
     */
    public void encode(FriendlyByteBuf buf) {
        ResourceLocation id = effect == null ? null : ForgeRegistries.MOB_EFFECTS.getKey(effect);
        buf.writeResourceLocation(id == null ? MISSING_ID : id);
        buf.writeVarInt(amplifier);
        buf.writeVarInt(duration);
    }

    /**
     * 从网络缓冲区解码。效果 id 无效时 {@code effect} 为 null。
     */
    public static AddPotionEffectPacket decode(FriendlyByteBuf buf) {
        ResourceLocation id = buf.readResourceLocation();
        MobEffect effect = ForgeRegistries.MOB_EFFECTS.getValue(id);
        int amp = buf.readVarInt();
        int dur = buf.readVarInt();
        return new AddPotionEffectPacket(effect, amp, dur);
    }

    /**
     * 服务端处理。
     * <p>
     * 所有状态修改必须通过 {@link NetworkEvent.Context#enqueueWork} 调度到服务端主线程，
     * 避免与 tick 逻辑产生并发读写。
     */
    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> handleOnServer(ctx));
        ctx.setPacketHandled(true);
    }

    private void handleOnServer(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) {
            return;
        }
        if (effect == null) {
            Constants.LOG.warn("收到未知药水效果的添加请求，已忽略");
            return;
        }
        // 仅创造模式可编辑
        if (!player.isCreative()) {
            Constants.LOG.warn("非创造模式玩家 {} 尝试添加药水效果，已忽略", player.getName().getString());
            return;
        }

        ItemStack offhand = player.getOffhandItem();
        PotionEditorProvider editor = PotionEditors.find(offhand);
        if (editor == null) {
            Constants.LOG.warn("玩家 {} 副手物品不可编辑，已忽略", player.getName().getString());
            return;
        }

        // 防御性 clamp，客户端校验不可信
        int safeAmplifier = PotionEffectData.clampAmplifier(amplifier);
        int safeDuration = PotionEffectData.clampDuration(duration);

        try {
            editor.addEffect(offhand, effect, safeAmplifier, safeDuration);
            // 标记副手物品已变更，强制同步到客户端。
            // removed() 提交路径下客户端可能已关闭菜单，非 0 容器 id 的槽位同步包会被丢弃，
            // 需再经 inventoryMenu（containerId=0）同步一次作为兜底。
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
            player.inventoryMenu.broadcastChanges();
            Constants.LOG.debug("玩家 {} 添加药水效果 {}：amplifier={}, duration={}",
                    player.getName().getString(), ForgeRegistries.MOB_EFFECTS.getKey(effect),
                    safeAmplifier, safeDuration);
        } catch (IllegalArgumentException e) {
            Constants.LOG.warn("添加药水效果失败：{}", e.getMessage());
        }
    }
}
