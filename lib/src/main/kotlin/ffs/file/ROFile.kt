package ffs.file

import kotlin.synchronized

internal open class ROFile(
    override val inode: Int,
    override val inodes: Inodes,
    val cleanup: () -> Unit
) : AbstractFile() {

    init {
        inodes.lockRO(inode)
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
        return false
    }

    override fun truncate(): Unit {
        throw UnsupportedOperationException("Read only")
    }

    override fun read(buf: ByteArray): Int {
        return withInode {
            val read = inodes.read(it, pos, buf)
            pos += read
            read
        }
    }

    override fun write(buf: ByteArray): Int {
        throw UnsupportedOperationException("Read only")
    }

    override fun close(): Unit {
        withInode { 
          inodes.unlockRO(it)
          inodes.unref(it)
          cleanup.invoke()
          super.close()
        }
    }
}
