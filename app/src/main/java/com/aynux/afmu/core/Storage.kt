package com.aynux.afmu.core

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Maps the phone's storage into the flat, absolute-path namespace the transfer protocol
 * uses, and hides the three different ways Android lets an app write a file.
 */
object Storage {

    data class Root(val name: String, val dir: File)

    /** Browsable roots. The empty path "/" lists exactly these. */
    fun roots(context: Context): List<Root> {
        val out = LinkedHashMap<String, Root>()

        val primary = Environment.getExternalStorageDirectory()
        if (primary != null && primary.canRead()) {
            out[primary.absolutePath] = Root("Internal storage", primary)
        }

        // Removable volumes: derive "/storage/XXXX-XXXX" from the app-private dir on it.
        for (appDir in context.getExternalFilesDirs(null)) {
            if (appDir == null) continue
            val volume = appDir.absolutePath.substringBefore("/Android/data/", "")
            if (volume.isEmpty() || volume == primary?.absolutePath) continue
            val f = File(volume)
            if (f.canRead()) out[f.absolutePath] = Root("SD card (${f.name})", f)
        }

        // Always available, needs no permission at all.
        context.getExternalFilesDir(null)?.let {
            it.mkdirs()
            out[it.absolutePath] = Root("App folder (always writable)", it)
        }
        return out.values.toList()
    }

    /** True when the app can browse the whole shared storage tree. */
    fun hasFullAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Resolves a client-supplied path, refusing anything that escapes a root — this is the
     * only thing standing between the token holder and the rest of the filesystem.
     */
    fun resolve(context: Context, path: String): File? {
        if (path.isBlank() || path == "/") return null
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        val allowed = roots(context).any { root ->
            val rootPath = runCatching { root.dir.canonicalPath }.getOrNull() ?: return@any false
            candidate.absolutePath == rootPath || candidate.absolutePath.startsWith("$rootPath/")
        }
        return if (allowed) candidate else null
    }

    /**
     * True when [file] is a browsable root itself. Deleting a root would wipe the whole
     * volume in one request, so [HttpServer] refuses it outright.
     */
    fun isRoot(context: Context, file: File): Boolean {
        val target = runCatching { file.canonicalPath }.getOrNull() ?: return false
        return roots(context).any { runCatching { it.dir.canonicalPath }.getOrNull() == target }
    }

    /** Where files pushed from the PC land. */
    fun inboxDir(context: Context, prefs: Prefs): File {
        val shared = File(Environment.getExternalStorageDirectory(), prefs.inboxDir)
        if (shared.isDirectory || shared.mkdirs()) {
            if (shared.canWrite()) return shared
        }
        return File(context.getExternalFilesDir(null), "inbox").apply { mkdirs() }
    }

    /** A destination for an incoming file, plus the label to show the user afterwards. */
    interface Sink : AutoCloseable {
        val stream: OutputStream
        val displayPath: String
        fun commit()
    }

    fun createInboxSink(context: Context, prefs: Prefs, fileName: String): Sink {
        val safeName = sanitizeName(fileName)
        val dir = inboxDir(context, prefs)
        if (dir.canWrite()) {
            val target = uniqueFile(dir, safeName)
            return FileSink(target)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return MediaStoreSink(context, prefs, safeName)
        }
        throw IOException("no writable destination for $safeName")
    }

    /** A destination inside an explicit directory (used by the browse-and-upload UI). */
    fun createFileSink(dir: File, fileName: String, overwrite: Boolean): Sink {
        val safeName = sanitizeName(fileName)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.absolutePath}")
        val target = if (overwrite) File(dir, safeName) else uniqueFile(dir, safeName)
        return FileSink(target)
    }

    private class FileSink(private val target: File) : Sink {
        private val partial = File(target.parentFile, target.name + ".afmu-part")
        private var committed = false
        override val stream: OutputStream = FileOutputStream(partial)
        override val displayPath: String get() = target.absolutePath

        override fun commit() {
            stream.flush()
            stream.close()
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            committed = true
        }

        /** Only a failed transfer gets cleaned up; a committed file must survive. */
        override fun close() {
            if (committed) return
            runCatching { stream.close() }
            partial.delete()
        }
    }

    /** Android 10+ path that needs no storage permission whatsoever. */
    private class MediaStoreSink(
        private val context: Context,
        prefs: Prefs,
        fileName: String,
    ) : Sink {
        private val resolver = context.contentResolver
        private val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeTypeOf(fileName))
                put(MediaStore.Downloads.RELATIVE_PATH, prefs.inboxDir)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        ) ?: throw IOException("MediaStore rejected $fileName")

        private var committed = false

        override val stream: OutputStream =
            resolver.openOutputStream(uri) ?: throw IOException("cannot open $uri")
        override val displayPath: String = "${prefs.inboxDir}/$fileName"

        override fun commit() {
            stream.flush()
            stream.close()
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
            committed = true
        }

        override fun close() {
            if (committed) return
            runCatching { stream.close() }
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (candidate.exists() && i < 10_000) {
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            candidate = File(dir, "$stem ($i)$suffix")
            i++
        }
        return candidate
    }

    /** Strips directory components and characters that break FAT32/exFAT SD cards. */
    fun sanitizeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.replace(Regex("""[\x00-\x1f<>:"|?*]"""), "_").take(200)
        // "." and ".." name the directory itself and its parent; both would resolve to a
        // destination the caller never asked for.
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") "unnamed" else cleaned
    }

    fun mimeTypeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }
}
