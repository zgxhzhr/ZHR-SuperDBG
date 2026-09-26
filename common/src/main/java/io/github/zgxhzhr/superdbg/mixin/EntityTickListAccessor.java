package io.github.zgxhzhr.superdbg.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 EntityTickList.active（SRG f_156903_），供完整性校验重新包装。 */
@Mixin(EntityTickList.class)
public interface EntityTickListAccessor {

    @Accessor("active")
    Int2ObjectMap<Entity> superdbg$getActive();

    @Accessor("active")
    @Mutable
    void superdbg$setActive(Int2ObjectMap<Entity> map);
}
