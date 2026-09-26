package io.github.zgxhzhr.superdbg.guard;

import io.github.zgxhzhr.superdbg.entity.RemovalGuard;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectSet;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * 守卫版 Int2ObjectMap：包装 EntityLookup.byId / EntityTickList.active 等
 * 以实体 int ID 为键的 fastutil 映射。
 * <p>
 * 删除操作（{@link #remove(int)} / {@link #remove(Object)} / {@link #clear} /
 * compute / merge 等）落到守卫实体上时，若调用栈含非白名单调用方
 * （例如反射 {@code active.remove(id)} 的字段级删除），拒绝删除并
 * 假装成功（返回旧值），使外部无法感知门禁存在。
 * 非守卫实体、编辑器强制移除（FORCE_REMOVE）、本模组绕过（BYPASS）、
 * 原版/Forge/TLM 白名单流程一律透传，零额外开销（不扫描调用栈）。
 */
public final class GuardedInt2ObjectMap<V> implements Int2ObjectMap<V> {

    private final Int2ObjectMap<V> delegate;

    public GuardedInt2ObjectMap(Int2ObjectMap<V> delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    /** 门禁：值为守卫实体且当前删除不被允许时返回 true */
    private boolean blocked(V value) {
        return value instanceof net.minecraft.world.entity.Entity entity
                && RemovalGuard.isContainerDeleteBlocked(entity);
    }

    @Override
    public V remove(int key) {
        V old = delegate.get(key);
        if (blocked(old)) {
            return old; // 假装删除成功，实际保留
        }
        return delegate.remove(key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public V remove(Object key) {
        if (key instanceof Integer k) {
            return remove(k.intValue());
        }
        return delegate.remove(key);
    }

    @Override
    public boolean remove(int key, Object value) {
        V old = delegate.get(key);
        if (Objects.equals(old, value) && blocked(old)) {
            return true; // 假装删除成功
        }
        return delegate.remove(key, value);
    }

    @Override
    public void clear() {
        if (RemovalGuard.isBypassing()) {
            delegate.clear();
            return;
        }
        // 非绕过上下文：逐个删除，守卫实体保留
        var it = delegate.int2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            Entry<V> e = it.next();
            if (!blocked(e.getValue())) {
                it.remove();
            }
        }
    }

    @Override
    public V computeIfAbsent(int key, java.util.function.IntFunction<? extends V> mappingFunction) {
        V v = delegate.get(key);
        if (v != null) return v;
        return delegate.computeIfAbsent(key, mappingFunction);
    }

    @Override
    public V computeIfPresent(int key, BiFunction<? super Integer, ? super V, ? extends V> remappingFunction) {
        V old = delegate.get(key);
        V result = delegate.computeIfPresent(key, remappingFunction);
        if (result == null && blocked(old)) {
            // remapping 试图删除守卫实体：放回
            delegate.put(key, old);
            return old;
        }
        return result;
    }

    @Override
    public V merge(int key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        V old = delegate.get(key);
        V result = delegate.merge(key, value, remappingFunction);
        if (result == null && blocked(old)) {
            delegate.put(key, old);
            return old;
        }
        return result;
    }

    // ---- 纯委托方法 ----

    @Override public int size() { return delegate.size(); }
    @Override public boolean isEmpty() { return delegate.isEmpty(); }
    @Override public boolean containsKey(int key) { return delegate.containsKey(key); }
    @Override public boolean containsKey(Object key) { return delegate.containsKey(key); }
    @Override public boolean containsValue(Object value) { return delegate.containsValue(value); }
    @Override public V get(int key) { return delegate.get(key); }
    @Override public V get(Object key) { return delegate.get(key); }
    @Override public V getOrDefault(int key, V defaultValue) { return delegate.getOrDefault(key, defaultValue); }
    @Override public V getOrDefault(Object key, V defaultValue) { return delegate.getOrDefault(key, defaultValue); }
    @Override public V put(int key, V value) { return delegate.put(key, value); }
    @Override public V put(Integer key, V value) { return delegate.put(key, value); }
    @Override public V putIfAbsent(int key, V value) { return delegate.putIfAbsent(key, value); }
    @Override public V putIfAbsent(Integer key, V value) { return delegate.putIfAbsent(key, value); }
    @Override public V replace(int key, V value) { return delegate.replace(key, value); }
    @Override public V replace(Integer key, V value) { return delegate.replace(key, value); }
    @Override public boolean replace(int key, V oldValue, V newValue) { return delegate.replace(key, oldValue, newValue); }
    @Override public boolean replace(Integer key, V oldValue, V newValue) { return delegate.replace(key, oldValue, newValue); }
    @Override public V compute(int key, BiFunction<? super Integer, ? super V, ? extends V> remappingFunction) {
        return delegate.compute(key, remappingFunction);
    }
    @Override public V compute(Integer key, BiFunction<? super Integer, ? super V, ? extends V> remappingFunction) {
        return delegate.compute(key, remappingFunction);
    }
    @Override public V merge(Integer key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        return delegate.merge(key, value, remappingFunction);
    }
    @Override public void putAll(Map<? extends Integer, ? extends V> m) { delegate.putAll(m); }
    @Override public ObjectSet<Entry<V>> int2ObjectEntrySet() { return delegate.int2ObjectEntrySet(); }
    @Override public IntSet keySet() { return delegate.keySet(); }
    @Override public ObjectCollection<V> values() { return delegate.values(); }
    @Override public ObjectSet<Map.Entry<Integer, V>> entrySet() { return delegate.entrySet(); }
    @Override public void defaultReturnValue(V rv) { delegate.defaultReturnValue(rv); }
    @Override public V defaultReturnValue() { return delegate.defaultReturnValue(); }
    @Override public boolean equals(Object o) { return delegate.equals(o); }
    @Override public int hashCode() { return delegate.hashCode(); }
    @Override public String toString() { return delegate.toString(); }
}
