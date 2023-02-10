package ffs.file

import java.util.concurrent.CyclicBarrier 
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.test.*
import ffs.file.Fs
import ffs.file.Inode.Companion.Type
import ffs.Fs.Companion.Flags

class FsTest {

    private val fsIdx = AtomicInteger(0)

    private fun nextFsIdx(): Int {
        return fsIdx.getAndAdd(1)
    }

    private data class Entry(val type: Type, val path: String, val data: String? = null) {
        fun nioPath(): Path = Paths.get(path)
    }
    
    private fun withFS(layout: List<Entry>, block: (Fs) -> Unit) {
        val fsPath = Files.createTempFile("ffs-test", nextFsIdx().toString())
        Fs.create(fsPath).use {
            for (f in layout) {
                when (f.type) {
                    Type.Dir -> it.makeDir(f.nioPath())
                    Type.File -> {
                        it.open(f.nioPath(), Flags.RW, create = true).use {
                            if (f.data != null) {
                                it.write(f.data.toByteArray())
                            }
                        }
                    }
                    else -> throw Throwable("Bad FS layout: $layout")
                }
            }
            block.invoke(it)
        }
    }

    private val baseLayout: List<Entry> = listOf(
        Entry(Type.Dir, "/a"),
        Entry(Type.Dir, "/b"),
        Entry(Type.Dir, "/c"),
        Entry(Type.File, "/a/file", "Hello, World!\n"),
    )

    @Test
    fun openFSTest() {
        val fsPath = Files.createTempFile("ffs-test", null)
        
        Fs.create(fsPath).use {
            it.makeDir(Paths.get("/test"))
        }
        
        val dir = Fs.open(fsPath).use {
            val dir = it.open(Paths.get("/test"), Flags.RO)
            assertTrue(dir.isDir(), "should be able to reopen fs file and read it's layout")
            dir
        }

        assertFailsWith<IOException>(message = "should not be able to read after FS was closed") {
            dir.modifiedAt()
        }
    }

    @Test
    fun openAndReadTest() {
        withFS(baseLayout) {
            val expectedString = "Hello, World!\n"
            
            val buf = ByteArray(expectedString.toByteArray().size)
            val file = it.open(Paths.get("/a/file"), Flags.RO)
            
            assertTrue(file.isFile(), "should be able to open a file in existing FS")

            file.read(buf)
            val s = String(buf)
            
            assertTrue(s == expectedString, "should be able to read RO file")
            
            file.seek(file.size())
            val read = file.read(buf)

            assertTrue(read == 0, "should read 0 if at the end of file")
            
            file.close()

            assertFailsWith<IOException>(message = "should not be able to read after close") {
                file.read(buf)
            }
        }
    }

    @Test
    fun openAndModifyTest() {
        withFS(baseLayout) {
            val appendedString = "Hello, World!\n"
            val expectedString = "Hello, World!\nHello, World!\n"
            
            val buf = ByteArray(expectedString.toByteArray().size)
            val file = it.open(Paths.get("/a/file"), Flags.RW)
            
            assertTrue(file.isFile(), "should be able to open a file in existing FS")

            file.seek(file.size())
            val written = file.write(appendedString.toByteArray())

            assertTrue(written == appendedString.toByteArray().size, "should be able to write at the end of the file")

            file.reset()
            file.read(buf)
            
            val s = String(buf)
            
            assertTrue(s == expectedString, "should correctly write at the end of the file")
            assertTrue(file.modifiedAt() > file.createdAt(), "should update modification time properly")

            file.close()

            assertFailsWith<IOException>(message = "should not be able to write after close") {
                file.write(buf)
            }
        }
    }

    @Test
    fun openRWandROTest() {
        withFS(baseLayout) {
            val fileRW = it.open(Paths.get("/a/file"), Flags.RW)

            assertFailsWith<IOException>(message = "should not be able to open file already opened for modification") {
                it.open(Paths.get("/a/file"), Flags.RO)
            }

            fileRW.close()

            val fileRO = it.open(Paths.get("/a/file"), Flags.RO)

            assertTrue(fileRO.isFile(), "should be able to open file after it was closed")
            fileRO.close()
        }
    }

