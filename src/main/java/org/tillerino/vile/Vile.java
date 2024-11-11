package org.tillerino.vile;

import java.io.*;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Hashtable;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A memory-efficient representation of a file path.
 *
 * <p>Each instance only stores its name and a reference to its parent. Instances are cached in a global tree structure.
 * The tree structure contains soft values such that instances can be garbage collected.
 *
 * <p>Basic usage:
 *
 * <pre>{@code
 * Vile file1 = Vile.get("/home/user", "file1.txt");
 * file1.toString() // "/home/user/file1.txt"
 * file1.toFile() // new File("/home/user/file1.txt")
 * file1.toPath() // Paths.get("/home/user/file1.txt")
 * Vile parent = file1.parent().get() // "/home/user"
 * parent.parent() // empty! The path is not parsed and the segments are left as-is.
 * parent.child("file2.txt") // "/home/user/file2.txt"
 * }</pre>
 *
 * <p>Nodes are deduplicated where possible:
 *
 * <pre>{@code
 * file1 == Vile.get("/home/user", "file1.txt") // true
 * Vile file2 = Vile.get("/home/user", "file2.txt");
 * file1.parent().get() == file2.parent().get() // true
 * }</pre>
 *
 * <p>This is preserved through serialization:
 *
 * <pre>{@code
 * ByteArrayOutputStream baos = new ByteArrayOutputStream();
 * ObjectOutputStream oos = new ObjectOutputStream(baos);
 * oos.writeObject(file1);
 * oos.close();
 * ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
 * ObjectInputStream ois = new ObjectInputStream(bais);
 * Vile file1Copy = (Vile) ois.readObject();
 * file1 == file1Copy // true
 * }</pre>
 *
 * <p>The granularity of the tree is determined by the length of the path segments:
 *
 * <pre>{@code
 * Vile shortFirst = Vile.get("/home", "user/file1.txt");
 * Vile longFirst = Vile.get("/home/user", "file1.txt");
 * shortFirst.equals(longFirst) // false!
 * shortFirst.parent() // "/home"
 * longFirst.parent() // "/home/user"
 * }</pre>
 *
 * <p>If you want to match the natural segment structure of the path, use {@link #from(Path)} or {@link #from(File)}:
 *
 * <pre>{@code
 * Vile vile = Vile.from(Path.of("/home/user/file1.txt"));
 * vile.equals(Vile.get("/", "home", "user", "file1.txt")) // true
 * vile.parent() // "/home/user"
 * vile.parent().get().parent() // "/home"
 * vile.parent().get().parent().get().parent() // "/"
 * }</pre>
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

    static final Map<String, SoftValue> roots = new ConcurrentHashMap<>();

    private static final ReferenceQueue<Vile> queue = new ReferenceQueue<>();

    private final Vile parent;
    private final String name;

    /**
     * This field stores all children, but to optimize memory consumption, we go in steps: Initially (no children) the
     * field is null. With a single child, the field is a {@link SoftReference}. With two children, the field is an
     * array of soft references. Above 10 children, the field is a full-blown cache
     */
    transient Object children = null;

    Vile(Vile parent, String name) {
        this.parent = parent;
        this.name = name;
    }

    /**
     * Creates or returned the cached root node with the given name. Examples:
     *
     * <pre>{@code
     * Vile root = Vile.root("/");
     * Vile home = Vile.root("/home");
     * Vile user = Vile.root("/home/user");
     * Vile invoices = Vile.root("Documents/invoices");
     * }</pre>
     *
     * <p>The name is not cleaned up in any way and taken exactly as is:
     *
     * <pre>{@code
     * Vile.root("Documents").equals(Vile.root("Documents/")) // false!
     * }</pre>
     *
     * <p>To create a sanitized root node, use a {@link Path} to parse the path:
     *
     * <pre>{@code
     * Vile.from(Path.of("/home/")).equals(Vile.root("/home")) // true
     * }</pre>
     *
     * @param name The path of this root node. This can be any path, absolute or relative, and with any number of
     *     segments. May not be blank.
     * @return A cached node, if possible.
     */
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

    /**
     * Returns a node with the given path. This is equivalent to
     * {@code root(root).child(names[0]).child(names[1]).child(names[2]).child(names[3])...}.
     *
     * @param root The path of the root node. This can be any path, absolute or relative, and with any number of
     *     segments.
     * @param names The relative paths of the child nodes. These can comprise any number of segments. May not be blank.
     * @return Cached instances of the node itself and any parents are returned if possible.
     */
    public static Vile get(String root, String... names) {
        Vile v = root(root);
        for (String s : names) {
            v = v.child(s);
        }
        return v;
    }

    /**
     * Returns a node with the given path. Each segment of the path corresponds to a child node with the root node
     * corresponding to the first segment or the root of the file system, if the path is absolute. Examples:
     *
     * <p>{@code from("Path.of("/home/user/file.txt")"} returns the same node as {@code get("/", "home", "user",
     * "file.txt")}.
     *
     * <p>{@code from("Path.of("Documents/file.txt")"} returns the same node as {@code get("Documents", "file.txt")}.
     *
     * @param path any path
     * @return Cached instances of the node itself and any parents are returned if possible.
     */
    public static Vile from(Path path) {
        Path root = path.getRoot();
        Vile v = root(root != null ? root.toString() : path.getName(0).toString());
        for (int i = root != null ? 0 : 1; i < path.getNameCount(); i++) {
            v = v.child(path.getName(i).toString());
        }
        return v;
    }

    /**
     * Returns a node with the given path. Each segment of the path corresponds to a child node with the root node
     * corresponding to the first segment or the root of the file system, if the path is absolute. Examples:
     *
     * <p>{@code from(new File("/home/user/file.txt")"} returns the same node as {@code get("/", "home", "user",
     * "file.txt")}.
     *
     * <p>{@code from(new File("Documents/file.txt")"} returns the same node as {@code get("Documents", "file.txt")}.
     *
     * @param file any file
     * @return Cached instances of the node itself and any parents are returned if possible.
     */
    public static Vile from(File file) {
        return from(file.toPath());
    }

    /**
     * Returns the child node with the given name. If the child node does not exist, it is created.
     *
     * @param name The sub-path of the child node. This can comprise any number of segments.
     * @return A cached node, if possible.
     */
    public Vile child(String name) {
        validateNotBlank(name);
        gc();
        synchronized (this) {
            if (children instanceof Map<?, ?>) {
                // In this case, we can do everything outside the synchronized block.
                // We check this first, so we can exit the synchronized block quickly.
            } else if (children == null) {
                Vile child = new Vile(this, name);
                children = new SoftReference<>(child);
                return child;
            } else if (children instanceof SoftReference<?>) {
                return getChildFromSingleSoftReferenceCache(name);
            } else {
                return getChildFromSoftReferenceArrayCache(name);
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

    /**
     * Returns the name of this node. This value is not cleaned up in any way and returned exactly as it was passed in
     * to create the node.
     *
     * @return not blank
     */
    public String name() {
        return name;
    }

    /**
     * Returns the parent node of this node.
     *
     * @return empty if this is the root node
     */
    public Optional<Vile> parent() {
        return Optional.ofNullable(parent);
    }

    /**
     * Converts this node to a {@link File}. May throw validation errors from the {@link File} constructor.
     *
     * @return a new instance
     */
    public File toFile() {
        return new File(toString());
    }

    /**
     * Converts this node to a {@link Path}. May throw validation errors from the {@link Path} creator.
     *
     * @return a new instance
     */
    public Path toPath() {
        return Paths.get(toString());
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
