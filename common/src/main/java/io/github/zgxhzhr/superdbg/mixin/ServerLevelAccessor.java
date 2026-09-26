package io.github.zgxhzhr.superdbg.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露 ServerLevel 的 entityManager（SRG f_143244_）和
 *  entityTickList（SRG f_143243_），供容器完整性校验使用。 */
@Mixin(ServerLevel.class)
public interface ServerLevelAccessor {

    @Accessor("entityManager")
    PersistentEntitySectionManager<Entity> superdbg$getEntityManager();

    @Accessor("entityTickList")
    EntityTickList superdbg$getEntityTickList();
}
