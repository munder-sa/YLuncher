package com.gt8d.launcher.config

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
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

class ConfigRepository(
    private val file: File,
    private val installed: () -> Set<String>,
) {
    private val lastSetFile = File(file.parentFile, "last_set_index.txt")

    private val dataStore: DataStore<LauncherConfig> = createDataStore()

    val config: Flow<LauncherConfig> = dataStore.data.map { it.resolve(installed()) }

    var lastSetIndex: Int
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

    private companion object {
        private val activeJobs = mutableMapOf<String, Job>()
    }
}
