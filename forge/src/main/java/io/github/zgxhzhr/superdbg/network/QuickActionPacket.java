package io.github.zgxhzhr.superdbg.network;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;

import java.util.Collections;
import java.util.function.Supplier;

/**
 * 客户端→服务端：快捷指令（调试斧 Shift+右键空气打开面板）。
 *
 * @param action    动作类型：{@link #ACTION_TP}、{@link #ACTION_KILL_MOBS}、
 *                  {@link #ACTION_KILL_PLAYERS}、{@link #ACTION_TIME}、{@link #ACTION_WEATHER}
 * @param dimension 传送目标维度 id；非传送动作可为 null
 * @param x/y/z     传送目标坐标
 * @param time      调时间动作的时刻（tick）；调天气动作时为天气种类
 *                  （{@link #WEATHER_CLEAR}、{@link #WEATHER_RAIN}、{@link #WEATHER_THUNDER}）
 */
public record QuickActionPacket(int action, ResourceLocation dimension,
                                double x, double y, double z, int time) {

    public static final int ACTION_TP = 1;
    public static final int ACTION_KILL_MOBS = 2;
    public static final int ACTION_KILL_PLAYERS = 3;
    public static final int ACTION_TIME = 4;
    public static final int ACTION_WEATHER = 5;

    /** {@link #ACTION_WEATHER} 的天气种类：晴天（与原版 {@code /weather clear} 一致） */
    public static final int WEATHER_CLEAR = 0;
    /** {@link #ACTION_WEATHER} 的天气种类：下雨 */
    public static final int WEATHER_RAIN = 1;
    /** {@link #ACTION_WEATHER} 的天气种类：雷雨 */
    public static final int WEATHER_THUNDER = 2;

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(action);
        boolean hasDim = dimension != null;
        buf.writeBoolean(hasDim);
        if (hasDim) {
            buf.writeResourceLocation(dimension);
        }
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeVarInt(time);
    }

    public static QuickActionPacket decode(FriendlyByteBuf buf) {
        int action = buf.readVarInt();
        ResourceLocation dim = null;
        if (buf.readBoolean()) {
            dim = buf.readResourceLocation();
        }
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        int time = buf.readVarInt();
        return new QuickActionPacket(action, dim, x, y, z, time);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> handleOnServer(ctx));
        ctx.setPacketHandled(true);
    }

    private void handleOnServer(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null || !player.isCreative()) {
            return;
        }
        switch (action) {
            case ACTION_TP -> teleport(player);
            case ACTION_KILL_MOBS -> killMobs(player);
            case ACTION_KILL_PLAYERS -> killPlayers(player);
            case ACTION_TIME -> setTime(player);
            case ACTION_WEATHER -> setWeather(player);
            default -> {
            }
        }
    }

    // ==================== 动作实现 ====================

    private void teleport(ServerPlayer player) {
        if (dimension == null) {
            return;
        }
        MinecraftServer server = player.server;
        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimension);
        ServerLevel dest = server.getLevel(dimKey);
        if (dest == null) {
            feedback(player, "§c维度不存在或未加载：" + dimension);
            return;
        }
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        // 同维度/跨维度（含从未去过的模组维度）统一走 Mojang 官方命令传送路径
        // ServerPlayer#teleportTo(ServerLevel,...)：它会
        //   1. 先给落点加 POST_TELEPORT 区块票据（未生成区块由票据驱动生成/加载）；
        //   2. 跨维度时发 ClientboundRespawnPacket、moveTo 到精确落点、
        //      addDuringCommandTeleport 正确加入新维度、发位置包、触发维度事件。
        // 绝不能像传送门那样自定义 ITeleporter——其默认 getPortalInfo 会把主世界旧
        // 坐标当落点，reposition 后再二次 teleport 会造成区块/实体追踪与客户端维度
        // 状态不同步（末地里出现主世界的熔岩/水/羊）。
        player.teleportTo(dest, x + 0.5, y, z + 0.5, Collections.emptySet(), yaw, pitch);
        feedback(player, "§a已传送到 " + dimension
                + String.format(" [%.1f, %.1f, %.1f]", x, y, z));
    }

    private void killMobs(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        int count = 0;
        for (Entity entity : level.getAllEntities()) {
            // 所有非玩家生物走正常死亡流程（掉落/经验/死亡事件）
            if (entity instanceof LivingEntity living && !(entity instanceof Player)) {
                living.kill();
                count++;
            }
        }
        feedback(player, "§c已杀死当前维度 " + count + " 个非玩家生物");
    }

    private void killPlayers(ServerPlayer player) {
        int count = player.server.getPlayerList().getPlayerCount();
        for (ServerPlayer target : player.server.getPlayerList().getPlayers()) {
            target.kill();
        }
        feedback(player, "§c已杀死全部 " + count + " 名玩家（含自己）");
    }

    private void setTime(ServerPlayer player) {
        player.serverLevel().setDayTime(time);
        feedback(player, "§a时间已设置为 " + time);
    }

    /**
     * 直接改写当前维度的天气，时长按原版 {@code /weather} 的默认区间随机。
     *
     * <p>刻意不走 {@code /weather} 命令，而是直接调用 {@code ServerLevel#setWeatherParameters}：
     * 整合包里 EclipticSeasons 会拦截该命令的雷雨分支（详见 WeatherCommandMixin），
     * 面板要求「点一下就必须生效」，因此绕开命令层直接写世界天气。</p>
     */
    private void setWeather(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (time == WEATHER_CLEAR) {
            level.setWeatherParameters(ServerLevel.RAIN_DELAY.sample(level.getRandom()), 0, false, false);
            feedback(player, "§a天气已设为晴天");
        } else if (time == WEATHER_RAIN) {
            level.setWeatherParameters(0, ServerLevel.RAIN_DURATION.sample(level.getRandom()), true, false);
            feedback(player, "§a天气已设为下雨");
        } else if (time == WEATHER_THUNDER) {
            level.setWeatherParameters(0, ServerLevel.THUNDER_DURATION.sample(level.getRandom()), true, true);
            feedback(player, "§a天气已设为雷雨");
        }
    }

    private static void feedback(ServerPlayer player, String text) {
        player.displayClientMessage(Component.literal(text), false);
    }
}
