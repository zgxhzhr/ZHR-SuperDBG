package io.github.zgxhzhr.superdbg.entityclear;

import io.github.zgxhzhr.superdbg.entity.RemovalGuard;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实体清除器的服务端权威逻辑：按配置扫描目标实体（应用保护名单），
 * 预览模式只计数，执行模式走原版 {@link LivingEntity#kill()} / {@link Entity#discard()}
 * 正常摘除链（不触碰守卫强删链，防移除实体默认就在保护名单里）。
 */
public final class EntityClearService {

    private EntityClearService() {
    }

    /** 一次扫描/执行的结果。 */
    public record Result(int protectedCount, Map<ResourceLocation, Integer> counts) {
    }

    public static Result run(ServerPlayer player, EntityClearConfig config, boolean execute) {
        Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
        int protectedCount = 0;

        MinecraftServer server = player.server;
        List<ServerLevel> levels = new ArrayList<>();
        if (config.scope == EntityClearConfig.SCOPE_ALL_DIMENSIONS) {
            server.getAllLevels().forEach(levels::add);
        } else {
            levels.add(player.serverLevel());
        }

        double radiusSqr = (double) config.radius * config.radius;
        // 先收集后删除，避免遍历实体分区存储时并发摘除
        List<Entity> targets = new ArrayList<>();

        for (ServerLevel level : levels) {
            for (Entity entity : level.getAllEntities()) {
                if (config.scope == EntityClearConfig.SCOPE_AROUND
                        && entity.distanceToSqr(player) > radiusSqr) {
                    continue;
                }
                if (isProtected(player, entity, config)) {
                    protectedCount++;
                    continue;
                }
                ResourceLocation typeId =
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
                boolean selected = config.mode == EntityClearConfig.MODE_BLACKLIST
                        ? !config.types.contains(typeId.toString())
                        : config.types.contains(typeId.toString());
                if (!selected) {
                    continue;
                }
                counts.merge(typeId, 1, Integer::sum);
                targets.add(entity);
            }
        }

        if (execute) {
            for (Entity entity : targets) {
                if (entity instanceof LivingEntity living
                        && config.method == EntityClearConfig.METHOD_KILL) {
                    // 正常死亡流程：掉落战利品/经验、触发死亡事件，原版摘除链完整执行
                    living.kill();
                } else {
                    // 非生物或选择直接抹除：不掉落
                    entity.discard();
                }
            }
        }
        return new Result(protectedCount, counts);
    }

    private static boolean isProtected(ServerPlayer self, Entity entity,
                                       EntityClearConfig config) {
        // 执行者本人永远保护
        if (entity == self) {
            return true;
        }
        if ((config.protectBits & EntityClearConfig.PROTECT_PLAYERS) != 0
                && entity instanceof net.minecraft.world.entity.player.Player) {
            return true;
        }
        if (entity instanceof LivingEntity living) {
            if ((config.protectBits & EntityClearConfig.PROTECT_GUARDED) != 0
                    && RemovalGuard.has(living)) {
                return true;
            }
            if ((config.protectBits & EntityClearConfig.PROTECT_TAMED) != 0
                    && living instanceof TamableAnimal tame && tame.isTame()) {
                return true;
            }
            if ((config.protectBits & EntityClearConfig.PROTECT_BABY) != 0
                    && living.isBaby()) {
                return true;
            }
        }
        if ((config.protectBits & EntityClearConfig.PROTECT_NAMED) != 0
                && entity.hasCustomName()) {
            return true;
        }
        if ((config.protectBits & EntityClearConfig.PROTECT_RIDING) != 0
                && (entity.isPassenger() || !entity.getPassengers().isEmpty())) {
            return true;
        }
        return false;
    }
}
