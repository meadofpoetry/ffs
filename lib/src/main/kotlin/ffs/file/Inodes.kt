package ffs.file

import ffs.file.binary.*
import kotlin.math.min
import kotlin.synchronized
import kotlin.require
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap

/**
 * The core module for managing Inodes
 */
internal class Inodes(val blocks: Blocks, val imapIndices: IntRange, val maxInodes: Int) : AutoCloseable {

    @Volatile
    private var closed = false
    
    private val inodeBlocks: List<Int> = imapIndices.toList()

    private val inodesPerBlock: Int = (Blocks.PAGE_SIZE / Inode.encoder.sizeBinary).toInt()
    
    private val inodesNum: Int = min(maxInodes, inodeBlocks.size * inodesPerBlock)

    private val maxFile = Blocks.PAGE_SIZE * Blocks.PAGE_SIZE / Int.SIZE_BYTES

    private val inodesTable: ConcurrentHashMap<Int, Inode> = ConcurrentHashMap()

    // Allocate Inode of provided type
    fun alloc(type: Inode.Companion.Type): Int {
        synchronized (this) {
            for (idx in 0..inodesNum) {
                val inode = readFromDisk(idx)
                if (inode.isEmpty()) {
                    val newNode      = Inode.empty.copy(
                        type = type,
                        pagePtr = blocks.allocate()
                    )
                    newNode.updateCreatedAndModified()
                    inodesTable[idx] = newNode
                    updateOnDisk(idx)
                    return idx
                }
            }
            throw IOException("Out of inodes")
        }
    }

    // Free inode and its contents, if there are no
    // on-disk and runtime files referring to it.
    private fun maybeFree(nodeIdx: Int): Unit {
        val node = getNode(nodeIdx)
        
        synchronized (node) {
            if (node.nlink != 0 || node.ref != 0) {
                return
            }
            // If directory -- unlink subdirs first
            if (node.isDir()) {
                var childIdx = 0
                while (true) {
                    val dirEntry = readDir(nodeIdx, childIdx)
                    childIdx += 1
                    if (dirEntry == null) {
                        break
                    }
                    unlink(dirEntry.inode)
                }
            }
            // Deallocate blocks
            freeBlocks(node)
            blocks.free(node.pagePtr)
        }
        // Deallocate inode
        updateNode(nodeIdx) {
            it.copy(type = Inode.Companion.Type.Unused)
        }
        updateOnDisk(nodeIdx)
    }

    fun isDir(nodeIdx: Int): Boolean {
        return getNode(nodeIdx).isDir()
    }

    fun getSize(nodeIdx: Int): Int {
        return getNode(nodeIdx).size
    }

    fun getCreatedAt(nodeIdx: Int): FileTime {
        return getNode(nodeIdx).createdAt
    }

    fun getModifiedAt(nodeIdx: Int): FileTime {
        return getNode(nodeIdx).modifiedAt
    }

    // Increment on-disk ref-count
    fun link(nodeIdx: Int): Unit {
        updateNode(nodeIdx) {
            it.nlink += 1
        }
        updateOnDisk(nodeIdx)
    }

    fun unlink(nodeIdx: Int): Unit {
        updateNode(nodeIdx) {
            it.nlink -= 1
        }
        updateOnDisk(nodeIdx)
        maybeFree(nodeIdx)
    }

    // Increment runtime ref-count
    fun ref(nodeIdx: Int): Unit {
        updateNode(nodeIdx) {
            it.ref += 1
        }
    }

    fun unref(nodeIdx: Int): Unit {
        updateNode(nodeIdx) {
            it.ref -= 1
        }
        maybeFree(nodeIdx)
    }

    // Lock inode for read-only operation
    fun lockRO(nodeIdx: Int): Unit {
        updateNode(nodeIdx) {
            if (it.writeLock) {
                throw IOException("file was opened with RW flag somewhere else")
            }
            it.readLock += 1
        }
    }

    fun unlockRO(nodeIdx: Int): Unit {
        updateNode(nodeIdx) {
            it.readLock -= 1
        }
    }

    // Lock inode for read-write operation
    fun lockRW(nodeIdx: Int): Unit {
        updateNode(nodeIdx) {
            if (it.writeLock || it.readLock > 0) {
                throw IOException("file was opened somewhere else")
            }
            it.writeLock = true
        }
    }

    fun unlockRW(nodeIdx: Int): Unit {
        updateNode(nodeIdx) {
            it.writeLock = false
        }
    }

