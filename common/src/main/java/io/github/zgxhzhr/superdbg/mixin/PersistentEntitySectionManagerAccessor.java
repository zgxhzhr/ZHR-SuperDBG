package io.github.zgxhzhr.superdbg.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;
import java.util.UUID;

/** 暴露 PersistentEntitySectionManager 的 knownUuids（f_157491_）和
 *  visibleEntityStorage（f_157494_），供容器完整性校验重新包装。 */
@Mixin(PersistentEntitySectionManager.class)
public interface PersistentEntitySectionManagerAccessor {

    @Accessor("knownUuids")
    Set<UUID> superdbg$getKnownUuids();

    @Accessor("knownUuids")
    @Mutable
    void superdbg$setKnownUuids(Set<UUID> set);

    @Accessor("visibleEntityStorage")
    EntityLookup<Entity> superdbg$getVisibleEntityStorage();
}
