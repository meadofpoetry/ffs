package ffs.file

import ffs.Fs.Companion.Flags
import kotlin.synchronized
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.DirectoryStream
import java.nio.file.NoSuchFileException

class Fs private constructor (
    private val header: Header,
    private val blocks: Blocks,
    private val inodes: Inodes,
    private val root: Int
) : ffs.Fs, AutoCloseable {

    companion object {

        private data class Layout(
            val inodesPerBlock: Int,
            val inodeBlocksRange: IntRange,
            val blockBitmapBlock: Int,
            val firstDataBlock: Int,
        )

        private fun makeLayout(inodesNum: Int): Layout {
            // Evaluate layout options
            val inodesPerBlock: Int = Blocks.PAGE_SIZE / Inode.encoder.sizeBinary.toInt()
            val inodeBlocks: Int = (inodesNum + inodesPerBlock) / inodesPerBlock
            // Zeroth block is occupied by header, then come the inode blocks
            val inodeBlocksRange = IntRange(1, inodeBlocks + 1)

            // Then comes the block with bits depictin which data blocks are free
            val blockBitmapBlock = inodeBlocksRange.last + 1
            // After that the data blocks come
            val firstDataBlock = blockBitmapBlock + 1

            return Layout(
                inodesPerBlock,
                inodeBlocksRange,
                blockBitmapBlock,
                firstDataBlock
            )
        }

        fun create(path: Path, inodesNum: Int = 512, maxBlocks: Int = 4096): Fs {
            val fileChan = RandomAccessFile(path.toString(), "rwd").getChannel()

            val layout = makeLayout(inodesNum)

            val header = Header(inodesNum.toLong(), maxBlocks.toLong(), Blocks.PAGE_SIZE.toLong())
            val blocks = Blocks(fileChan, layout.blockBitmapBlock, layout.firstDataBlock, maxBlocks, blockCacheSize = 512)

            blocks.zeroBlock(0)
            layout.inodeBlocksRange.forEach { 
              blocks.zeroBlock(it)
            }
            blocks.zeroBlock(layout.blockBitmapBlock)

            blocks.write(0, 0, Header.encoder, header)

            // Create root
            val inodes = Inodes(blocks, layout.inodeBlocksRange, header.inodes.toInt())
            val root = inodes.alloc(Inode.Companion.Type.Dir)
            inodes.link(root)

            return Fs(header, blocks, inodes, root)
        }

        fun open(path: Path): Fs {
            val fileChan = RandomAccessFile(path.toString(), "rwd").getChannel()

            val headerBuffer = ByteBuffer.allocate(Header.decoder.sizeBinary.toInt())
            fileChan.read(headerBuffer)

            val header = Header.decoder.decode(headerBuffer.position(0))
            val layout = makeLayout(header.inodes.toInt())

            val blocks = Blocks(fileChan, layout.blockBitmapBlock, layout.firstDataBlock, header.maxBlocks.toInt(), blockCacheSize = 512)
            // Create root
            val inodes = Inodes(blocks, layout.inodeBlocksRange, header.inodes.toInt())
            val root = 0

            return Fs(header, blocks, inodes, root)
        }

        private val acceptAllFilter = object : DirectoryStream.Filter<Path> {
            override fun accept(path: Path): Boolean {
                return true
            }
        }
    }

    override fun open(path: Path, flags: Flags, create: Boolean): ffs.File {
        isAbsolute(path)
        synchronized(this) {
            lockPathRO(path.parent)
            try {
                
                val node = try {
                    getNode(path)
                } catch (e: NoSuchFileException) {
                    if (!create) {
                        throw e
                    }
                    // If create - allocate a new node
                    // and link it under parent directory
                    val dir = getNode(path.parent)
                    val childNode = inodes.alloc(Inode.Companion.Type.File)
                    linkFile(dir, childNode, path.fileName)
                    childNode
                }
                
                when {
                    flags == Flags.RO && inodes.isDir(node) ->
                        return DirFile(node, inodes, { unlockPathRO(path.parent) })
                    flags == Flags.RW && inodes.isDir(node) ->
                        throw IllegalArgumentException("can't open directory with RW flag")
                    flags == Flags.RO ->
                        return ROFile(node, inodes, { unlockPathRO(path.parent) })
                    flags == Flags.RW ->
                        return RWFile(node, inodes, { unlockPathRO(path.parent) })
                    else ->
                        throw Throwable("unreachable")
                }
                // We must unlock the path only if the File
                // wasn't created, otherwise the File.close()
                // will do it for us
            } catch (e: Throwable) {
                unlockPathRO(path.parent)
                throw e
            }
        }
    }

    override fun importExternal(externalPath: Path, internalTarget: Path): Unit {
        isAbsolute(internalTarget)
        if (Files.isDirectory(externalPath)) {
            traversePath(externalPath, internalTarget)
        } else {
            copyFile(externalPath, internalTarget)
        }
    }

    private fun traversePath(src: Path, target: Path) {
        makeDir(target)
        Files.newDirectoryStream(src).use {
            it.forEach {
                if (Files.isDirectory(it)) {
                    traversePath(it, target.resolve(it.fileName))
                } else {
                    copyFile(it, target.resolve(it.fileName))
                }
            }
        }
    }

    private fun copyFile(src: Path, target: Path) {
        val buf = ByteArray(Blocks.PAGE_SIZE)
        open(target, Flags.RW, true).use { output ->
          Files.newInputStream(src).use { input ->
            while (true) {
                val read = input.read(buf)
                output.write(buf)
                if (read < Blocks.PAGE_SIZE) {
                    break
                }
            }
          }
        }
    }
    
    override fun makeDir(path: Path): Unit {
        isAbsolute(path)
        synchronized(this) {
            require(path != path.root)
            val parentNode = getNode(path.parent)
            val child = inodes.alloc(Inode.Companion.Type.Dir)
            linkFile(parentNode, child, path.fileName)
        }
    }

    override fun move(src: Path, dest: Path): Unit {
        isAbsolute(src)
        isAbsolute(dest)
        synchronized(this) {
            val node = getNode(src)

            val srcDirNode = getNode(src.parent)
            val destDirNode = getNode(dest.parent)

            withRWLock(srcDirNode) {
                withRWLock(destDirNode) {
                    // Check if destination exists
                    try {
                        getNode(dest)
                        throw java.nio.file.FileAlreadyExistsException(dest.toString())
                    } catch (e: NoSuchFileException) { }
                    
                    inodes.insertDir(destDirNode, dest.fileName.toString(), node)
                    inodes.removeDir(srcDirNode, src.fileName.toString())
                }
            }
        }
    }

    override fun copy(src: Path, dest: Path): Unit {
        isAbsolute(src)
        isAbsolute(dest)
        synchronized(this) {
            deepCopy(src, dest)
        }
    }
    
    private fun deepCopy(src: Path, dest: Path): Unit {
        val node = getNode(src)
        
        if (inodes.isDir(node)) {
            // Create dest dir and recursively copy src content
            makeDir(dest)
            newDirectoryStream(src).use {
                it.forEach {
                    deepCopy(it, dest.resolve(it.fileName))
                }
            }
        } else {
            // Create a copy of src file and store it in dest dir
            val copyNode = inodes.copy(node)
            val destDirNode = getNode(dest.parent)
            // Check if destination exists
            try {
                getNode(dest)
                throw java.nio.file.FileAlreadyExistsException(dest.toString())
            } catch (e: NoSuchFileException) { }
            inodes.insertDir(destDirNode, dest.fileName.toString(), copyNode)
        }
    }
    
    override fun remove(path: Path): Unit {
        isAbsolute(path)
        synchronized(this) {
            require(path != path.root)
            val parentNode = getNode(path.parent)
            require(inodes.isDir(parentNode))

            withRWLock(parentNode) {
                inodes.removeDir(parentNode, path.fileName.toString())
            }
        }
    }

    override fun newInputStream(path: Path): InputStream {
        synchronized(this) {
            val file = open(path, Flags.RO)
            if (file.isDir()) {
                file.close()
                throw IllegalArgumentException("file is directory")
            }
            return FileInputStream(file)
        }
    }

    override fun newOutputStream(path: Path, create: Boolean): OutputStream {
        synchronized(this) {
            val file = open(path, Flags.RW, create)
            if (file.isDir()) {
                file.close()
                throw IllegalArgumentException("file is directory")
            }
            return FileOutputStream(file)
        }
    }

    override fun newDirectoryStream(dir: Path): DirectoryStream<Path> {
        return newDirectoryStream(dir, Fs.acceptAllFilter)
    }

    override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<Path>): DirectoryStream<Path> {
        synchronized(this) {
            val file = open(dir, Flags.RO) as AbstractFile
            if (!file.isDir()) {
                file.close()
                throw IllegalArgumentException("file is not a directory")
            }
            return FileDirectoryStream(dir, file, filter)
        }
    }

    override fun close(): Unit {
        inodes.close()
    }

    private fun getNode(path: Path): Int {
        var node: Int = root
        
        for (segment in path) {
            node = inodes.lookupDir(node, segment.toString())
        }

        return node
    }

    private fun linkFile(parent: Int, child: Int, name: Path): Unit {
        inodes.insertDir(parent, name.toString(), child)
    }

    private fun <T> withRWLock(inode: Int, block: () -> T): T {
        inodes.lockRW(inode)
        try {
            return block.invoke()
        } finally {
            inodes.unlockRW(inode)
        }
    }

    private fun lockPathRO(path: Path?): Unit {
        if (path == null) {
            return
        }
        
        val iter = path.iterator()
        
        fun lock(node: Int): Unit {
            try {
                inodes.lockRO(node)
                if (iter.hasNext()) {
                    val nextSegment = iter.next().toString()
                    val childNode = inodes.lookupDir(node, nextSegment)
                    lock(childNode)
                }
            } catch (e: Throwable) {
                inodes.unlockRO(node)
                throw e
            }
        }

        lock(root)
    }

    private fun unlockPathRO(path: Path?): Unit {
        if (path == null) {
            return
        }
        
        val iter = path.iterator()
        
        fun unlock(node: Int): Unit {
            if (iter.hasNext()) {
                val nextSegment = iter.next().toString()
                val childNode = inodes.lookupDir(node, nextSegment)
                unlock(childNode)
            }
            inodes.unlockRO(node)
        }

        unlock(root)
    }

    private fun isAbsolute(path: Path): Unit {
        if (! path.isAbsolute()) {
            throw IllegalArgumentException("Path must be absolute")
        }
    }

}
