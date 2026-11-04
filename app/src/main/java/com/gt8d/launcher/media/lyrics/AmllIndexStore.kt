package com.gt8d.launcher.media.lyrics

import com.gt8d.launcher.diag.DiagLog
import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One application-wide instance. Construction never performs IO or starts communication. */
class AmllIndexStore(
    private val dir: File,
    private val transport: AmllIndexTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AmllIndexLookup {
    private val mutex = Mutex()
    private val table get() = File(dir, "table.jsonl")
    @Volatile var generation: String? = null
        private set
    private var initialized = false

    override suspend fun resolve(query: LyricsQuery, isrc: String?): AmllMatch = withContext(io) {
        mutex.withLock {
            ensureActive()
            val match = try {
                if (!initialized) {
                    dir.listFiles { file -> file.name.endsWith(".tmp") }?.forEach { it.delete() }
                    initialized = true
                }
                val last = File(dir, "attempt").takeIf { it.isFile }?.readText()?.toLongOrNull()
                val now = clock()
                if (last == null || (now >= last && now - last >= DAY)) refresh(now)
                if (!table.isFile) {
                    generation = null
                    AmllMatch.Unavailable
                } else {
                    val digest = MessageDigest.getInstance("SHA-256")
                    val result = DigestInputStream(table.inputStream(), digest).bufferedReader().use {
                        AmllIndex.resolve(it.lineSequence().iterator(), query, isrc)
                    }
                    generation = digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                    result
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { AmllMatch.Unavailable }
            DiagLog.d("Lyrics", "amll index match ${match.javaClass.simpleName}")
            match
        }
    }

    private suspend fun refresh(now: Long) {
        val started = System.nanoTime()
        var code = "storage"; var rows = 0
        val temporary = File(dir, "table.tmp")
        val attempt = File(dir, "attempt.tmp")
        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("index directory")
            attempt.writeText(now.toString())
            if (!attempt.renameTo(File(dir, "attempt"))) throw IOException("index attempt rename")
            transport.get { status, input ->
                code = status.toString()
                require(status == 200 && input != null)
                var generated = 0L
                temporary.outputStream().buffered().use { output ->
                    AmllIndex.readLines(input) { line ->
                        val bytes = (AmllIndex.parseRaw(line).encode() + "\n").toByteArray(Charsets.UTF_8)
                        generated += bytes.size; AmllIndex.checkTableSize(generated)
                        output.write(bytes); rows++
                    }
                }
                require(rows > 0)
            }
            kotlin.coroutines.coroutineContext.ensureActive()
            if (!temporary.renameTo(table)) throw IOException("index table rename")
        } catch (e: CancellationException) { code = "cancelled"; throw e }
        catch (e: Exception) { code += ":${e.javaClass.simpleName}" }
        finally {
            temporary.delete(); attempt.delete()
            DiagLog.d("Lyrics", "amll index refresh code=$code rows=$rows ms=${(System.nanoTime() - started) / 1_000_000}")
        }
    }

    companion object { const val DAY = 24 * 60 * 60 * 1000L }
}
