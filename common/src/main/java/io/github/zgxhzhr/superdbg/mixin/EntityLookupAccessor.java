package io.github.zgxhzhr.superdbg.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.level.entity.EntityLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/** 暴露 EntityLookup 的 byId/byUuid 字段，供容器完整性校验重新包装。 */
@Mixin(EntityLookup.class)
public interface EntityLookupAccessor {

    @Accessor("byId")
    Int2ObjectMap<?> superdbg$getById();

    @Accessor("byId")
    @Mutable
    void superdbg$setById(Int2ObjectMap<?> map);

    @Accessor("byUuid")
    Map<UUID, ?> superdbg$getByUuid();

    @Accessor("byUuid")
    @Mutable
    void superdbg$setByUuid(Map<UUID, ?> map);
}
