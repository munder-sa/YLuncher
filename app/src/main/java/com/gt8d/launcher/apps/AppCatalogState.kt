package com.gt8d.launcher.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.gt8d.launcher.ui.apps.AppListEntry
import com.gt8d.launcher.ui.panel.AppInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal interface AppCatalogSource {
    suspend fun entries(): List<AppListEntry>
    suspend fun info(pkg: String, installed: Set<String>): AppInfo
    suspend fun settings(entries: List<AppListEntry>): ComponentName?
}

private fun sourceFor(context: Context, catalog: AppCatalog): AppCatalogSource = object : AppCatalogSource {
    override suspend fun entries() = catalog.readEntries()
    override suspend fun info(pkg: String, installed: Set<String>) = catalog.readInfo(pkg, installed)
    override suspend fun settings(entries: List<AppListEntry>): ComponentName? {
        entries.firstOrNull { it.pkg == "com.suding.settings" }?.let { return ComponentName(it.pkg, it.cls) }
        val activity = context.packageManager.resolveActivity(Intent(android.provider.Settings.ACTION_SETTINGS), 0)
            ?.activityInfo ?: return null
        return ComponentName(activity.packageName, activity.name)
    }
}

data class AppCatalogSnapshot(
    val installed: Set<String> = emptySet(),
    val entries: List<AppListEntry> = emptyList(),
    val infos: Map<String, AppInfo> = emptyMap(),
    val settings: ComponentName? = null,
    val ready: Boolean = false,
    val loading: Boolean = false,
    val appRevision: Long = 0,
    val failure: String? = null,
) {
    fun info(pkg: String): AppInfo = infos[pkg] ?: AppInfo(pkg, pkg, null, pkg in installed)
}

