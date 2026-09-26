package io.github.zgxhzhr.superdbg.guard;

import com.google.common.collect.ForwardingSet;
import io.github.zgxhzhr.superdbg.entity.RemovalGuard;

import java.util.Iterator;
import java.util.UUID;

/**
 * 守卫版 UUID 集合：包装 PersistentEntitySectionManager.knownUuids（SRG f_157491_）。
 * 反射直写 {@code knownUuids.remove(uuid)} 经过本包装时按 UUID 反查守卫快照，
 * 守卫实体且非白名单调用方时拒绝。
 */
public final class GuardedUuidSet extends ForwardingSet<UUID> {

    private final java.util.Set<UUID> delegate;

    public GuardedUuidSet(java.util.Set<UUID> delegate) {
        this.delegate = delegate;
    }

    @Override
    protected java.util.Set<UUID> delegate() {
        return delegate;
    }

    @Override
    public boolean remove(Object key) {
        if (key instanceof UUID uuid) {
            if (RemovalGuard.isContainerUuidDeleteBlocked(uuid)) {
                return true; // 假装删除成功
            }
            // 诊断：确认离场流程是否触达 knownUuids.remove（每 UUID 只记一条）
            if (RemovalGuard.isDismissedUuidPublic(uuid) && REMOVE_LOGGED.size() < 4096
                    && REMOVE_LOGGED.add(uuid)) {
                io.github.zgxhzhr.superdbg.Constants.LOG.debug(
                        "[SuperDbg] knownUuids.remove 触达并放行离场 uuid={}", uuid);
            }
        }
        return delegate.remove(key);
    }

    private static final java.util.Set<UUID> REMOVE_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public boolean removeIf(java.util.function.Predicate<? super UUID> predicate) {
        if (RemovalGuard.isBypassing()) {
            return delegate.removeIf(predicate);
        }
        boolean changed = false;
        Iterator<UUID> it = delegate.iterator();
        while (it.hasNext()) {
            UUID uuid = it.next();
            if (predicate.test(uuid) && !RemovalGuard.isContainerUuidDeleteBlocked(uuid)) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public void clear() {
        if (RemovalGuard.isBypassing()) {
            delegate.clear();
            return;
        }
        delegate.removeIf(uuid -> !RemovalGuard.isContainerUuidDeleteBlocked(uuid));
    }

    @Override
    public Iterator<UUID> iterator() {
        final Iterator<UUID> base = delegate.iterator();
        return new Iterator<>() {
            private UUID last;

            @Override
            public boolean hasNext() {
                return base.hasNext();
            }

            @Override
            public UUID next() {
                return last = base.next();
            }

            @Override
            public void remove() {
                if (last != null && RemovalGuard.isContainerUuidDeleteBlocked(last)) {
                    return; // 拒绝
                }
                base.remove();
            }
        };
    }
}
