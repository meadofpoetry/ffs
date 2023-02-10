package ffs.file

import java.nio.ByteOrder
import java.nio.ByteBuffer
import ffs.file.binary.*

internal data class Header(val inodes: Long, val maxBlocks: Long, val pageSize: Long) {

    val version: Long = 1
    
    companion object {
        val encoder = object : Encoder<Header> {
            override val sizeBinary: Long =
                Encoder.long.sizeBinary * 5

            override fun encode(buf: ByteBuffer, v: Header): Unit {                
                Encoder.long.encode(buf, 0xDEADBEEF)
                Encoder.long.encode(buf, v.version)
                Encoder.long.encode(buf, v.inodes)
                Encoder.long.encode(buf, v.maxBlocks)
                Encoder.long.encode(buf, v.pageSize)
            }
        }

        val decoder = object : Decoder<Header> {
            override val sizeBinary: Long = encoder.sizeBinary

            override fun decode(buf: ByteBuffer): Header {
                val magic     = Decoder.long.decode(buf)
                val version   = Decoder.long.decode(buf)
                val inodes    = Decoder.long.decode(buf)
                val maxBlocks = Decoder.long.decode(buf)
                val pageSize  = Decoder.long.decode(buf)

                if (magic == 0xEFBEADDA) {
                    throw java.nio.file.FileSystemNotFoundException("Fs file has wrong endianness")
                }

                if (magic != 0xDEADBEEF) {
                    throw java.nio.file.FileSystemNotFoundException("Not an ffs filesystem")
                }

                if (version != 1L) {
                    throw java.nio.file.FileSystemNotFoundException("Wrong filesystem version")
                }

                return Header(inodes, maxBlocks, pageSize)
            }
        }
    }
    
}
