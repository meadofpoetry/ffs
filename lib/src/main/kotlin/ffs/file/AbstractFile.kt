package ffs.file

import ffs.File
import kotlin.synchronized
import java.io.IOException
import java.nio.file.attribute.FileTime

internal abstract class AbstractFile : File {

    abstract val inode: Int

    abstract val inodes: Inodes

    @Volatile
    private var closed = false

    protected var pos: Int = 0

    internal fun <T> withInode(block: (Int) -> T): T {
        if (closed) {
            throw IOException("file is closed")
        }
        synchronized(this) {
            return block.invoke(inode)
        }
    }

    override fun available(): Int {
        return withInode {
            inodes.getSize(it) - pos
        }
    }

    override fun seek(pos: Int): Unit {
        withInode {
            if (0 > pos || pos > inodes.getSize(it)) {
                throw IndexOutOfBoundsException()
            }
            this.pos = pos
        }
    }
    
    override fun reset(): Unit {
        synchronized(this) {
            pos = 0
        }
    }

    override fun size(): Int {
        return withInode {
            inodes.getSize(it)
        }
    }
    
    override fun createdAt(): FileTime {
        return withInode {
            inodes.getCreatedAt(it)
        }
    }

    override fun modifiedAt(): FileTime {
        return withInode {
            inodes.getModifiedAt(it)
        }
    }

    override fun close(): Unit {
        closed = true
    }

}
