package com.gt8d.launcher.config

import java.io.Closeable
import java.io.File
import java.nio.file.Files

/** 保存先とキャッシュをテスト開始前の状態へ戻す、UI テスト用の fixture。 */
internal class LastSetIndexFixture(private val repository: ConfigRepository) : Closeable {
    private val configFile = ConfigRepository::class.java.getDeclaredField("file")
        .apply { isAccessible = true }.get(repository) as File
    val indexFile = File(configFile.parentFile, "last_set_index.txt")
    private val storeField = ConfigRepository::class.java.getDeclaredField("lastSetIndexStore")
        .apply { isAccessible = true }
    private val savedStore = storeField.get(repository) as LastSetIndexStore
    private val savedBytes = indexFile.takeIf { it.exists() }?.readBytes()

    init {
        storeField.set(repository, LastSetIndexStore(indexFile))
    }

    override fun close() {
        try {
            if (savedBytes != null) {
                indexFile.writeBytes(savedBytes)
            } else {
                Files.deleteIfExists(indexFile.toPath())
            }
        } finally {
            storeField.set(repository, savedStore)
        }
    }
}
