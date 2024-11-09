package org.tillerino.vile;

import java.io.*;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.Hashtable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A memory-efficient representation of a file path.
 * <p>
 * Each instance only stores its name and a reference to its parent. Instances are cached in a global tree structure.
 * The tree structure contains soft values such that instances can be garbage collected.
 */
public class Vile implements Serializable {
    /**
     * To save memory, the children of non-root nodes are cached in an array before switching to a map. This is the
     * largest number of children that will be stored in an array.
     */
    public static int MAP_THRESHOLD = 10;

    /**
     * Customizes the map implementation used for caching children of non-root nodes. The default is {@link Hashtable}
     * since it is thread-safe but does not have a large memory overhead. {@link ConcurrentHashMap} has better
     * performance but a larger memory overhead. If your code is single-threaded, you can use {@link java.util.HashMap}
     * instead.
     */
    public static Supplier<Map<String, SoftValue>> MAP_FACTORY = Hashtable::new;

    static Map<String, SoftValue> roots = new ConcurrentHashMap<>();

    private static ReferenceQueue<Vile> queue = new ReferenceQueue<>();

    private Vile parent;
    private String name;

    /**
     * This field stores all children, but to optimize memory consumption, we go in steps: Initially (no children) the
     * field is null. With a single child, the field is a {@link SoftReference}. With two children, the field is an
     * array of soft references. Above 10 children, the field is a full-blown cache
     */
    Object children = null;

    Vile(Vile parent, String name) {
        this.parent = parent;
        this.name = name;
    }

    public static Vile root(String name) {
        validateNotBlank(name);
        gc();
        return computeIfAbsent(roots, null, name);
    }

    private static void validateNotBlank(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name must not be blank");
        }
    }

    static void gc() {
        for (SoftValue r; (r = (SoftValue) queue.poll()) != null; ) {
            if (r.parent != null) {
                ((Map<?, ?>) r.parent.children).remove(r.name);
            } else {
                roots.remove(r.name);
            }
        }
    }

    public static Vile get(String root, String... names) {
        Vile v = root(root);
        for (String s : names) {
            v = v.child(s);
        }
        return v;
    }

    public Vile child(String name) {
        validateNotBlank(name);
        gc();
        synchronized (this) {
            if (children instanceof Map<?, ?> c) {
                // In this case, we can do everything outside the synchronized block.
                // We check this first, so we can exit the synchronized block quickly.
            } else if (children == null) {
                Vile child = new Vile(this, name);
                children = new SoftReference<>(child);
                return child;
            } else if (children instanceof SoftReference<?>) {
                return getChildFromSingleSoftReferenceCache(name);
            } else if (children instanceof SoftReference<?>[]) {
                return getChildFromSoftReferenceArrayCache(name);
            } else {
                throw new IllegalStateException();
            }
        }
        return computeIfAbsent((Map<String, SoftValue>) children, this, name);
    }

    private Vile getChildFromSingleSoftReferenceCache(String name) {
        SoftReference<Vile> s = (SoftReference<Vile>) children;
        Vile child = s.get();
        if (child == null) {
            // soft reference was collected
            child = new Vile(this, name);
            children = new SoftReference<>(child);
            return child;
        }
        if (child.name.equals(name)) {
            return child;
        }

        // We have two children now, so we need to upgrade the cache to an array.
        SoftReference<?>[] a = new SoftReference[MAP_THRESHOLD];
        children = a;
        a[0] = s;
        child = new Vile(this, name);
        a[1] = new SoftReference<>(child);
        return child;
    }

    private Vile getChildFromSoftReferenceArrayCache(String name) {
        SoftReference<Vile>[] a = (SoftReference<Vile>[]) children;

        int freeIndex = -1;
        Vile child = null;
        for (int i = 0; i < a.length; i++) {
            if (a[i] == null) {
                if (freeIndex == -1) {
                    freeIndex = i;
                }
                break;
            }
            Vile v = a[i].get();
            if (v == null) {
                if (freeIndex == -1) {
                    freeIndex = i;
                }
            } else if (child == null && v.name.equals(name)) {
                child = v;
                if (freeIndex == -1) {
                    return child;
                }
            }
            if (freeIndex >= 0 && freeIndex < i) {
                // compact array
                a[freeIndex++] = a[i];
                a[i] = null;
            }
        }
        if (child != null) {
            return child;
        }
        if (freeIndex >= 0) {
            child = new Vile(this, name);
            a[freeIndex] = new SoftReference<>(child);
            return child;
        }

        // Array is full, we need to upgrade to a map.
        Map<String, SoftValue> cache = MAP_FACTORY.get();
        children = cache;
        for (SoftReference<?> s : a) {
            Vile v = (Vile) s.get();
            if (v != null) {
                cache.put(v.name, new SoftValue(v, queue));
            }
        }
        child = new Vile(this, name);
        cache.put(name, new SoftValue(child, queue));
        return child;
    }

    public int length() {
        return parent != null ? parent.length() + 1 : 1;
    }

    @Override
    public String toString() {
        if (parent == null) {
            return name;
        }
        StringBuilder sb = new StringBuilder();
        toStringBuilder(sb);
        return sb.toString();
    }

    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that instanceof Vile v) {
            return Objects.equals(parent, v.parent) && Objects.equals(name, v.name);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return 31 * (parent == null ? 1 : parent.hashCode()) + name.hashCode();
    }

    private void toStringBuilder(StringBuilder sb) {
        if (parent != null) {
            parent.toStringBuilder(sb);
            if (parent.name.charAt(parent.name.length() - 1) != File.separatorChar) {
                sb.append(File.separatorChar);
            }
        }
        sb.append(name);
    }

    static Vile computeIfAbsent(Map<String, SoftValue> cache, Vile parent, String name) {
        // We don't actually use Map#computeIfAbsent because there would be a rare race condition where
        // the value is garbage collected while the code executes.
        // With compute, this is avoided since every thread executes the block and the result is immediately passed
        // to the thread via the "pointer".
        Vile[] v = {null};
        cache.compute(name, (s, r) -> {
            if (r != null && (v[0] = r.get()) != null) {
                return r;
            }
            v[0] = new Vile(parent, s);
            return new SoftValue(v[0], queue);
        });
        return v[0];
    }

    // SERIALIZATION

    @Serial
    private void writeObject(ObjectOutputStream oos) throws IOException {
        oos.writeInt(length());
        writeNameRecursive(oos);
    }

    private void writeNameRecursive(ObjectOutputStream oos) throws IOException {
        if (parent != null) {
            parent.writeNameRecursive(oos);
        }
        oos.writeObject(name);
    }

    @Serial
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        int length = in.readInt();
        if (length > 1) {
            parent = root((String) in.readObject());
            for (int i = 2; i < length; i++) {
                parent = parent.child((String) in.readObject());
            }
        }
        name = (String) in.readObject();
    }

    @Serial
    private Object readResolve() throws ObjectStreamException {
        if (parent == null) {
            return root(name);
        }

        return parent.child(name);
    }

    public static class SoftValue extends SoftReference<Vile> {
        final Vile parent;
        final String name;

        SoftValue(Vile value, ReferenceQueue<Vile> queue) {
            super(value, queue);
            this.parent = value.parent;
            this.name = value.name;
        }
    }
}
