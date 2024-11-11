# Vile

**Soft-(V)alued tree of f(ILE)s:** Save memory by storing file paths as a globally deduplicated tree.

## Overview

Java's `File` and `Path` classes store the entire path.
This bears a significant memory overhead when describing a collection of files which share common prefixes.
`Vile` stores all paths in a tree, saving memory by deduplicating common prefixes.

## Features

- **Memory Efficient:** The tree of paths is shared across all instances forcing a global deduplication.
  The tree uses soft references to avoid memory leaks.
- **Serializable:** Implements deduplication for basic Java serialization.
- **Thread-Safe**
- **Zero Dependencies**: In fact, it's just a single class.

## Usage

```java
Vile file1 = Vile.get("/home/user", "file1.txt");
file1.toString() // "/home/user/file1.txt"
file1.toFile() // new File("/home/user/file1.txt")
file1.toPath() // Paths.get("/home/user/file1.txt")

Vile parent = file1.parent().get() // "/home/user"
parent.parent() // empty! The path is not parsed and the segments are left as-is.
parent.child("file2.txt") // "/home/user/file2.txt"
```

Nodes are deduplicated where possible:
```java
file1 == Vile.get("/home/user", "file1.txt") // true
Vile file2 = Vile.get("/home/user", "file2.txt");
file1.parent().get() == file2.parent().get() // true
```

This is preserved through serialization:
```java
ByteArrayOutputStream baos = new ByteArrayOutputStream();
ObjectOutputStream oos = new ObjectOutputStream(baos);
oos.writeObject(file1);
oos.close();
ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
ObjectInputStream ois = new ObjectInputStream(bais);
Vile file1Copy = (Vile) ois.readObject();
file1 == file1Copy // true
```


The granularity of the tree is determined by the length of the path segments:
```java
Vile shortFirst = Vile.get("/home", "user/file1.txt");
Vile longFirst = Vile.get("/home/user", "file1.txt");
shortFirst.equals(longFirst) // false!
shortFirst.parent() // "/home"
longFirst.parent() // "/home/user"
```

If you want to match the natural segment structure of the path, use `from(Path)` or `from(File)`:
```java
Vile vile = Vile.from(Path.of("/home/user/file1.txt"));
vile.equals(Vile.get("/home/user", "file1.txt")) // true
vile.parent() // "/home/user"
vile.parent().get().parent() // "/home"
vile.parent().get().parent().get().parent() // "/"
```

## Alternatives

Please 