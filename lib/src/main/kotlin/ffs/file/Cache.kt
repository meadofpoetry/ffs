package ffs.file

import kotlin.collections.*
import kotlin.synchronized

/**
 * Simple Clock cache
 */
internal class Cache<Key, V>(val size: Int, val populate: (Key) -> V) {

    private data class Node<K, V>(
        var k: K?,
        var v: V?,
        var locked: Boolean,
        var used: Boolean
    )

    private fun <K, V>emptyNode(): Node<K, V> {
        return Node<K, V>(null, null, false, false)
    }
    
    private val nodes: List<Node<Key, V>> = List<Node<Key, V>>(size, { emptyNode() })
    private val table: MutableMap<Key, Node<Key, V>> = mutableMapOf()

    private fun getLockedNode(key: Key): Node<Key, V> {
        synchronized(this) {
            if (table.contains(key)) {
                val node = table.get(key)!!
                node.used = true
                return node
            }

            // eviction (if needed)
            var i = 0
            while (true) {
                val node = nodes[i]
                i = (i + 1) % size
                if (node.k == null) {
                    break
                } else if (node.locked) {
                    continue
                } else if (node.used) {
                    node.used = false
                } else {
                    if (node.k != null) {
                        table.remove(node.k)
                    }
                    node.k = null
                    node.v = null
                    break
                }
            }

            for (node in nodes) {
                if (node.k != null || node.locked) {
                    continue
                }

                node.k = key
                node.v = populate(key)
                node.used = true
                table[key] = node
                return node
            }

            throw Throwable("key not found")
        }
    }

    private fun unlockNode(node: Node<Key, V>): Unit {
        synchronized(this) {
            node.locked = false
        }
    }
    
    fun <T> withValue(key: Key, block: (V) -> T): T {
        val node = getLockedNode(key)
        try {
            return block.invoke(node.v!!)
        } finally {
            unlockNode(node)
        }
    }
    
}
