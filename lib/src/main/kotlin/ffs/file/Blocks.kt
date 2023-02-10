package ffs.file

import ffs.file.binary.*
import kotlin.math.min
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel;

internal class Blocks(
    val fileChan: FileChannel,
    // Index of the block containing
    // bitmap, where we store info
    // regarding which blocks are free
    val bitmapIndex: Int,
    // Index of the first block from which on
    // data blocks are located
    val firstDataBlock: Int,
    // Max data blocks allowed to be allocated
    // within the dile
    val maxBlocks: Int,
    // Max number of opened memory pages
    val blockCacheSize: Int
) : AutoCloseable {

    companion object {
        val PAGE_SIZE: Int = 4096
    }
    
    private val page: ByteBuffer = getPage(bitmapIndex)
    
    private val size: Int = min(maxBlocks, 4 * page.limit())

    // Simple memory page cache
    private val cache: Cache<Int, ByteBuffer> =
        Cache<Int, ByteBuffer>(blockCacheSize, { getPage(it) })

    fun <T> withBlock(blockIdx: Int, block: (ByteBuffer) -> T): T {
        return cache.withValue(blockIdx) {
            // We need to duplicate page buffers
            // since multiple consumers may read
            // the same page concurrently and thus
            // must have separate offset variables
            block.invoke(it.duplicate())
        }
    }

    fun<T> write(blockIdx: Int, offset: Int, encoder: Encoder<T>, value: T) {
        withBlock(blockIdx) {
            encoder.encode(it.position(offset), value)
        }
    }

    fun<T> read(blockIdx: Int, offset: Int, decoder: Decoder<T>): T {
        return withBlock(blockIdx) {
            decoder.decode(it.position(offset))
        }
    }

    fun allocate(): Int {
        fun findFree(): Int {
            synchronized(this) {
                for (blockIdx in 0..size) {
                    val byteOffset = blockIdx / 8
                    val bitMask: Int = 1 shl (blockIdx % 8)
                    val prevMask = page.get(byteOffset).toInt()
                    if (prevMask and bitMask == 0) {
                        page.put(byteOffset, (prevMask or bitMask).toByte())
                        return firstDataBlock + blockIdx
                    }
                }
                throw IOException("No free inodes left")
            }
        }
        val freeBlock = findFree()
        zeroBlock(freeBlock)
        return freeBlock
    }

    fun free(block: Int): Unit {
        synchronized(this) {
            val blockIdx = block - firstDataBlock
            val byteOffset = blockIdx / 8
            val bitMask: Int = (1 shl (blockIdx % 8)).inv()
            val prevMask = page.get(byteOffset).toInt()
            page.put(byteOffset, (prevMask and bitMask).toByte())
        }
    }

    private fun getPageNum(): Int {
        return (fileChan.size() / PAGE_SIZE).toInt()
    }
    
    private fun getPage(pageNum: Int): ByteBuffer {
        val pageOffset = (pageNum * PAGE_SIZE).toLong()
        return fileChan.map(FileChannel.MapMode.READ_WRITE, pageOffset, PAGE_SIZE.toLong())
    }
    
    private val zeroes = ByteArray(PAGE_SIZE.toInt())
    
    fun zeroBlock(pageNum: Int): Unit {
        withBlock(pageNum) {
            it.put(zeroes)
        }
    }
    
    override fun close(): Unit {
        fileChan.close()
    }

}
