package ffs.file

import ffs.file.binary.*
import java.time.Instant
import java.nio.ByteBuffer
import java.nio.file.attribute.FileTime

internal data class Inode(
    // On-disk data

    // Inode type, Unused means inode is free
    var type:       Type,
    // Number of linkes from other inodes, representing
    // how many on-disk files refer to this inode
    var nlink:      Int,
    // Size of data
    var size:       Int,
    // Pointer to the meta-page, containing addresses of
    // data pages
    var pagePtr:    Int,
    // Creation timestamp
    var createdAt:  FileTime = FileTime.fromMillis(0),
    // Modification timestamp
    var modifiedAt: FileTime = FileTime.fromMillis(0),
    
    // In-memory fields

    // Number of linkes from runtime objects (e.g. ffs.File)
    var ref: Int = 0,
    // Inode is locked for modification
    var writeLock: Boolean = false,
    // Inode is locked for reading (concurrency allowed)
    var readLock:  Int = 0,
) {

    fun isEmpty(): Boolean = type == Type.Unused

    fun isDir(): Boolean = type == Type.Dir

    fun updateCreatedAndModified(): Unit {
        this.createdAt = FileTime.from(Instant.now())
        this.modifiedAt = this.createdAt
    }
    
    fun updateModified(): Unit {
        this.modifiedAt = FileTime.from(Instant.now())
    }
    
    companion object {

        enum class Type(val repr: Int) {
            Unused(0),
            File(1),
            Dir(2);

            fun toInt(): Int = repr

            companion object {
                fun fromInt(int: Int): Type = when(int) {
                    0 -> Type.Unused
                    1 -> Type.File
                    2 -> Type.Dir
                    else -> throw RuntimeException("Unknown type $int")
                }
            }
        }

        val empty: Inode = Inode(Type.Unused, 0, 0, 0)

        val root: Inode = Inode(Type.Dir, 0, 0, 0)

        val encoder = object : Encoder<Inode> {
            override val sizeBinary: Long =
                4 * Encoder.int.sizeBinary + 2 * Encoder.fileTime.sizeBinary

            override fun encode(buf: ByteBuffer, v: Inode): Unit {
                Encoder.int.encode(buf, v.type.repr.toInt())
                Encoder.int.encode(buf, v.nlink)
                Encoder.int.encode(buf, v.size)
                Encoder.int.encode(buf, v.pagePtr)
                Encoder.fileTime.encode(buf, v.createdAt)
                Encoder.fileTime.encode(buf, v.modifiedAt)
            }
        }

        val decoder = object : Decoder<Inode> {
            override val sizeBinary: Long = encoder.sizeBinary

            override fun decode(buf: ByteBuffer): Inode {
                val typeInt    = Decoder.int.decode(buf)
                val nlink      = Decoder.int.decode(buf)
                val size       = Decoder.int.decode(buf)
                val pagePtr    = Decoder.int.decode(buf)
                val createdAt  = Decoder.fileTime.decode(buf)
                val modifiedAt = Decoder.fileTime.decode(buf)

                val type = Type.fromInt(typeInt)

                return Inode(type, nlink, size, pagePtr, createdAt, modifiedAt)
            }
        }

    }
    
}
