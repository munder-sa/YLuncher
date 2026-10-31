package com.gt8d.launcher.config

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.gt8d.launcher.sets.LastIndexStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int

class ConfigRepository(
    private val file: File,
    private val backupWriter: (File, String) -> Unit = { f, s -> f.writeText(s) },
    private val installed: () -> Set<String>,
) : LastIndexStore {
    private val lastSetFile = File(file.parentFile, "last_set_index.txt")

    private val dataStore: DataStore<LauncherConfig> = createDataStore()

    val config: Flow<LauncherConfig> = dataStore.data.map { it.resolve(installed()) }

    override var lastSetIndex: Int
        get() = runCatching {
            if (lastSetFile.exists()) {
                lastSetFile.readText().trim().toInt()
            } else {
                0
            }
        }.getOrDefault(0)
        set(value) {
            lastSetFile.parentFile?.mkdirs()
            lastSetFile.writeText(value.toString())
        }

    suspend fun update(f: (LauncherConfig) -> LauncherConfig) {
        dataStore.updateData { current -> f(current) }
    }

    suspend fun reset() {
        dataStore.updateData { Defaults.build(installed()) }
    }

    private fun createDataStore(): DataStore<LauncherConfig> {
        val canonicalPath = file.canonicalPath
        val newJob = SupervisorJob()
        val prevJob = synchronized(activeJobs) {
            activeJobs.put(canonicalPath, newJob)
        }
        if (prevJob != null) {
            runBlocking {
                prevJob.cancelAndJoin()
            }
        }

        migrateFileIfNeeded()

        val serializer = object : Serializer<LauncherConfig> {
            override val defaultValue: LauncherConfig
                get() = Defaults.build(installed())

            override suspend fun readFrom(input: InputStream): LauncherConfig {
                val text = input.readBytes().decodeToString()
                return ConfigCodec.decode(text)
                    ?: throw CorruptionException("Corrupt or unsupported LauncherConfig")
            }

            override suspend fun writeTo(t: LauncherConfig, output: OutputStream) {
                output.write(ConfigCodec.encode(t).encodeToByteArray())
            }
        }

        val corruptionHandler = ReplaceFileCorruptionHandler {
            if (file.exists()) {
                val backupFile = File(file.parentFile, "config.bak.json")
                file.copyTo(backupFile, overwrite = true)
            }
            Defaults.build(installed())
        }

        return DataStoreFactory.create(
            serializer = serializer,
            corruptionHandler = corruptionHandler,
            scope = CoroutineScope(Dispatchers.IO + newJob),
            produceFile = { file },
        )
    }

    private fun migrateFileIfNeeded() {
        runCatching {
            if (!file.isFile) return
            val original = file.readText()
            fun version(text: String): Int =
                Json.parseToJsonElement(text).jsonObject["version"]?.jsonPrimitive?.int ?: 1
            if (version(original) >= CONFIG_VERSION) return
            val decoded = ConfigCodec.decode(original) ?: return
            val backup = File(file.parentFile, "config-v2-backup.json")
            val validBackup = runCatching {
                val text = backup.readText()
                ConfigCodec.decode(text) != null && version(text) < 3
            }.getOrDefault(false)
            if (!validBackup) {
                val tmp = File(file.parentFile, "config-v2-backup.json.tmp")
                backupWriter(tmp, original)
                if (tmp.readText() != original || !tmp.renameTo(backup)) return
            }
            val tmp = File(file.parentFile, "config.json.tmp")
            tmp.writeText(ConfigCodec.encode(decoded))
            tmp.renameTo(file)
        }
    }

    private companion object {
        private val activeJobs = mutableMapOf<String, Job>()
    }
}
