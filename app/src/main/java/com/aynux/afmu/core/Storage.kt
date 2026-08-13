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

    /** Suffix of a file still being written; must match afmu::kPartSuffix on the Linux side. */
    private const val PART_SUFFIX = ".afmu-part"

    /** Makes each in-flight partial file unique; see [FileSink]. */
    private val partialIds = java.util.concurrent.atomic.AtomicLong()

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

    /**
     * A [Sink] that can pick up where an interrupted transfer left off.
     *
     * Only used when *this* device is the client pulling a file (PROTOCOL.md §3.3). Incoming
     * uploads deliberately do **not** resume — see [FileSink], whose partial name is unique
     * per sink precisely so two concurrent uploads cannot land in the same file.
     */
    interface ResumableSink : Sink {
        /** Bytes already on disk from an earlier attempt; the `Range` offset to ask for. */
        val existingBytes: Long

        /**
         * Throws away what was there and starts from zero.
         *
         * Needed whenever the answer says the leftover cannot be built on: the peer ignored
         * `Range` and sent the whole file (200), the range it granted does not start where we
         * asked, or the leftover is already as big as the file (416 — stale, from a truncated
         * earlier run against a file that has since changed).
         */
        fun restart()

        /**
         * Commits under the name the peer reported, when it reported one.
         *
         * The partial has to be named before the request goes out — its size *is* the `Range`
         * offset — so it is named from the remote path. The peer's `Content-Disposition` only
         * arrives with the response, by which point the partial already exists. Both of our
         * server implementations derive that header from the same path, so the two agree; a
         * third implementation might not, and the user should see the name its peer chose.
         */
        fun commitAs(preferredName: String?)
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

    /**
     * A resumable destination in the inbox for a file being pulled from [remotePath].
     *
     * Falls back to a non-resumable sink when the inbox is not a directory we can write to
     * ourselves — a MediaStore entry is created fresh each time and has no partial to reopen.
     * The caller sees that as `existingBytes == 0` and simply transfers the whole file.
     */
    fun createResumableInboxSink(
        context: Context,
        prefs: Prefs,
        fileName: String,
        remotePath: String,
    ): ResumableSink {
        val safeName = sanitizeName(fileName)
        val dir = inboxDir(context, prefs)
        if (dir.canWrite()) return ResumableFileSink(dir, safeName, remotePath)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return NonResumable(MediaStoreSink(context, prefs, safeName))
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
        // Named after this sink, not after the target: two uploads of the same name arriving
        // at once both get the same target from [uniqueFile] — it only checks the final name,
        // which does not exist yet — and would then interleave into one partial file and each
        // commit it. Nothing resumes from a server-side partial, so a per-sink name costs
        // nothing (PROTOCOL.md §4.3).
        private val partial =
            File(target.parentFile, "${target.name}.${partialIds.incrementAndGet().toString(16)}$PART_SUFFIX")
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

    /**
     * The resumable counterpart of [FileSink], for files this device is pulling.
     *
     * **The partial's name carries a fingerprint of the remote path, and that is not
     * decoration.** Keyed on the file name alone, two same-named files in different remote
     * directories — or a leftover from a run against a file that has since changed — share
     * one `.afmu-part`. The resume offset is then measured against the wrong bytes, and what
     * lands on disk is a silently corrupt file that reports success. Matches the Linux
     * client's naming (`TransferModel.cpp`), though nothing crosses the wire: it is local.
     */
    private class ResumableFileSink(
        private val dir: File,
        private val safeName: String,
        remotePath: String,
    ) : ResumableSink {

        private val partial = File(dir, "$safeName.${pathFingerprint(remotePath)}$PART_SUFFIX")

        init {
            // Two transfers appending to one partial interleave into a file that is the right
            // length and the wrong content. Nothing downstream could detect that.
            synchronized(activeParts) {
                if (!activeParts.add(partial.absolutePath)) {
                    throw IOException("already downloading into ${partial.name}")
                }
            }
        }

        override var existingBytes: Long = if (partial.isFile) partial.length() else 0L
            private set

        private var out: OutputStream = FileOutputStream(partial, existingBytes > 0)
        private var committed = false
        private var released = false

        /** Only known after [commit] — [uniqueFile] may have had to pick another name. */
        private var finalPath: String? = null

        override val stream: OutputStream get() = out
        override val displayPath: String get() = finalPath ?: File(dir, safeName).absolutePath

        override fun restart() {
            if (existingBytes == 0L) return
            runCatching { out.close() }
            partial.delete()
            existingBytes = 0L
            out = FileOutputStream(partial, false)
        }

        override fun commit() = commitAs(null)

        override fun commitAs(preferredName: String?) {
            out.flush()
            out.close()
            val name = preferredName?.let { sanitizeName(it) }?.takeIf { it.isNotBlank() } ?: safeName
            // Resolved now rather than at construction: the name may have been taken while
            // this transfer was in flight, and the partial is what holds our claim, not the
            // final name.
            val target = uniqueFile(dir, name)
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            committed = true
            finalPath = target.absolutePath
        }

        /**
         * **Keeps the partial on failure** — the opposite of [FileSink], and the entire point:
         * the bytes already fetched are what the next attempt resumes from. A partial that
         * never gets resumed is bounded by the file it belongs to and visible in the inbox,
         * which beats re-fetching a gigabyte because the Wi-Fi blinked.
         */
        override fun close() {
            if (!released) {
                synchronized(activeParts) { activeParts.remove(partial.absolutePath) }
                released = true
            }
            if (committed) return
            runCatching { out.close() }
        }
    }

    /**
     * Wraps a sink that has no partial to resume from, so callers need only one type.
     *
     * [commitAs] ignores the peer's name: a MediaStore row is named when it is created, which
     * is before the response arrives. Renaming it afterwards would be a second write for a
     * name that, against either of our own server implementations, is the one it already has.
     */
    private class NonResumable(private val inner: Sink) : ResumableSink {
        override val existingBytes: Long get() = 0L
        override fun restart() = Unit
        override val stream: OutputStream get() = inner.stream
        override val displayPath: String get() = inner.displayPath
        override fun commit() = inner.commit()
        override fun commitAs(preferredName: String?) = inner.commit()
        override fun close() = inner.close()
    }

    /** 8 hex characters of SHA-1 over the remote path; see [ResumableFileSink]. */
    private fun pathFingerprint(remotePath: String): String =
        java.security.MessageDigest.getInstance("SHA-1")
            .digest(remotePath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(8)

    /** Partial paths being written right now; see [ResumableFileSink]. */
    private val activeParts = HashSet<String>()

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
