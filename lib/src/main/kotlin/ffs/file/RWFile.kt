package ffs.file

import kotlin.synchronized

internal class RWFile(
    override val inode: Int,
    override val inodes: Inodes,
    val cleanup: () -> Unit
) : AbstractFile() {

    init {
        inodes.lockRW(inode)
        inodes.ref(inode)
    }

    override fun isDir(): Boolean {
        return false
    }

    override fun isFile(): Boolean {
        return true
    }
    
    override fun canRead(): Boolean {
        return true
    }
    
    override fun canWrite(): Boolean {
        return true
    }

    override fun truncate(): Unit {
        withInode {
            inodes.truncate(it)
            pos = 0
        }
    }
   
    override fun read(buf: ByteArray): Int {
        return withInode {
            val read = inodes.read(it, pos, buf)
            pos += read
            read
        }
    }
    
    override fun write(buf: ByteArray): Int {
        return withInode {
            inodes.write(it, pos, buf)
            pos += buf.size
            buf.size
        }
    }

    override fun close(): Unit {
        withInode {
            inodes.unlockRW(it)
            inodes.unref(it)
            cleanup.invoke()
            super.close()
        }
    }
}