    @Test
    fun concurrentReadTest() {
        withFS(baseLayout) { fs ->
            val expectedString = "Hello, World!\n"
            val bufSize = expectedString.toByteArray().size
            
            val barrier = CyclicBarrier(3)

            val buf1 = ByteArray(bufSize)
            val buf2 = ByteArray(bufSize)
            val buf3 = ByteArray(bufSize)
            
            val t1 = thread() {
                val file = fs.open(Paths.get("/a/file"), Flags.RO)
                barrier.await()
                file.read(buf1)
                file.close()
            }

            val t2 = thread() {
                val file = fs.open(Paths.get("/a/file"), Flags.RO)
                barrier.await()
                file.read(buf2)
                file.close()
            }

            val file = fs.open(Paths.get("/a/file"), Flags.RO)
            barrier.await()
            file.read(buf3)
            file.close()

            t1.join()
            t2.join()

            assertTrue(String(buf1) == expectedString, "should concurrently read file")
            assertTrue(String(buf2) == expectedString, "should concurrently read file")
            assertTrue(String(buf3) == expectedString, "should concurrently read file")
        }
    }

    @Test
    fun copyTest() {
        withFS(baseLayout) {
            val expectedString = "Hello, World!\n"
            val buf1 = ByteArray(expectedString.toByteArray().size)
            val buf2 = ByteArray(expectedString.toByteArray().size)
            
            it.copy(Paths.get("/a"), Paths.get("/c/a_copy"))

            val file1 = it.open(Paths.get("/a/file"), Flags.RW)
            file1.write(expectedString.reversed().toByteArray())
            
            val file2 = it.open(Paths.get("/c/a_copy/file"), Flags.RO)

            file1.reset()
            file1.read(buf1)
            file2.read(buf2)

            file1.close()
            file2.close()
            
            assertTrue(String(buf1) == expectedString.reversed(), "should read modified file")
            assertTrue(String(buf2) == expectedString, "should read copy of the file")
        }
    }

    @Test
    fun moveTest() {
        withFS(baseLayout) {
            val expectedString = "Hello, World!\n"
            val buf = ByteArray(expectedString.toByteArray().size)
            
            it.move(Paths.get("/a"), Paths.get("/c/a_moved"))

            assertFailsWith<NoSuchFileException>(message = "should not be able to open removed file") {
                it.open(Paths.get("/a/file"), Flags.RO)
            }
                        
            val file = it.open(Paths.get("/c/a_moved/file"), Flags.RO)
            file.read(buf)

            file.close()
            
            assertTrue(String(buf) == expectedString, "should read file after move")
        }
    }

    @Test
    fun removeTest() {
        withFS(baseLayout) {
            it.remove(Paths.get("/a"))

            assertFailsWith<NoSuchFileException>(message = "should not be able to open removed dir") {
                it.open(Paths.get("/a"), Flags.RO)
            }

            assertFailsWith<NoSuchFileException>(message = "should not be able to open removed dir contents") {
                it.open(Paths.get("/a/file"), Flags.RO)
            }            
        }
    }

    @Test
    fun inputStreamTest() {
        withFS(baseLayout) {
            val expectedString = "Hello, World!\n"

            val buf = it.newInputStream(Paths.get("/a/file")).use { istream ->
                istream.readAllBytes()
            }

            assertTrue(String(buf) == expectedString, "should read from InputStream")
        }
    }

    @Test
    fun outputStreamTest() {
        withFS(baseLayout) {
            val expectedString = "Hello, World!\n"
            
            it.newOutputStream(Paths.get("/test_ostream"), create = true).use { ostream ->
                ostream.write(expectedString.toByteArray())
            }

            val buf = it.newInputStream(Paths.get("/test_ostream")).use { istream ->
                istream.readAllBytes()
            }
            
            assertTrue(String(buf) == expectedString, "should read from file, written through OutputStream")
        }
    }

    @Test
    fun directoryStreamTest() {
        withFS(baseLayout) {
            val expected = listOf("/a", "/b", "/c")
            
            val paths = it.newDirectoryStream(Paths.get("/")).use { dirStream ->
                dirStream.toList().map { it.toString() }
            }
            
            assertContentEquals(expected, paths, "should read directory contents through DirectoryStream")
        }
    }

}