    // Free inode's data blocks
    fun truncate(nodeIdx: Int): Unit {
        val node = getNode(nodeIdx)

        synchronized(node) {
            freeBlocks(node)
            blocks.zeroBlock(node.pagePtr)
        }
    }

    // Create a deep copy of inode, copying all its data pages
    fun copy(nodeIdx: Int): Int {
        val node = getNode(nodeIdx)
        
        synchronized(node) {
            val newNode = alloc(node.type)

            // copy data
            var pageNum = 0
            val buf = ByteArray(Blocks.PAGE_SIZE)
            
            while (true) {
                val offset = pageNum * Blocks.PAGE_SIZE
                val readBytes = read(nodeIdx, offset, buf)
                write(newNode, offset, buf, readBytes)
                if (readBytes < Blocks.PAGE_SIZE) {
                    break
                }
                pageNum += 1
            }
            
            return newNode
        }
    }

    // Write buf to inode data blocks from offset
    fun write(nodeIdx: Int, offset: Int, buf: ByteArray, len: Int? = null): Unit {
        val node = getNode(nodeIdx)

        val size = len ?: buf.size
        
        require(! (offset > node.size))
        require(! (offset + size > maxFile))
        
        var written = 0
        var pos     = offset
        while (written < size) {
            val blockNum = pos / Blocks.PAGE_SIZE
            val bytesInPage = min(size - written, Blocks.PAGE_SIZE - pos % Blocks.PAGE_SIZE)
            mapPage(nodeIdx, blockNum) { blockIdx ->
                blocks.withBlock(blockIdx) {
                    val dstBuf = it.position(pos % Blocks.PAGE_SIZE)
                    for (i in 0..bytesInPage-1) {
                        dstBuf.put(buf[written + i])
                    }
                }
            }

            written += bytesInPage
            pos     += bytesInPage
        }

        if (pos > getNode(nodeIdx).size) {
            updateNode(nodeIdx, { it.size = pos })
        }
        updateNode(nodeIdx, { it.updateModified() })
        updateOnDisk(nodeIdx)
    }

    // Read data from inode data blocks to buf
    fun read(nodeIdx: Int, offset: Int, buf: ByteArray): Int {
        val node = getNode(nodeIdx)
        require(! (offset > node.size))

        val size = min(node.size - offset, buf.size)
        
        var read = 0
        while (read < size) {
            val pos = offset + read
            val blockNum = pos / Blocks.PAGE_SIZE
            val bytesInPage = min(size - read, Blocks.PAGE_SIZE - pos % Blocks.PAGE_SIZE)
            mapPage(nodeIdx, blockNum) { blockIdx ->
                blocks.withBlock(blockIdx) {
                    val srcBuf = it.position(pos % Blocks.PAGE_SIZE)
                    for (i in 0..bytesInPage-1) {
                        buf[read + i] = srcBuf.get()
                    }
                }
            }

            read += bytesInPage
        }
        
        return size
    }

    // Find file entry in directory inode
    fun lookupDir(nodeIdx: Int, name: String): Int {
        val node = getNode(nodeIdx)
        require( getNode(nodeIdx).isDir() )

        synchronized(node) {
            val buf = ByteBuffer.allocate(DirEntry.decoder.sizeBinary.toInt())
            var off = 0
            while (off < node.size) {
                off += read(nodeIdx, off, buf.array())
                val dir = DirEntry.decoder.decode(buf)
                buf.position(0)
                if (dir.inode == 0) {
                    continue
                }
                if (dir.name == name) {
                    return dir.inode
                }
            }
            throw java.nio.file.NoSuchFileException(name)
        }
    }

    // Insert file entry in directory inode
    fun insertDir(nodeIdx: Int, name: String, childIdx: Int): Unit {
        val node = getNode(nodeIdx)
        require( getNode(nodeIdx).isDir() )

        synchronized(node) {
            val buf = ByteBuffer.allocate(DirEntry.decoder.sizeBinary.toInt())
            var off = 0
            while (off < node.size) {
                val readBytes = read(nodeIdx, off, buf.array())
                val dir = DirEntry.decoder.decode(buf)
                buf.position(0)
                if (dir.inode == 0) {
                    break
                }
                if (dir.name == name) {
                    throw java.nio.file.FileAlreadyExistsException(name)
                }
                off += readBytes
            }

            val dir = DirEntry(childIdx, name)
            DirEntry.encoder.encode(buf.position(0), dir)
            write(nodeIdx, off, buf.array())
            link(childIdx)
        }
    }

