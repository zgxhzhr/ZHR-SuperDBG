package io.github.zgxhzhr.superdbg.event;

import io.github.zgxhzhr.superdbg.Constants;
import io.github.zgxhzhr.superdbg.entity.RenderNameStore;
import io.github.zgxhzhr.superdbg.network.NetworkHandler;
import io.github.zgxhzhr.superdbg.network.SyncRenderNamePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;

/**
 * 玩家进入世界时双向同步自定义渲染名：
 * 新玩家获知所有在线玩家的渲染名，其他在线玩家获知新玩家的渲染名。
 */
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RenderNameSyncHandler {

    private RenderNameSyncHandler() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer joined)) {
            return;
        }
        MinecraftServer server = joined.getServer();
        if (server == null) {
            return;
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        String joinedName = RenderNameStore.get(joined);
        for (ServerPlayer other : players) {
            if (other == joined) {
                continue;
            }
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> joined),
                    new SyncRenderNamePacket(other.getUUID(), RenderNameStore.get(other)));
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> other),
                    new SyncRenderNamePacket(joined.getUUID(), joinedName));
        }
    }
}