/** AppGraph が所有する。すべての問い合わせ・画像変換を worker へ送り、世代一致時だけ公開する。 */
class AppCatalogState internal constructor(
    private val source: AppCatalogSource,
    dispatcher: CoroutineDispatcher,
    private val context: Context? = null,
) : AutoCloseable {
    constructor(context: Context, catalog: AppCatalog, dispatcher: CoroutineDispatcher = Dispatchers.IO) : this(
        sourceFor(context.applicationContext, catalog), dispatcher, context.applicationContext,
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val value = MutableStateFlow(AppCatalogSnapshot())
    val snapshot = value.asStateFlow()
    private var started = false
    private var closed = false
    private var listRunning = false
    private var listGeneration = 0L
    private var configurationGeneration = 0L
    private val generations = mutableMapOf<String, Long>()
    private val requested = mutableSetOf<String>()
    private val running = mutableMapOf<String, Pair<Long, Long>>()
    private var configuration = context?.resources?.configuration?.let(::conditions)
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_CONFIGURATION_CHANGED) {
                configurationChanged()
            } else if (intent.action in setOf(Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REMOVED,
                    Intent.ACTION_PACKAGE_CHANGED)) {
                intent.data?.takeIf { it.scheme == "package" }?.schemeSpecificPart?.let(::invalidate)
            }
        }
    }

    @Synchronized fun start() {
        if (closed || started) return
        started = true
        context?.let {
            ContextCompat.registerReceiver(it, receiver, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }, ContextCompat.RECEIVER_EXPORTED)
            ContextCompat.registerReceiver(it, receiver, IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED),
                ContextCompat.RECEIVER_EXPORTED)
            registered = true
        }
        refreshList()
    }

    @Synchronized fun resume() {
        if (closed) return
        if (!started) { start(); return }
        configurationChanged()
        if (!value.value.ready && !listRunning) refreshList()
        if (value.value.ready && !listRunning) requested.toList().forEach(::loadInfo)
    }

    @Synchronized fun request(packages: Set<String>) {
        if (closed) return
        requested.addAll(packages.filter { it.isNotBlank() })
        if (value.value.ready) requested.toList().forEach(::loadInfo)
    }

    @Synchronized fun invalidate(pkg: String) {
        if (closed) return
        generations[pkg] = (generations[pkg] ?: 0L) + 1
        running.remove(pkg)
        val old = value.value
        if (pkg in old.infos) value.value = old.copy(infos = old.infos - pkg, appRevision = old.appRevision + 1)
        refreshList()
    }

    @Synchronized internal fun invalidateConfiguration() {
        if (closed) return
        configurationGeneration++
        running.clear()
        val old = value.value
        value.value = old.copy(infos = emptyMap(), appRevision = old.appRevision + 1)
        refreshList()
    }

    @Synchronized private fun configurationChanged() {
        val next = context?.resources?.configuration?.let(::conditions) ?: return
        if (next != configuration) {
            configuration = next
            invalidateConfiguration()
        }
    }

    private fun conditions(c: android.content.res.Configuration) = Triple(c.locales.toLanguageTags(), c.densityDpi, c.uiMode)

    private fun refreshList() {
        listGeneration++
        if (listRunning) return
        listRunning = true
        value.value = value.value.copy(loading = true)
        scope.launch {
            while (true) {
                val generation = synchronized(this@AppCatalogState) { listGeneration }
                val result = try {
                    val entries = source.entries().toList()
                    entries to source.settings(entries)
                } catch (e: CancellationException) {
                    synchronized(this@AppCatalogState) {
                        listRunning = false
                        if (!closed) {
                            value.value = value.value.copy(loading = false)
                            if (generation != listGeneration) refreshList()
                        }
                    }
                    throw e
                } catch (e: Exception) {
                    synchronized(this@AppCatalogState) {
                        if (!closed && generation == listGeneration) value.value = value.value.copy(failure = e.toString())
                    }
                    null
                }
                synchronized(this@AppCatalogState) {
                    if (closed) return@launch
                    if (generation == listGeneration) {
                        val old = value.value
                        if (result != null) {
                            val installed = result.first.map { it.pkg }.toSet()
                            val changed = old.installed != installed
                            val infos = old.infos.filter { (pkg, _) -> (pkg in old.installed) == (pkg in installed) }
                            if (changed) {
                                (old.installed union installed).filter { (it in old.installed) != (it in installed) }.forEach {
                                    generations[it] = (generations[it] ?: 0L) + 1
                                    running.remove(it)
                                }
                            }
                            val next = old.copy(installed = installed, entries = result.first, settings = result.second,
                                infos = infos, ready = true, loading = false, failure = null)
                            value.value = next.copy(appRevision = old.appRevision + if (next != old.copy(loading = false)) 1 else 0)
                            result.second?.packageName?.let { requested.add(it) }
                            requested.toList().forEach(::loadInfo)
                        } else value.value = old.copy(loading = false)
                        listRunning = false
                        return@launch
                    }
                }
            }
        }
    }

    private fun loadInfo(pkg: String) {
        if (pkg in value.value.infos || pkg in running) return
        val token = (generations[pkg] ?: 0L) to configurationGeneration
        running[pkg] = token
        val installed = value.value.installed
        scope.launch {
            val info = try { source.info(pkg, installed)
            } catch (e: CancellationException) {
                synchronized(this@AppCatalogState) {
                    if (running[pkg] == token) running.remove(pkg)
                }
                throw e
            } catch (_: Exception) { AppInfo(pkg, pkg, null, pkg in installed) }
            synchronized(this@AppCatalogState) {
                if (closed || token != ((generations[pkg] ?: 0L) to configurationGeneration)) return@launch
                running.remove(pkg)
                val old = value.value
                value.value = old.copy(infos = old.infos + (pkg to info), appRevision = old.appRevision + 1)
            }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        if (registered) {
            try { context?.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {
                // Context 側ですでに解除されていても、所有するジョブと画像は解放する。
            }
        }
        registered = false
        scope.cancel()
        running.clear()
        value.value = AppCatalogSnapshot()
    }
}