    fun removeDir(nodeIdx: Int, name: String): Unit {
        val node = getNode(nodeIdx)
        require( getNode(nodeIdx).isDir() )

        var childNode: Int? = null

        synchronized(node) {
            val buf = ByteBuffer.allocate(DirEntry.decoder.sizeBinary.toInt())
            var off = 0
            while (off < node.size) {
                val readBytes = read(nodeIdx, off, buf.array())
                val dir = DirEntry.decoder.decode(buf)
                buf.position(0)
                if (dir.inode != 0 && dir.name == name) {
                    DirEntry.encoder.encode(buf, DirEntry.Companion.empty)
                    write(nodeIdx, off, buf.array())
                    childNode = dir.inode
                    break
                }
                off += readBytes
            }
        }
        if (childNode == null) {
            throw java.nio.file.NoSuchFileException(name)
        }
        unlink(childNode!!)
    }

    /* Returns DirEntry at index for given inode
     * if entry at that index exists, overwise
     * returns null.
     */
    fun readDir(nodeIdx: Int, index: Int): DirEntry? {
        val node = getNode(nodeIdx)
        require( getNode(nodeIdx).isDir() )

        synchronized(node) {
            val buf = ByteBuffer.allocate(DirEntry.decoder.sizeBinary.toInt())
            var dirCount = 0
            var off = 0

            while (off < node.size) {
                off += read(nodeIdx, off, buf.array())
                val dir = DirEntry.decoder.decode(buf)
                buf.position(0)
                if (dir.inode == 0) {
                    continue
                }
                if (dirCount == index) {
                    return dir
                }
                dirCount += 1
            }
            return null
        }
    }

    override fun close(): Unit {
        synchronized(this) {
            closed = true
            blocks.close()
        }
    }

    // Get inode, populating in-memory cache if needed
    private fun getNode(nodeIdx: Int): Inode {
        val node = inodesTable.computeIfAbsent(nodeIdx) {
            readFromDisk(nodeIdx)
        }
        if (closed) {
            throw IOException("FS is closed")
        }
        require(!node.isEmpty())
        return node
    }

    private fun updateNode(nodeIdx: Int, block: (Inode) -> Unit): Unit {
        val node = getNode(nodeIdx)
        synchronized(node) {
            block.invoke(node)
        }
    }

    // Read inode entry from disk bypassing cache
    private fun readFromDisk(nodeIdx: Int): Inode {
        val inodeBlock = inodeBlocks.get(nodeIdx / inodesPerBlock)
        val inodePos   = (nodeIdx % inodesPerBlock) * Inode.decoder.sizeBinary.toInt()
        return blocks.read(inodeBlock, inodePos, Inode.decoder)
    }

    // Flush cached inode to disk
    private fun updateOnDisk(nodeIdx: Int) {
        val node = getNode(nodeIdx)
        synchronized(node) {
            val inodeBlock = inodeBlocks.get(nodeIdx / inodesPerBlock)
            val inodePos   = (nodeIdx % inodesPerBlock) * Inode.decoder.sizeBinary.toInt()
            blocks.write(inodeBlock, inodePos, Inode.encoder, node)
        }
    }

    // Map inode block offset to actual page on disk.
    // blockIdx is a sequentual index of data pages within inode,
    // block callback will recieve actual block index within FS file,
    // corresponding to blockIdx for this inode.
    private fun mapPage(nodeIdx: Int, blockIdx: Int, block: (Int) -> Unit) {
        val node: Inode = getNode(nodeIdx)
        if (node.pagePtr == 0) {
            throw IOException("File is in incoherent state")
        }

        val page = {
            val page = blocks.read(node.pagePtr, blockIdx * Decoder.int.sizeBinary.toInt(), Decoder.int)
            if (page == 0) {
                val newPage = blocks.allocate()
                blocks.write(node.pagePtr, blockIdx * Encoder.int.sizeBinary.toInt(), Encoder.int, newPage)
                newPage
            } else {
                page
            }
        }.invoke()

        block.invoke(page)
    }

    private fun freeBlocks(node: Inode): Unit {
        val occupiedBlocks = (node.size + Blocks.PAGE_SIZE) / Blocks.PAGE_SIZE
        for (blockIdx in 0..occupiedBlocks) {
            val page = blocks.read(node.pagePtr, blockIdx * Decoder.int.sizeBinary.toInt(), Decoder.int)
            if (page != 0) {
                blocks.free(page)
            }
        }
    }
    
}
