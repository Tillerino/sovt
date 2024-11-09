# vile

Soft-(V)alued tree of f(ILE)s: Save memory by storing file paths as a globally deduplicated tree.

`Vile` instances have a `Vile` _parent_ and a `String` _name_.
The parent links back to its children forming a tree.
The roots of the tree (instances without a parent) are stored in a static map.
This static map is used for global deduplication.
Parents link to their children through soft references avoiding the memory leak.

`Vile` is thread-safe and implements Java serialization. 