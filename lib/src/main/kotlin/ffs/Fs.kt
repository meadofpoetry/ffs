package ffs

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.nio.file.DirectoryStream

interface Fs {

    /**
     * Open file.
     *
     * @param   path     Absolute path within filesystem.
     *
     * @param   flags    Open flags (RO, RW).
     *
     * @param   create   Create file if not exists.
     *
     * @return  File
     *
     * @throws  NoSuchFileException
     *          If {code: not create} and file doesn't exist.
     *
     * @throws  IllegalArgumentException
     *          If provided path not absolute
     *          or RW flag is used with a directory path.
     *
     * @throws  IOException
     *          Internal FS error, or FS was closed.
     */
    fun open(path: Path, flags: Flags, create: Boolean = false): ffs.File

    /**
     * Copy files from external filesystem. E.g.
     * importExternal("C:\\data", "/parent/folder") will create
     * folder directory under parent directory, and recursively
     * copy all the data contents into folder.
     *
     * @param   externalPath   External path FileSystems knows about.
     *
     * @param   internalTarget
     *          Path within file system, last segment of the path
     *          must be a non-existent file name.
     *
     * @throws  FileAlreadyExistsException
     *          internalTarget file already exists.
     *
     * @throws  IllegalArgumentException
     *          If provided internalTarget not absolute.
     *
     * @throws  IOException
     *          Internal FS error, or FS was closed.
     */
    fun importExternal(externalPath: Path, internalTarget: Path): Unit

    /**
     * Makes new directory.
     *
     * @param   path     Absolute path within filesystem.
     *
     * @throws  FileAlreadyExistsException
     *          If directory already exists.
     *
     * @throws  NoSuchFileException
     *          If parent directory doesn't exist.
     *
     * @throws  IllegalArgumentException
     *          If provided paths are not absolute.
     *
     * @throws  IOException
     *          Internal FS error, or FS was closed.
     */
    fun makeDir(path: Path): Unit

    /**
     * Moves data from src to dest. E.g. move("/a", "/a_moved")
     * will effectively rename a to a_moved
     *
     * @param   src     Absolute path of the source file within filesystem.
     *
     * @param   dest    Absolute path of the target file within filesystem.
     *
     * @throws  FileAlreadyExistsException
     *          If target file already exists.
     *
     * @throws  NoSuchFileException
     *          If source file or target's parent directory don't exist.
     *
     * @throws  IllegalArgumentException
     *          If provided paths are not absolute.
     *
     * @throws  IOException
     *          Internal FS error, or FS was closed.
     */
    fun move(src: Path, dest: Path): Unit

    /**
     * Copied data from src to dest. E.g. move("/a", "/a_copied")
     * will create a deep copy of a under /a_copied path.
     *
     * @param   src     Absolute path of the source file within filesystem.
     *
     * @param   dest    Absolute path of the target file within filesystem.
     *
     * @throws  FileAlreadyExistsException
     *          If target file already exists.
     *
     * @throws  NoSuchFileException
     *          If source file or target's parent directory don't exist.
     *
     * @throws  IllegalArgumentException
     *          If provided paths are not absolute.
     *
     * @throws  IOException
     *          Internal FS error, or FS was closed.
     */
    fun copy(src: Path, dest: Path): Unit

    /**
     * Removes the file, located under provided path.
     *
     * @param   patj    Absolute path of the file within filesystem.
     *
     * @throws  NoSuchFileException
     *          If file directory doesn't exist.
     *
     * @throws  IllegalArgumentException
     *          If provided path is not absolute.
     *
     * @throws  IOException
     *          Internal FS error, or FS was closed.
     */
    fun remove(path: Path): Unit

    /**
     * Opens an InputStream for provided path, akin to
     * {code: open(path, Flags.RO, create = false)}
     *
     * @param   path     Absolute path within filesystem.
     *
     * @return  InputStream
     *
     * @throws  NoSuchFileException
     *          If file doesn't exist.
     *
     * @throws  IllegalArgumentException
     *          If provided path not absolute
     *          or file is directory.
     *
     * @throws  IOException
     *          Internal FS error, or FS was closed.
     */
    fun newInputStream(path: Path): InputStream

    /**
     * Opens an OutputStream for provided path, similar to
     * {code: open(path, Flags.RW, create)}
     *
     * @param   path     Absolute path within filesystem.
     *
     * @param   create   Create file if not exists.
     *
     * @return  OutputStream
     *
     * @throws  NoSuchFileException
     *          If {code: not create} and file doesn't exist.
     *
     * @throws  IllegalArgumentException
     *          If provided path not absolute
     *          or file is directory.
     *
     * @throws  IOException
     *          Internal FS error, or FS was closed.
     */
    fun newOutputStream(path: Path, create: Boolean = false): OutputStream

    /**
     * Opens a DirectoryStream for provided path, which allows
     * to iterate over directory's entries.
     *
     * @param   dir      Absolute path within filesystem.
     *
     * @return  DirectoryStream
     *
     * @throws  NoSuchFileException
     *          If file doesn't exist.
     *
     * @throws  IllegalArgumentException
     *          If provided path not absolute
     *          or file is not directory.
     *
     * @throws  IOException
     *          Internal FS error, or FS was closed.
     */
    fun newDirectoryStream(dir: Path): DirectoryStream<Path>

    /**
     * Opens a DirectoryStream for provided path, which allows
     * to iterate over directory's entries.
     *
     * @param   dir      Absolute path within filesystem.
     *
     * @param   filter   DirectoryStream.Filter
     *
     * @return  DirectoryStream
     *
     * @throws  NoSuchFileException
     *          If file doesn't exist.
     *
     * @throws  IllegalArgumentException
     *          If provided path not absolute
     *          or file is not directory.
     *
     * @throws  IOException
     *          Internal FS error, or FS was closed.
     */
    fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<Path>): DirectoryStream<Path>

    companion object {

        enum class Flags {
            RO,
            RW,
        }
        
    }
    
}
