package ffs.file

import java.nio.ByteBuffer
import ffs.file.binary.*

internal data class DirEntry(val inode: Int, val name: String) {
    init {
        require(name.length <= 255)
    }

    companion object {

        val empty = DirEntry(0, "")

        val encoder = object : Encoder<DirEntry> {
            val nameEncoder = Encoder.fixedString(255)
            
            override val sizeBinary: Long =
                Encoder.int.sizeBinary + nameEncoder.sizeBinary

            override fun encode(buf: ByteBuffer, v: DirEntry): Unit {
                Encoder.int.encode(buf, v.inode)
                nameEncoder.encode(buf, v.name)
            }
        }

        val decoder = object : Decoder<DirEntry> {
            val nameDecoder = Decoder.fixedString(255)
            
            override val sizeBinary: Long =
                Decoder.int.sizeBinary + nameDecoder.sizeBinary

            override fun decode(buf: ByteBuffer): DirEntry {
                val inode = Decoder.int.decode(buf)
                val name  = nameDecoder.decode(buf)

                return DirEntry(inode, name)
            }
        }
        
    }
}
