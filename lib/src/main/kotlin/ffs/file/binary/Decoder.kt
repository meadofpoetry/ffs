package ffs.file.binary

import java.nio.ByteBuffer
import java.nio.file.attribute.FileTime

interface Decoder<T> {

    val sizeBinary: Long

    fun decode(buf: ByteBuffer): T

    fun<K> map(f: (T) -> K): Decoder<K> {
        val parent = this
        return object : Decoder<K> {
            override val sizeBinary = parent.sizeBinary
            override fun decode(buf: ByteBuffer): K {
                return f(parent.decode(buf))
            }
        }
    }

    companion object {

        val int = object : Decoder<Int> {
            override val sizeBinary: Long = 4
            
            override fun decode(buf: ByteBuffer): Int {
                return buf.getInt()
            }
        }

        val long = object : Decoder<Long> {
            override val sizeBinary: Long = 8
            
            override fun decode(buf: ByteBuffer): Long {
                return buf.getLong()
            }
        }

        val char = object : Decoder<Char> {
            override val sizeBinary: Long = 2
            
            override fun decode(buf: ByteBuffer): Char {
                return buf.getChar()
            }
        }

        val fileTime: Decoder<FileTime> = long.map { FileTime.fromMillis(it) }

        fun fixedString(fieldSize: Long): Decoder<String> {
            return object : Decoder<String> {
                override val sizeBinary: Long =
                    long.sizeBinary + fieldSize
                
                override fun decode(buf: ByteBuffer): String {
                    val stringSize = buf.getLong()

                    val bytes = ByteArray(fieldSize.toInt())

                    buf.get(bytes)

                    return String(bytes, 0, stringSize.toInt())
                }
            }
        }
    }
    
}
