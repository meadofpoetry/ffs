package ffs.file.binary

import java.nio.ByteBuffer
import java.nio.file.attribute.FileTime

interface Encoder<in T> {

    val sizeBinary: Long

    fun encode(buf: ByteBuffer, v: T): Unit

    fun<K> comap(f: (K) -> T): Encoder<K> {
        val parent = this
        return object : Encoder<K> {
            override val sizeBinary = parent.sizeBinary
            override fun encode(buf: ByteBuffer, v: K): Unit {
                parent.encode(buf, f(v))
            }
        }
    }

    companion object {

        val int = object : Encoder<Int> {
            override val sizeBinary: Long = 4
            
            override fun encode(buf: ByteBuffer, v: Int): Unit {
                buf.putInt(v)
            }
        }

        val long = object : Encoder<Long> {
            override val sizeBinary: Long = 8
            
            override fun encode(buf: ByteBuffer, v: Long): Unit {
                buf.putLong(v)
            }
        }

        val char = object : Encoder<Char> {
            override val sizeBinary: Long = 2
            
            override fun encode(buf: ByteBuffer, v: Char): Unit {
                buf.putChar(v)
            }
        }

        val fileTime: Encoder<FileTime> = long.comap { it.toMillis() }

        fun fixedString(fieldSize: Long): Encoder<String> {
            return object : Encoder<String> {
                override val sizeBinary: Long =
                    long.sizeBinary + fieldSize
                
                override fun encode(buf: ByteBuffer, v: String): Unit {
                    val bytes = v.toByteArray()
                    val stringSize = bytes.size.toLong()
                    
                    if (stringSize > fieldSize) {
                        throw RuntimeException("encoded string size is too big")
                    }
                    buf.putLong(stringSize)
                    buf.put(bytes)
                    for (i in 0..(fieldSize - stringSize - 1)) {
                        buf.put(0)
                    }
                }
            }
        }
    }
}
