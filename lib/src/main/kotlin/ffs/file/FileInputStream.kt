package ffs.file

import java.io.InputStream

class FileInputStream internal constructor (private val file: ffs.File) : InputStream() {

    override fun available(): Int {
        return file.available()
    }
    
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) {
            return 0
        }
        
        if (off < 0 || len < 0 || b.size - off < len) {
            throw IndexOutOfBoundsException()
        }
        
        val buf = ByteArray(len)
        val read = file.read(buf)
        if (read == 0) {
            return -1
        }
    
        for (i in 0..read-1) {
            b[off + i] = buf[i]
        }
        return read
    }
    
    override fun read(): Int {
        val buf = ByteArray(1)
        if (file.read(buf) == 0) {
            return -1
        }
        return buf.get(0).toInt()
    }
    
    override fun close(): Unit {
        file.close()
    }
    
}
