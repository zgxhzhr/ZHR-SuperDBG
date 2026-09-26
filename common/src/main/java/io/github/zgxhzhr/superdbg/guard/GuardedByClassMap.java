package io.github.zgxhzhr.superdbg.guard;

import com.google.common.collect.ForwardingMap;

import java.util.List;
import java.util.Map;

/**
 * 守卫版 ClassInstanceMultiMap.byClass 映射：
 * 底层存储不变，所有经 {@link #get} / {@link #computeIfAbsent} / {@link #values} /
 * {@link #entrySet()} 读出的 List 均包装为 {@link GuardedEntityList} 视图。
 * <p>
 * 这样无论通过原方法（ClassInstanceMultiMap.remove）还是反射字段
 * （直接遍历 byClass 映射的 value 调 remove），
 * 删除操作都走守卫门禁；add 操作穿透到底层原始 List，行为零变化。
 */
public final class GuardedByClassMap<T> extends ForwardingMap<Class<?>, List<T>> {

    private final Map<Class<?>, List<T>> delegate;

    public GuardedByClassMap(Map<Class<?>, List<T>> delegate) {
        this.delegate = delegate;
    }

    @Override
    protected Map<Class<?>, List<T>> delegate() {
        return delegate;
    }

    private List<T> wrap(List<T> list) {
        return list == null ? null : new GuardedEntityList<>(list);
    }

    @Override
    public List<T> get(Object key) {
        return wrap(delegate.get(key));
    }

    @Override
    public List<T> getOrDefault(Object key, List<T> defaultValue) {
        List<T> v = delegate.get(key);
        return v == null ? defaultValue : wrap(v);
    }

    @Override
    public List<T> computeIfAbsent(Class<?> key,
                                   java.util.function.Function<? super Class<?>, ? extends List<T>> fn) {
        return wrap(delegate.computeIfAbsent(key, fn));
    }

    @Override
    public List<T> putIfAbsent(Class<?> key, List<T> value) {
        return wrap(delegate.putIfAbsent(key, value));
    }

    @Override
    public java.util.Collection<List<T>> values() {
        return new com.google.common.collect.ForwardingCollection<>() {
            @Override
            protected java.util.Collection<List<T>> delegate() {
                return GuardedByClassMap.this.delegate.values();
            }

            @Override
            public java.util.Iterator<List<T>> iterator() {
                final var base = super.iterator();
                return new java.util.Iterator<>() {
                    @Override public boolean hasNext() { return base.hasNext(); }
                    @Override public List<T> next() { return wrap(base.next()); }
                    @Override public void remove() { base.remove(); }
                };
            }
        };
    }

    @Override
    public java.util.Set<Entry<Class<?>, List<T>>> entrySet() {
        return new com.google.common.collect.ForwardingSet<>() {
            @Override
            protected java.util.Set<Entry<Class<?>, List<T>>> delegate() {
                return GuardedByClassMap.this.delegate.entrySet();
            }

            @Override
            public java.util.Iterator<Entry<Class<?>, List<T>>> iterator() {
                final var base = super.iterator();
                return new java.util.Iterator<>() {
                    @Override public boolean hasNext() { return base.hasNext(); }
                    @Override public Entry<Class<?>, List<T>> next() {
                        Entry<Class<?>, List<T>> e = base.next();
                        return new java.util.AbstractMap.SimpleEntry<>(e.getKey(), wrap(e.getValue())) {
                            @Override
                            public List<T> setValue(List<T> value) {
                                return e.setValue(value);
                            }
                        };
                    }
                    @Override public void remove() { base.remove(); }
                };
            }
        };
    }
}
