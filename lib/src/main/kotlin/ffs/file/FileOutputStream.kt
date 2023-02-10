package ffs.file

import java.io.OutputStream

class FileOutputStream internal constructor (private val file: ffs.File) : OutputStream() {

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) {
            return
        }
        
        if (off < 0 || len < 0 || b.size - off < len) {
            throw IndexOutOfBoundsException()
        }
        
        val buf = ByteArray(len)
        for (i in 0..len-1) {
            buf[i] = b[off + i]
        }
        file.write(buf)
    }
    
    override fun write(b: Int): Unit {
        val buf = ByteArray(1)
        buf[0] = b.toByte()
        file.write(buf)
    }
    
    override fun close(): Unit {
        file.close()
    }
    
}
