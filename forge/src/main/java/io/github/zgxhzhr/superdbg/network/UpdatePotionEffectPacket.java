package io.github.zgxhzhr.superdbg.network;

import io.github.zgxhzhr.superdbg.Constants;
import io.github.zgxhzhr.superdbg.potion.PotionEditorProvider;
import io.github.zgxhzhr.superdbg.potion.PotionEditors;
import io.github.zgxhzhr.superdbg.potion.PotionEffectData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端→服务端：更新副手药水某个效果的等级与时长。
 *
 * @param effectIndex 效果在列表中的索引
 * @param amplifier   新等级（0-{@link PotionEffectData#MAX_AMPLIFIER}）
 * @param duration    新时长（-1 永久，或非负 tick）
 */
public record UpdatePotionEffectPacket(int effectIndex, int amplifier, int duration) {

    /** 持续时长上限，避免恶意超大值。 */
    private static final int MAX_DURATION = Integer.MAX_VALUE / 2;

    /**
     * 编码到网络缓冲区。
     */
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(effectIndex);
        buf.writeVarInt(amplifier);
        buf.writeVarInt(duration);
    }

    /**
     * 从网络缓冲区解码。
     */
    public static UpdatePotionEffectPacket decode(FriendlyByteBuf buf) {
        int index = buf.readVarInt();
        int amp = buf.readVarInt();
        int dur = buf.readVarInt();
        return new UpdatePotionEffectPacket(index, amp, dur);
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
        // 仅创造模式可编辑
        if (!player.isCreative()) {
            Constants.LOG.warn("非创造模式玩家 {} 尝试编辑药水，已忽略", player.getName().getString());
            return;
        }

        ItemStack offhand = player.getOffhandItem();
        PotionEditorProvider editor = PotionEditors.find(offhand);
        if (editor == null) {
            Constants.LOG.warn("玩家 {} 副手物品不可编辑，已忽略", player.getName().getString());
            return;
        }

        // 防御性 clamp，客户端校验不可信。amplifier 上限已放开到 Integer.MAX_VALUE
        // （由 MobEffectInstanceMixin 放开 byte 序列化瓶颈支持）。
        int safeAmplifier = Math.max(PotionEffectData.MIN_AMPLIFIER,
                Math.min(PotionEffectData.MAX_AMPLIFIER, amplifier));
        int safeDuration;
        if (duration == -1) {
            safeDuration = -1;
        } else {
            safeDuration = Mth.clamp(duration, 0, MAX_DURATION);
        }

        try {
            editor.writeEffect(offhand, effectIndex, safeAmplifier, safeDuration);
            // 标记副手物品已变更，强制同步到客户端。
            // removed() 提交路径下客户端可能已关闭菜单，非 0 容器 id 的槽位同步包会被丢弃，
            // 需再经 inventoryMenu（containerId=0）同步一次作为兜底。
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
            player.inventoryMenu.broadcastChanges();
            Constants.LOG.debug("玩家 {} 编辑药水效果索引 {}：amplifier={}, duration={}",
                    player.getName().getString(), effectIndex, safeAmplifier, safeDuration);
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            Constants.LOG.warn("编辑药水效果失败：{}", e.getMessage());
        }
    }
}
