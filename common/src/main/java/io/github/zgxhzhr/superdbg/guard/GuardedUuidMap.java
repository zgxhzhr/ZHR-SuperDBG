package io.github.zgxhzhr.superdbg.guard;

import com.google.common.collect.ForwardingMap;
import io.github.zgxhzhr.superdbg.entity.RemovalGuard;
import net.minecraft.world.level.entity.EntityAccess;

import java.util.Map;
import java.util.UUID;

/**
 * 守卫版 UUID→实体映射：包装 EntityLookup.byUuid（SRG f_156808_）。
 * 反射直写字段的 {@code map.remove(uuid)} 字段级删除经过本包装时被门禁拦截。
 */
public final class GuardedUuidMap<T extends EntityAccess> extends ForwardingMap<UUID, T> {

    private final Map<UUID, T> delegate;

    public GuardedUuidMap(Map<UUID, T> delegate) {
        this.delegate = delegate;
    }

    @Override
    protected Map<UUID, T> delegate() {
        return delegate;
    }

    private boolean blocked(T value) {
        return value instanceof net.minecraft.world.entity.Entity entity
                && RemovalGuard.isContainerDeleteBlocked(entity);
    }

    @Override
    public T remove(Object key) {
        T old = delegate.get(key);
        if (blocked(old)) {
            return old;
        }
        return delegate.remove(key);
    }

    @Override
    public boolean remove(Object key, Object value) {
        T old = delegate.get(key);
        if (value != null && value.equals(old) && blocked(old)) {
            return true;
        }
        return delegate.remove(key, value);
    }

    @Override
    public void clear() {
        if (RemovalGuard.isBypassing()) {
            delegate.clear();
            return;
        }
        var it = delegate.entrySet().iterator();
        while (it.hasNext()) {
            if (!blocked(it.next().getValue())) {
                it.remove();
            }
        }
    }

    @Override
    public T compute(UUID key, java.util.function.BiFunction<? super UUID, ? super T, ? extends T> fn) {
        T old = delegate.get(key);
        T result = delegate.compute(key, fn);
        if (result == null && blocked(old)) {
            delegate.put(key, old);
            return old;
        }
        return result;
    }

    @Override
    public T merge(UUID key, T value, java.util.function.BiFunction<? super T, ? super T, ? extends T> fn) {
        T old = delegate.get(key);
        T result = delegate.merge(key, value, fn);
        if (result == null && blocked(old)) {
            delegate.put(key, old);
            return old;
        }
        return result;
    }
}
