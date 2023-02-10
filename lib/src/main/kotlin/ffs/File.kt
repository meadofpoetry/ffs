package ffs

import java.nio.file.attribute.FileTime

/**
 * Provides an interface to file abstraction
 * for both files and directories.
 */
interface File : AutoCloseable {

    /**
     * Checks if the file is directory.
     *
     * @return true if file is directory.
     */
    fun isDir(): Boolean

    /**
     * Checks if the file is a regular file.
     */
    fun isFile(): Boolean

    /**
     * Checks if the file can be read. Regular
     * files can be read, but directories can't.
     */
    fun canRead(): Boolean

    /**
     * Checks if the file can be written. Only regular
     * files can be written to if opened with RW flags.
     */
    fun canWrite(): Boolean

    /**
     * Returns current size of the file.
     *
     * @throws  IOException
     *          If underlying FS or file were closed
     */
    fun size(): Int

    /**
     * Returns the timestamp representing when the file
     * was created.
     *
     * @throws  IOException
     *          If underlying FS or file were closed
     */
    fun createdAt(): FileTime

    /**
     * Returns the timestamp representing when the file
     * was modified.
     *
     * @throws  IOException
     *          If underlying FS or file were closed
     */
    fun modifiedAt(): FileTime

    /**
     * Checks how many bytes can be read from current
     * position in the file.
     *
     * @throws  IOException
     *          If underlying FS or file were closed
     */
    fun available(): Int

    /**
     * Resets the current position in the file. Following
     * read() call will read from the beginning.
     */
    fun reset(): Unit

    /**
     * Reads file content from current position on into
     * the provided buffer.
     *
     * @param   buf  Target buffer
     *
     * @return  Number of bytes actually read.
     *
     * @throws  IOException
     *          If underlying FS or file were closed
     */
    fun read(buf: ByteArray): Int

    /**
     * Write buffer contents to file from current position on.
     *
     * @param   buf  Source buffer
     *
     * @return  Number of bytes actually written.
     *
     * @throws  IOException
     *          If underlying FS or file were closed
     */
    fun write(buf: ByteArray): Int

    /**
     * Frees the contents of the file.
     *
     * @throws  IOException
     *          If underlying FS or file were closed
     */
    fun truncate(): Unit

    /**
     * Moves the current position within the file to
     * the provided value.
     *
     * @param   pos   New position within the file.
     *
     * @throws  IOException
     *          If underlying FS or file were closed
     *
     * @throws  IndexOutOfBoundsException
     *          If {code: pos} argument is lesser than zero
     *          or {code: pos} > file size
     */
    fun seek(pos: Int): Unit

}
