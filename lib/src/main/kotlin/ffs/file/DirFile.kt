package ffs.file

import kotlin.synchronized

internal class DirFile(
    inode: Int,
    inodes: Inodes,
    cleanup: () -> Unit
) : ROFile(inode, inodes, cleanup) {

    override fun isDir(): Boolean {
        return true
    }

    override fun isFile(): Boolean {
        return false
    }

    override fun canRead(): Boolean {
        return false
    }

    override fun read(buf: ByteArray): Int {
        throw UnsupportedOperationException("Can't read directory as regular file")
    }

}
