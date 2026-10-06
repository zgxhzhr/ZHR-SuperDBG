package io.github.zgxhzhr.superdbg.event;

import io.github.zgxhzhr.superdbg.Constants;
import io.github.zgxhzhr.superdbg.network.NetworkHandler;
import io.github.zgxhzhr.superdbg.network.SyncRenderNamePacket;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

/**
 * 玩家显示名覆写处理器。
 *
 * <p>监听 Forge {@link PlayerEvent.NameFormat}：用调试器存储的渲染名覆写玩家显示名，
 * 使聊天/Tab 列表/头顶名牌/死亡消息统一显示新名。服务端取
 * {@link io.github.zgxhzhr.superdbg.entity.RenderNameStore}（持久化），客户端取
 * {@link io.github.zgxhzhr.superdbg.client.ClientRenderNameCache}（按玩家 UUID，会话内同步）。
 * 只改显示名，不修改 GameProfile 与 UUID。渲染名为空时放行原版逻辑（显示真实名）。</p>
 *
 * <p>同时承担渲染名广播：{@link #broadcast} 向所有客户端发送渲染名同步包
 * （头顶名牌/客户端缓存）与玩家信息更新包（Tab 列表/社交屏幕显示名），
 * 提交路径与玩家登录/重生重同步共用。</p>
 */
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlayerDisplayNameHandler {

    private PlayerDisplayNameHandler() {
    }

    @SubscribeEvent
    public static void onNameFormat(PlayerEvent.NameFormat event) {
        Player player = event.getEntity();
        String renderName;
        if (player.level().isClientSide) {
            renderName = io.github.zgxhzhr.superdbg.client.ClientRenderNameCache.get(player.getUUID());
        } else {
            renderName = io.github.zgxhzhr.superdbg.entity.RenderNameStore.get(player);
        }
        boolean overridden = renderName != null && !renderName.isBlank();
        Constants.LOG.info("[SuperDbg] NameFormat player={} renderName={} overridden={}",
                player.getScoreboardName(), renderName, overridden);
        if (overridden) {
            event.setDisplayname(Component.literal(renderName));
        }
    }

    /** 玩家登录：对所有有渲染名的在线玩家重发渲染名广播，使客户端（含刚登录的）刷新。 */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        MinecraftServer server = event.getEntity().getServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (io.github.zgxhzhr.superdbg.entity.RenderNameStore.get(online) != null) {
                broadcast(server, online);
            }
        }
    }

    /** 玩家重生：实体重建后重发渲染名广播，修复死亡后改名消失的问题。 */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        Player player = event.getEntity();
        MinecraftServer server = player.getServer();
        if (server == null || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (io.github.zgxhzhr.superdbg.entity.RenderNameStore.get(serverPlayer) != null) {
            broadcast(server, serverPlayer);
        }
    }

    /** 死亡复制（PlayerEvent.Clone）：把原玩家的渲染名保底复制到重生后的新玩家并广播。 */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (!(event.getEntity() instanceof ServerPlayer newPlayer)) {
            return;
        }
        // getOriginal() 返回类型即 Player（Clone 构造器持有原玩家）
        Player original = event.getOriginal();
        String old = io.github.zgxhzhr.superdbg.entity.RenderNameStore.get(original);
        String current = io.github.zgxhzhr.superdbg.entity.RenderNameStore.get(newPlayer);
        if (old != null && !old.equals(current)) {
            io.github.zgxhzhr.superdbg.entity.RenderNameStore.set(newPlayer, old);
            MinecraftServer server = newPlayer.getServer();
            if (server != null) {
                broadcast(server, newPlayer);
            }
        }
        Constants.LOG.info("[SuperDbg] Clone 渲染名复制 old={} new={}", old, current);
    }

    /**
     * 向所有客户端广播某个玩家的渲染名：
     * 渲染名同步包（头顶名牌/客户端缓存）+ 玩家信息更新包（Tab 列表/社交屏幕显示名）。
     */
    public static void broadcast(MinecraftServer server, ServerPlayer target) {
        if (server == null) {
            return;
        }
        String renderName = io.github.zgxhzhr.superdbg.entity.RenderNameStore.get(target);
        NetworkHandler.CHANNEL.send(PacketDistributor.ALL.noArg(),
                new SyncRenderNamePacket(target.getUUID(), renderName));
        ClientboundPlayerInfoUpdatePacket updatePacket = new ClientboundPlayerInfoUpdatePacket(
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, target);
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            online.connection.send(updatePacket);
        }
        Constants.LOG.info("[SuperDbg] PlayerDisplayNameHandler.broadcast target={} renderName={}",
                target.getName().getString(), renderName);
    }
}
