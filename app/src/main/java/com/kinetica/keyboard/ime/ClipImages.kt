package com.kinetica.keyboard.ime

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
     * Copies the image at [uri] into the cache; its record, or null when it cannot be read, is
     * not an image, is over [MAX_BYTES], or takes longer than [deadlineMs]. Reads another app's
     * provider, so it runs off the main thread, on a thread of its own: a provider that never
     * answers is cut off at the deadline instead of holding that thread.
     *
     * Only `content:` URIs from other apps' providers are read ([readable]). The read runs as the
     * keyboard, so a clip naming the keyboard's own files, or a provider the keyboard reaches only
     * through its own permissions (contacts, messages), would otherwise hand that data to whoever
     * copied it, once the image is pasted into their field.
     */
    fun copy(uri: Uri, mime: String, deadlineMs: Long = DEADLINE_MS): ClipboardHistory.Image? {
        if (!readable(uri, context.packageName)) {
            Log.w(TAG, "copied image refused: ${uri.scheme}://${uri.authority}")
            return null
        }
        val folder = dir
        if (!folder.isDirectory && !folder.mkdirs()) return null
        val tmp = File(folder, "incoming-${Thread.currentThread().id}.tmp")
        val cancel = CancellationSignal()
        val afd = AtomicReference<AssetFileDescriptor?>(null)
        val timedOut = AtomicBoolean(false)
        val watchdog = object : TimerTask() {
            override fun run() {
                timedOut.set(true)
                cancel.cancel()
                // Closing the descriptor wakes a read() blocked on a pipe that never fills.
                try {
                    afd.get()?.close()
                } catch (_: IOException) {
                }
            }
        }
        WATCHDOG.schedule(watchdog, deadlineMs)
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val fd = context.contentResolver.openTypedAssetFileDescriptor(uri, "image/*", null, cancel)
                ?: return null
            afd.set(fd)
            if (timedOut.get()) return null
            fd.createInputStream().use { ins ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES || timedOut.get()) return null
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            if (total == 0L || timedOut.get()) return null
            // Only something that decodes as a picture is kept: a database or a text file
            // labelled image/png never becomes a pasteable "image".
            if (!decodesAsImage(tmp)) {
                Log.w(TAG, "copied image refused: not an image")
                return null
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }.take(32)
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "img"
            val name = "$hash.$ext"
            val file = File(folder, name)
            if (!file.exists() && !tmp.renameTo(file)) return null
            ClipboardHistory.Image(name, mime)
        } catch (e: IOException) {
            Log.w(TAG, if (timedOut.get()) "copied image timed out" else "copied image unreadable", e)
            null
        } catch (e: OperationCanceledException) {
            Log.w(TAG, "copied image timed out")
            null
        } catch (e: SecurityException) {
            // The copying app granted nothing, or took the grant back first.
            Log.w(TAG, "copied image not readable by the keyboard", e)
            null
        } catch (e: RuntimeException) {
            // A provider that throws anything else (IllegalArgument, IllegalState): no image.
            Log.w(TAG, "copied image failed", e)
            null
        } finally {
            watchdog.cancel()
            try {
                afd.get()?.close()
            } catch (_: IOException) {
            }
            tmp.delete()
        }
    }

    private fun decodesAsImage(file: File): Boolean {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        return opts.outWidth > 0 && opts.outHeight > 0
    }

    /** The file of [image], or null once it is gone. */
    fun file(image: ClipboardHistory.Image): File? =
        File(dir, image.name).takeIf { it.isFile }

    /** The content URI a field is granted for [file]. */
    fun uri(file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /** Deletes every image file not named in [keep]. */
    fun sweep(keep: Set<String>) {
        // A copy in flight writes an incoming-*.tmp file; it cleans that up itself.
        dir.listFiles()?.forEach { if (it.name !in keep && !it.name.endsWith(".tmp")) it.delete() }
    }

    companion object {
        private const val TAG = "ClipImages"

        /** The cache folder; res/xml/clip_images.xml serves exactly this one. */
        const val DIR = "clip_images"

        /** Bigger copies are skipped: a field rarely takes more, and the cache stays small. */
        const val MAX_BYTES = 10L * 1024 * 1024

        /** Cuts off copies that run past their deadline; one daemon thread for all of them. */
        private val WATCHDOG by lazy { Timer("kinetica-clip-deadline", true) }

        /** A copy still running after this long is cut off and dropped. */
        const val DEADLINE_MS = 3_000L

        /**
         * Providers the keyboard can read only because of its own permissions (READ_CONTACTS,
         * READ_SMS): a clip naming one is another app asking the keyboard to read for it.
         */
        private val PRIVILEGED_AUTHORITIES = setOf(
            "com.android.contacts", "contacts", "call_log", "sms", "mms", "mms-sms",
            "icc", "com.android.voicemail", "com.android.calendar", "user_dictionary",
        )

        /**
         * True when a copied image at [uri] may be read: a `content:` URI whose provider is
         * neither the keyboard's own ([ownPackage] and its DEV id) nor one in
         * [PRIVILEGED_AUTHORITIES]. `file:`, `android.resource:` and every other scheme are refused.
         */
        fun readable(uri: Uri, ownPackage: String): Boolean = readable(uri.scheme, uri.authority, ownPackage)

        /** [readable] on the parts of a URI, for the JVM tests. */
        fun readable(scheme: String?, authority: String?, ownPackage: String): Boolean {
            if (!"content".equals(scheme, ignoreCase = true)) return false
            val auth = authority?.lowercase()?.substringAfterLast('@') ?: return false
            if (auth.isEmpty()) return false
            // A provider may serve several authorities, separated by ';' in the manifest.
            return auth.split(';').none { a ->
                a in PRIVILEGED_AUTHORITIES ||
                    a.startsWith(ownPackage.lowercase()) ||
                    a.startsWith(ownPackage.lowercase().removeSuffix(".dev"))
            }
        }

        /** Matches the provider in the manifest (the DEV build's id included). */
        fun authority(context: Context) = "${context.packageName}.clipimages"
    }
}
