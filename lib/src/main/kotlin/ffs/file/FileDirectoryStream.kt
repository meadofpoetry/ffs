package ffs.file

import java.nio.file.Path
import java.nio.file.DirectoryStream

internal class FileDirectoryStream internal constructor (
    private val path: Path,
    private val file: AbstractFile,
    private val filter: DirectoryStream.Filter<Path>
) : DirectoryStream<Path> {
    
    init {
        if (! file.isDir()) {
            throw IllegalArgumentException("file is not a directory")
        }
    }

    override fun iterator(): MutableIterator<Path> {
        return DirIterator(path, file, filter)
    }

    override fun close(): Unit {
        file.close()
    }

    companion object {

        private class DirIterator(
            val path: Path,
            val file: AbstractFile,
            val filter: DirectoryStream.Filter<Path>,
        ): MutableIterator<Path> {
            var nextPath: Path? = null

            var curPosInDir = 0

            private fun generateNextPath(): Path {
                return file.withInode {
                    var result: Path?
                    
                    while (true) {
                        val entry = file.inodes.readDir(it, curPosInDir)
                        if (entry == null) {
                            throw NoSuchElementException()
                        }
                        curPosInDir += 1
                        val newPath = path.resolve(entry.name)
                        if (filter.accept(newPath)) {
                            result = newPath
                            break
                        }
                    }

                    result!!
                }
            }
            
            override fun hasNext(): Boolean {
                synchronized(this) {
                    if (nextPath != null) {
                        return true
                    }

                    try {
                        nextPath = generateNextPath()
                        return true
                    } catch (e: NoSuchElementException) {
                        return false
                    }
                }
            }

            override fun next(): Path {
                synchronized(this) {
                    if (nextPath != null) {
                        val path = nextPath!!
                        nextPath = null
                        return path
                    }
                    return generateNextPath()
                }
            }

            override fun remove(): Unit {
                throw UnsupportedOperationException("remove")
            }
            
        }
        
    }
    
}
