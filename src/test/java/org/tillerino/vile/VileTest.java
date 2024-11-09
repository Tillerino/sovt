package org.tillerino.vile;

import static org.assertj.core.api.Assertions.*;

import java.io.File;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.apache.commons.lang3.SerializationUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VileTest {
    static List<Object> hardReferences = new ArrayList<>();

    @BeforeEach
    void setUp() {
        hardReferences.clear();
        for (int i = 0; i < 100 && !Vile.roots.isEmpty(); i++) {
            // This might not work on the first try, so we retry a few times.
            // Limit for safety.
            forceGc();
            Vile.gc();
        }
        assertThat(Vile.roots).isEmpty();
    }

    @Test
    void root() {
        Vile root1 = Vile.root("root1");
        Vile root1Again = Vile.root("root1");
        Vile root2 = Vile.root("root2");

        assertThat(root1)
                .hasToString("root1")
                .isEqualTo(root1Again)
                .isNotEqualTo(root2)
                .isNotEqualTo(new Object())
                .hasSameHashCodeAs(List.of("root1"))
                .isSameAs(root1Again);

        assertThat(root2).hasToString("root2").isSameAs(Vile.root("root2"));
    }

    @Test
    void blankRootIsNotAllowed() {
        assertThatThrownBy(() -> Vile.root(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Name must not be blank");
        assertThatThrownBy(() -> Vile.root(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Name must not be blank");
    }

    @Test
    void child() {
        Vile root = Vile.root("root");
        Vile child1 = root.child("child1");
        Vile child2 = root.child("child2");

        assertThat(child1)
                .hasToString(join("root", "child1"))
                .isSameAs(root.child("child1"))
                .isNotEqualTo(child2)
                .hasSameHashCodeAs(List.of("root", "child1"))
                .isEqualTo(new Vile(root, "child1"));

        assertThat(child2).hasToString(join("root", "child2")).isSameAs(root.child("child2"));
    }

    @Test
    void blankChildIsNotAllowed() {
        Vile root = Vile.root("root");

        assertThatThrownBy(() -> root.child(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Name must not be blank");
        assertThatThrownBy(() -> root.child(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Name must not be blank");
    }

    @Test
    void cacheProgression() {
        // stage 0: children is null
        Vile root = Vile.root("root");
        assertThat(root.children).isNull();

        // stage 1: children is a SoftReference
        Vile child1 = root.child("child1");
        assertThat(child1).isSameAs(root.child("child1"));
        assertThat(root.children).isInstanceOfSatisfying(SoftReference.class, s -> assertThat(s.get())
                .isSameAs(child1));

        // stage 2: children is a SoftReference[]
        Vile child2 = root.child("child2");
        assertThat(child2).isSameAs(root.child("child2"));
        assertThat(root.children).isInstanceOfSatisfying(SoftReference[].class, a -> {
            assertThat(a[0].get()).isSameAs(child1);
            assertThat(a[1].get()).isSameAs(child2);
        });

        // we keep these in a map, so they cannot be garbage collected
        List<Vile> nextChildren = IntStream.rangeClosed(2, Vile.MAP_THRESHOLD)
                .mapToObj(i -> {
                    Vile child = root.child("child" + i);
                    assertThat(child).isSameAs(root.child("child" + i));
                    return child;
                })
                .toList();
        assertThat(root.children).isInstanceOf(SoftReference[].class);

        // stage 3: children is a Map
        Vile child = root.child("child" + (Vile.MAP_THRESHOLD + 1));
        assertThat(child).isSameAs(root.child("child" + (Vile.MAP_THRESHOLD + 1)));
        assertThat(root.children).isInstanceOfSatisfying(Map.class, a -> {
            assertThat(a).hasSize(Vile.MAP_THRESHOLD + 1);
            IntStream.rangeClosed(1, Vile.MAP_THRESHOLD + 1)
                    .mapToObj(i -> "child" + i)
                    .forEach(name -> assertThat(a.get(name))
                            .as(name)
                            .isInstanceOfSatisfying(Vile.SoftValue.class, v -> assertThat(v.get())
                                    .isSameAs(root.child(name))));
        });
    }

    @Test
    void singleSoftReferenceIsReused() {
        Vile root = Vile.root("root");

        Vile[] child1 = {root.child("child1")};
        assertThat(root.children).isInstanceOfSatisfying(SoftReference.class, s -> assertThat(s.get())
                .isSameAs(child1[0]));
        child1[0] = null;

        forceGc((SoftReference<?>) root.children);

        Vile child2 = root.child("child2");
        assertThat(root.children).isInstanceOfSatisfying(SoftReference.class, s -> assertThat(s.get())
                .isSameAs(child2));
    }

    @Test
    void lastArrayElementIsReused() {
        Vile root = Vile.root("root");

        Vile[] children = {root.child("child1"), root.child("child2"), root.child("child3")};
        assertThat(root.children).isInstanceOfSatisfying(SoftReference[].class, a -> {
            assertThatNullsTrail(a);
            assertThat(a[0].get()).isSameAs(children[0]);
            assertThat(a[1].get()).isSameAs(children[1]);
            assertThat(a[2].get()).isSameAs(children[2]);
        });
        children[2] = null;

        forceGc(((SoftReference<?>[]) root.children)[2]);

        Vile child4 = root.child("child4");
        assertThat(root.children).isInstanceOfSatisfying(SoftReference[].class, a -> {
            assertThatNullsTrail(a);
            assertThat(a[0].get()).isSameAs(children[0]);
            assertThat(a[1].get()).isSameAs(children[1]);
            assertThat(a[2].get()).isSameAs(child4);
        });
    }

    @Test
    void intermediateArrayElementIsReused() {
        Vile root = Vile.root("root");

        Vile[] children = {root.child("child1"), root.child("child2"), root.child("child3")};
        assertThat(root.children).isInstanceOfSatisfying(SoftReference[].class, a -> {
            assertThatNullsTrail(a);
            assertThat(a[0].get()).isSameAs(children[0]);
            assertThat(a[1].get()).isSameAs(children[1]);
            assertThat(a[2].get()).isSameAs(children[2]);
        });
        children[1] = null;

        forceGc(((SoftReference<?>[]) root.children)[1]);

        Vile child4 = root.child("child4");
        assertThat(root.children).isInstanceOfSatisfying(SoftReference[].class, a -> {
            assertThatNullsTrail(a);
            assertThat(a[0].get()).isSameAs(children[0]);
            // array was compacted so child3 moved to index 1
            assertThat(a[1].get()).isSameAs(children[2]);
            assertThat(a[2].get()).isSameAs(child4);
        });
    }

    @Test
    void firstArrayElementIsReused() {
        Vile root = Vile.root("root");

        Vile[] children = {root.child("child1"), root.child("child2"), root.child("child3")};
        assertThat(root.children).isInstanceOfSatisfying(SoftReference[].class, a -> {
            assertThatNullsTrail(a);
            assertThat(a[0].get()).isSameAs(children[0]);
            assertThat(a[1].get()).isSameAs(children[1]);
            assertThat(a[2].get()).isSameAs(children[2]);
        });
        children[0] = null;

        forceGc(((SoftReference<?>[]) root.children)[0]);

        Vile child4 = root.child("child4");
        assertThat(root.children).isInstanceOfSatisfying(SoftReference[].class, a -> {
            assertThatNullsTrail(a);
            assertThat(a[0].get()).isSameAs(children[1]);
            assertThat(a[1].get()).isSameAs(children[2]);
            assertThat(a[2].get()).isSameAs(child4);
        });
    }

    @Test
    void arrayIsCompactedGraduallyOnCacheMiss() {
        Vile root = Vile.root("root");

        Vile[] children = {root.child("child1"), root.child("child2"), root.child("child3")};
        assertThat(root.children).isInstanceOfSatisfying(SoftReference[].class, a -> {
            assertThatNullsTrail(a);
            assertThat(a[0].get()).isSameAs(children[0]);
            assertThat(a[1].get()).isSameAs(children[1]);
            assertThat(a[2].get()).isSameAs(children[2]);
        });
        children[0] = null;
        children[1] = null;

        forceGc(((SoftReference<?>[]) root.children)[0]);
        forceGc(((SoftReference<?>[]) root.children)[1]);

        Vile child4 = root.child("child4");
        assertThat(root.children).isInstanceOfSatisfying(SoftReference[].class, a -> {
            assertThatNullsTrail(a);
            // we only shift by one when compacting, so there is still an empty reference at the front
            assertThat(a[0].get()).isNull();
            assertThat(a[1].get()).isSameAs(children[2]);
            assertThat(a[2].get()).isSameAs(child4);
        });

        Vile child5 = root.child("child5");
        assertThat(root.children).isInstanceOfSatisfying(SoftReference[].class, a -> {
            assertThatNullsTrail(a);
            assertThat(a[0].get()).isSameAs(children[2]);
            assertThat(a[1].get()).isSameAs(child4);
            assertThat(a[2].get()).isSameAs(child5);
        });
    }

    @Test
    void arrayIsCompactedGraduallyOnCacheHit() {
        Vile root = Vile.root("root");

        Vile[] children = {root.child("child1"), root.child("child2"), root.child("child3")};
        assertThat(root.children).isInstanceOfSatisfying(SoftReference[].class, a -> {
            assertThatNullsTrail(a);
            assertThat(a[0].get()).isSameAs(children[0]);
            assertThat(a[1].get()).isSameAs(children[1]);
            assertThat(a[2].get()).isSameAs(children[2]);
        });
        children[0] = null;
        children[1] = null;

        forceGc(((SoftReference<?>[]) root.children)[0]);
        forceGc(((SoftReference<?>[]) root.children)[1]);

        assertThat(root.child("child3")).isSameAs(children[2]);
        assertThat(root.children).isInstanceOfSatisfying(SoftReference[].class, a -> {
            assertThatNullsTrail(a);
            // we only shift by one when compacting, so there is still an empty reference at the front
            assertThat(a[0].get()).isNull();
            assertThat(a[1].get()).isSameAs(children[2]);
            // at the end, there is already a null
            assertThat(a[2]).isNull();
        });

        assertThat(root.child("child3")).isSameAs(children[2]);
        assertThat(root.children).isInstanceOfSatisfying(SoftReference[].class, a -> {
            assertThatNullsTrail(a);
            assertThat(a[0].get()).isSameAs(children[2]);
            // now the array has been fully compacted
            assertThat(a[1]).isNull();
            assertThat(a[2]).isNull();
        });
    }

    @Test
    void mapIsCollected() {
        Vile root = Vile.root("root");

        List<Vile> strongRefs = IntStream.rangeClosed(1, Vile.MAP_THRESHOLD + 1)
                .mapToObj(i -> root.child("child" + i))
                .toList();
        assertThat(root.children).isInstanceOf(Map.class);

        root.child("willBeCollected");
        forceGc(((Map<String, Vile.SoftValue>) root.children).get("willBeCollected"));
        root.child("triggersGc");
        assertThat(((Map<String, Vile.SoftValue>) root.children).size()).isEqualTo(Vile.MAP_THRESHOLD + 2);
    }

    @Test
    void rootSerializationWithoutGc() {
        Vile root = Vile.root("root");
        assertThat(root).isSameAs(Vile.root("root"));

        Vile deserialized = SerializationUtils.deserialize(SerializationUtils.serialize(root));
        assertThat(deserialized).isSameAs(Vile.root("root"));
    }

    @Test
    void rootSerializationWithGc() {
        // care: do not assign to variable, so it can be garbage collected
        byte[] serialized = SerializationUtils.serialize(Vile.root("root"));

        forceGc(Vile.roots.values().iterator().next());
        Vile deserialized = SerializationUtils.deserialize(serialized);
        assertThat(deserialized).hasToString("root").isSameAs(Vile.root("root"));
    }

    @Test
    void childSerializationWithoutGc() {
        Vile child = Vile.get("child");

        Vile deserialized = SerializationUtils.deserialize(SerializationUtils.serialize(child));
        assertThat(deserialized).isSameAs(child);
    }

    @Test
    void childSerializationWithChildGc() {
        Vile root = Vile.root("root");

        // care: do not assign to variable, so it can be garbage collected
        byte[] serialized = SerializationUtils.serialize(root.child("child"));

        forceGc((SoftReference<?>) root.children);
        Vile deserialized = SerializationUtils.deserialize(serialized);
        assertThat(deserialized).hasToString(join("root", "child")).isSameAs(root.child("child"));
    }

    @Test
    void childSerializationWithRootGc() {
        // care: do not assign to variable, so it can be garbage collected
        byte[] serialized = SerializationUtils.serialize(Vile.get("root", "child"));

        forceGc(Vile.roots.values().iterator().next());
        Vile deserialized = SerializationUtils.deserialize(serialized);
        assertThat(deserialized)
                .hasToString(join("root", "child"))
                .isSameAs(Vile.root("root").child("child"));
    }

    @Test
    void deepChildSerialization() {
        Vile grandgrandchild = Vile.get("root", "child", "grandchild", "grandgrandchild");

        Vile deserialized = SerializationUtils.deserialize(SerializationUtils.serialize(grandgrandchild));
        assertThat(deserialized).isSameAs(grandgrandchild);
    }

    static String join(String... names) {
        return String.join(File.separator, names);
    }

    /**
     * It's pretty hard to force a GC, specifically one where soft references are cleared. So we just try to exhaust the
     * memory. With a large heap, this will be very slow, since the memory is cleared on allocation. With a small heap,
     * this is reasonably fast, so pass something like -Xmx64m.
     */
    static void forceGc() {
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory > Integer.MAX_VALUE) {
            throw new IllegalStateException("Too much memory: " + maxMemory);
        }

        try {
            hardReferences.add(new byte[(int) maxMemory / 2]);
            hardReferences.add(new byte[(int) maxMemory / 2]);
            fail("Should have run out of memory");
        } catch (OutOfMemoryError e) {
            // very good
        }
    }

    static void forceGc(SoftReference<?> ref) {
        for (int i = 0; i < 100 && ref.get() != null; i++) {
            forceGc();
        }
        assertThat(ref.get()).isNull();
    }

    static void assertThatNullsTrail(Object[] a) {
        boolean nullEncountered = false;
        for (Object o : a) {
            if (o == null) {
                nullEncountered = true;
            } else if (nullEncountered) {
                assertThat(o).isNotNull();
            }
        }
    }
}
