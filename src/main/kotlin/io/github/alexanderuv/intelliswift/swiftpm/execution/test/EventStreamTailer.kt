package io.github.alexanderuv.intelliswift.swiftpm.execution.test

import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Follows a file another process appends lines to, like `tail -f`, and passes each complete line
 * to [onLine]. The file may not exist yet when tailing starts.
 *
 * It polls rather than using a named pipe or file watching, so it behaves the same on macOS,
 * Linux and Windows: Java opens files for reading with sharing allowed, so the writer is never
 * blocked. Bytes are split at `\n` before decoding, so a UTF-8 character split across two reads
 * is decoded whole.
 */
class EventStreamTailer(
    private val file: Path,
    private val onLine: (String) -> Unit,
    private val pollIntervalMs: Long = 50,
) {
    private val lock = Any()
    private var channel: SeekableByteChannel? = null
    private var position = 0L
    private val pending = ByteArrayOutputStream()
    private val buffer = ByteBuffer.allocate(64 * 1024)
    private var future: ScheduledFuture<*>? = null
    private var stopped = false

    fun start() {
        future = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay(::pollSafely, 0, pollIntervalMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Stops following the file after reading what it contains now, including a last line without
     * a line break. Call it once the writer has exited.
     */
    fun stopAndDrain() {
        future?.cancel(false)
        synchronized(lock) {
            if (stopped) return
            stopped = true
            try {
                poll()
            } catch (e: IOException) {
                LOG.warn("Cannot read the Swift Testing event stream $file", e)
            }
            if (pending.size() > 0) emitPending()
            closeChannel()
        }
    }

    /** Stops following the file without reading further. */
    fun stop() {
        future?.cancel(false)
        synchronized(lock) {
            stopped = true
            closeChannel()
        }
    }

    private fun pollSafely() {
        try {
            synchronized(lock) { if (!stopped) poll() }
        } catch (e: IOException) {
            // On Windows the writer may hold the file without read sharing; it is read again later.
            LOG.debug("Cannot read $file", e)
            synchronized(lock) { closeChannel() }
        }
    }

    /** Reads everything appended since the last call. */
    private fun poll() {
        val channel = channel ?: open() ?: return
        if (channel.size() < position) {
            // The file was truncated or replaced: start over.
            position = 0
            pending.reset()
        }
        channel.position(position)
        while (true) {
            buffer.clear()
            val read = channel.read(buffer)
            if (read <= 0) break
            position += read
            buffer.flip()
            while (buffer.hasRemaining()) {
                val b = buffer.get()
                if (b == '\n'.code.toByte()) emitPending() else pending.write(b.toInt())
            }
        }
    }

    private fun emitPending() {
        val line = pending.toString(Charsets.UTF_8).removeSuffix("\r")
        pending.reset()
        if (line.isNotBlank()) onLine(line)
    }

    private fun open(): SeekableByteChannel? = try {
        Files.newByteChannel(file, StandardOpenOption.READ).also { channel = it }
    } catch (_: NoSuchFileException) {
        null
    }

    private fun closeChannel() {
        try {
            channel?.close()
        } catch (e: IOException) {
            LOG.debug(e)
        }
        channel = null
    }

    private companion object {
        val LOG = logger<EventStreamTailer>()
    }
}
