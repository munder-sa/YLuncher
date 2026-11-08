package com.gt8d.launcher.apps

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

internal interface LaunchLogIo {
    fun read(): String?
    fun write(text: String)
}

internal class LaunchLog(private val io: LaunchLogIo, private val maxEntries: Int = 4000) {
    constructor(file: File) : this(LaunchLogFile(file))

    private val order = compareBy<UsageLaunch> { it.wallMs }.thenBy { it.pkg }
    private var entries: List<UsageLaunch>? = null
    private var savedEntries: List<UsageLaunch> = emptyList()

    init {
        require(maxEntries >= 0)
    }

    @Synchronized
    fun merge(observed: List<UsageLaunch>, sinceWallMs: Long): List<UsageLaunch> {
        val previous = entries ?: try {
            parse(io.read()).also { savedEntries = it }
        } catch (_: Exception) {
            // 読めなかったファイルは触らず、次の呼び出しで読み直す。
            return observed.filter { it.wallMs >= sinceWallMs }.sortedWith(order)
        }
        val merged = (previous + observed).asSequence()
            .filter { it.wallMs >= sinceWallMs }
            .distinct()
            .sortedWith(order)
            .toList()
            .takeLast(maxEntries)
        entries = merged
        if (merged != previous && merged != savedEntries) {
            try {
                io.write(merged.joinToString("\n") { "${it.wallMs}\t${it.pkg}" })
                savedEntries = merged
            } catch (_: Exception) {
                // メモリには残し、内容が次に変わったときにまとめて保存する。
            }
        }
        return merged.toList()
    }

    private fun parse(text: String?): List<UsageLaunch> = text.orEmpty().lineSequence()
        .mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab < 0) return@mapNotNull null
            val wallMs = line.substring(0, tab).toLongOrNull() ?: return@mapNotNull null
            val pkg = line.substring(tab + 1)
            if (pkg.isEmpty()) null else UsageLaunch(pkg, wallMs)
        }
        .sortedWith(order)
        .toList()
}

/** LaunchLog のロック内から使うファイル入出力。 */
internal class LaunchLogFile(file: File) : LaunchLogIo {
    private val atomic = AtomicFile(file)
    private val companions = listOf(file, File(file.path + ".bak"), File(file.path + ".new"))

    override fun read(): String? = try {
        atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (failure: FileNotFoundException) {
        // 不在を確認できない場合は読み取り失敗として扱い、上書きさせない。
        if (companions.all { Files.notExists(it.toPath()) }) null else throw failure
    }

    override fun write(text: String) {
        val stream = atomic.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
    }
}
