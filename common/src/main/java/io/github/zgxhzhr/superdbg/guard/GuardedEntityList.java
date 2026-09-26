package io.github.zgxhzhr.superdbg.guard;

import com.google.common.collect.ForwardingList;
import io.github.zgxhzhr.superdbg.entity.RemovalGuard;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

/**
 * 守卫版实体列表：包装 EntitySection 内 ClassInstanceMultiMap 的 byClass 各列表
 * 及 allInstances。反射遍历这些 List 调 {@code remove(entity)} 的字段级删除，
 * 经本包装时被门禁拦截。
 */
public final class GuardedEntityList<T> extends ForwardingList<T> {

    private final List<T> delegate;

    public GuardedEntityList(List<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    protected List<T> delegate() {
        return delegate;
    }

    private boolean blocked(Object value) {
        return value instanceof net.minecraft.world.entity.Entity entity
                && RemovalGuard.isContainerDeleteBlocked(entity);
    }

    @Override
    public boolean remove(Object element) {
        if (blocked(element)) {
            return true;
        }
        return delegate.remove(element);
    }

    @Override
    public T remove(int index) {
        T old = delegate.get(index);
        if (blocked(old)) {
            return old;
        }
        return delegate.remove(index);
    }

    @Override
    public boolean removeAll(Collection<?> collection) {
        boolean changed = false;
        for (Object o : collection) {
            if (!blocked(o)) {
                changed |= delegate.remove(o);
            }
        }
        return changed;
    }

    @Override
    public boolean retainAll(Collection<?> collection) {
        return delegate.removeIf(o -> !collection.contains(o) && !blocked(o));
    }

    @Override
    public boolean removeIf(java.util.function.Predicate<? super T> predicate) {
        if (RemovalGuard.isBypassing()) {
            return delegate.removeIf(predicate);
        }
        boolean changed = false;
        Iterator<T> it = delegate.iterator();
        while (it.hasNext()) {
            T o = it.next();
            if (predicate.test(o) && !blocked(o)) {
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
        delegate.removeIf(o -> !blocked(o));
    }

    @Override
    public Iterator<T> iterator() {
        return wrap(super.iterator());
    }

    @Override
    public ListIterator<T> listIterator() {
        return wrapList(super.listIterator());
    }

    @Override
    public ListIterator<T> listIterator(int index) {
        return wrapList(super.listIterator(index));
    }

    private Iterator<T> wrap(final Iterator<T> base) {
        return new Iterator<>() {
            protected T last;

            @Override
            public boolean hasNext() {
                return base.hasNext();
            }

            @Override
            public T next() {
                return last = base.next();
            }

            @Override
            public void remove() {
                if (!blocked(last)) {
                    base.remove();
                }
            }

            @Override
            public void forEachRemaining(java.util.function.Consumer<? super T> action) {
                base.forEachRemaining(action);
            }
        };
    }

    private ListIterator<T> wrapList(final ListIterator<T> base) {
        return new ListIterator<>() {
            private T last;

            @Override public boolean hasNext() { return base.hasNext(); }
            @Override public T next() { return last = base.next(); }
            @Override public boolean hasPrevious() { return base.hasPrevious(); }
            @Override public T previous() { return last = base.previous(); }
            @Override public int nextIndex() { return base.nextIndex(); }
            @Override public int previousIndex() { return base.previousIndex(); }
            @Override public void remove() {
                if (!blocked(last)) {
                    base.remove();
                }
            }
            @Override public void set(T t) { base.set(t); }
            @Override public void add(T t) { base.add(t); }
        };
    }
}
