package com.kinetica.keyboard.ime

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Copied images, as files the keyboard can hand to a field (InputConnection.commitContent).
 *
 * The copying app's grant to read its image lasts only while that clip is on the clipboard, so
 * each image is copied into the keyboard's cache when it is copied, and served back through the
 * keyboard's own FileProvider (not exported; a field is granted one image at a time, for as long
 * as it holds it). Files are named by their content, so the same picture copied twice is one
 * file, and [sweep] removes every file [ClipboardHistory] no longer holds. Nothing is ever
 * fetched over a network: the source is a content URI on the phone.
 */
class ClipImages(private val context: Context) {

    private val dir: File get() = File(context.cacheDir, DIR)

    /**
     * Copies the image at [uri] into the cache; its record, or null when it cannot be read or is
     * over [MAX_BYTES]. Reads another app's provider, so it runs off the main thread.
     */
    fun copy(uri: Uri, mime: String): ClipboardHistory.Image? {
        val folder = dir
        if (!folder.isDirectory && !folder.mkdirs()) return null
        val tmp = File(folder, "incoming.tmp")
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { ins ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) return null
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            if (total == 0L) return null
            val hash = digest.digest().joinToString("") { "%02x".format(it) }.take(32)
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "img"
            val name = "$hash.$ext"
            val file = File(folder, name)
            if (!file.exists() && !tmp.renameTo(file)) return null
            ClipboardHistory.Image(name, mime)
        } catch (e: IOException) {
            Log.w(TAG, "copied image unreadable", e)
            null
        } catch (e: SecurityException) {
            // The copying app granted nothing, or took the grant back first.
            Log.w(TAG, "copied image not readable by the keyboard", e)
            null
        } finally {
            tmp.delete()
        }
    }

    /** The file of [image], or null once it is gone. */
    fun file(image: ClipboardHistory.Image): File? =
        File(dir, image.name).takeIf { it.isFile }

    /** The content URI a field is granted for [file]. */
    fun uri(file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /** Deletes every image file not named in [keep]. */
    fun sweep(keep: Set<String>) {
        dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    companion object {
        private const val TAG = "ClipImages"

        /** The cache folder; res/xml/clip_images.xml serves exactly this one. */
        const val DIR = "clip_images"

        /** Bigger copies are skipped: a field rarely takes more, and the cache stays small. */
        const val MAX_BYTES = 10L * 1024 * 1024

        /** Matches the provider in the manifest (the DEV build's id included). */
        fun authority(context: Context) = "${context.packageName}.clipimages"
    }
}
