package com.gt8d.launcher.ui.panel

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.apps.AppCatalogSource
import com.gt8d.launcher.apps.AppCatalogState
import com.gt8d.launcher.apps.FrequentApp
import com.gt8d.launcher.apps.HiddenApps
import com.gt8d.launcher.config.DockMode
import com.gt8d.launcher.config.LauncherConfig
import com.gt8d.launcher.config.MAX_PANEL_APPS
import com.gt8d.launcher.config.PanelView
import com.gt8d.launcher.config.ScreenSet
import com.gt8d.launcher.config.Slot
import com.gt8d.launcher.media.MediaWatcher
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.ui.apps.AppListEntry
import com.gt8d.launcher.ui.theme.LauncherTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InitialIconPreparationTest {
    private val panel = "example.panel"
    private val left = "example.left"
    private val right = "example.right"
    private val idle = "example.idle"
    private val music = "example.music"
    private val homeOnly = "example.home.only"
    private val dock = "example.dock"
    private val settings = ComponentName("example.settings", "SettingsActivity")
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val config = mutableStateOf(baseConfig())
    private val wide = mutableStateOf(false)
    private val catalogRevision = mutableStateOf(0)
    private val launches = mutableListOf<String>()
    private val components = mutableListOf<ComponentName>()
    private val catalogs = mutableListOf<AppCatalogState>()
    private val sources = mutableListOf<ControlledSource>()
    private var firstClockFrame: Long? = null
    private var previousNow: NowPlaying? = null
    private var savedConfig: LauncherConfig? = null
    private lateinit var configFile: File
    private var savedConfigBytes: ByteArray? = null

    @Suppress("UNCHECKED_CAST")
    private val nowFlow get() = MediaWatcher::class.java.getDeclaredField("_nowPlaying")
        .apply { isAccessible = true }.get(MediaWatcher) as MutableStateFlow<NowPlaying?>

    @get:Rule(order = 0) val fixture = object : ExternalResource() {
        override fun before() {
            val cn = ComponentName(app.packageName, ComponentActivity::class.java.name)
            val pm = Shadows.shadowOf(app.packageManager)
            pm.addOrUpdateActivity(ActivityInfo().apply {
                name = cn.className; packageName = cn.packageName
            })
            pm.addIntentFilterForActivity(cn,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
            AppGraph.setFrequentAppsForTest(emptyList(), false)
            AppGraph.init(app)
            previousNow = nowFlow.value
            nowFlow.value = null
            configFile = AppGraph.config.javaClass.getDeclaredField("file")
                .apply { isAccessible = true }.get(AppGraph.config) as File
            savedConfigBytes = configFile.takeIf { it.exists() }?.readBytes()
        }

        override fun after() {
            // The outer rule runs after Compose disposes its effects and collectors.
            catalogs.forEach { it.close() }
            sources.forEach { it.close() }
            AppGraph.appCatalogStateForTest = null
            nowFlow.value = previousNow
            AppGraph.clearFrequentAppsForTest()
            savedConfig?.let { original -> runBlocking { AppGraph.config.update { original } } }
            savedConfigBytes?.let { configFile.writeBytes(it) }
                ?: Files.deleteIfExists(configFile.toPath())
        }
    }
    @get:Rule(order = 1) val rule = createComposeRule()

    private fun baseConfig() = LauncherConfig(
        screenSets = listOf(ScreenSet("pair", Slot.App(left), Slot.App(right))),
        homeApps = listOf(homeOnly), panelApps = listOf(panel), dockApps = listOf(dock),
        idleApp = idle, dockMode = DockMode.Manual, appListPkgs = emptyList(),
    )

    private inner class ControlledSource(
        val installed: Set<String>,
        blocked: Set<String>,
        private val settingsComponent: ComponentName?,
        holdEntries: Boolean,
    ) : AppCatalogSource {
        val entriesRelease = CompletableDeferred<Unit>().also { if (!holdEntries) it.complete(Unit) }
        val gates = blocked.associateWith { CompletableDeferred<Unit>() }
        val requested = linkedSetOf<String>()
        val requestedAt = mutableMapOf<String, Long>()
        val icon = ImageBitmap(8, 8)
        override suspend fun entries(): List<AppListEntry> {
            entriesRelease.await()
            return installed.map { AppListEntry(it, "$it.Main", "Loaded $it") }
        }
        override suspend fun settings(entries: List<AppListEntry>) = settingsComponent
        override suspend fun info(pkg: String, installed: Set<String>): AppInfo {
            requested.add(pkg)
            requestedAt[pkg] = rule.mainClock.currentTime
            gates[pkg]?.await()
            return AppInfo(pkg, "Loaded $pkg", if (pkg in installed) icon else null, pkg in installed)
        }
        fun release(vararg packages: String) { packages.forEach { gates.getValue(it).complete(Unit) } }
        fun close() {
            entriesRelease.cancel()
            gates.values.forEach { it.cancel() }
        }
    }

    private fun source(
        blocked: Set<String>,
        installed: Set<String> = setOf(panel, left, right, idle, music, homeOnly, dock, settings.packageName),
        settingsComponent: ComponentName? = settings,
        holdEntries: Boolean = false,
    ) = ControlledSource(installed, blocked, settingsComponent, holdEntries).also { sources.add(it) }

    private fun useCatalog(source: ControlledSource): AppCatalogState =
        AppCatalogState(source, UnconfinedTestDispatcher()).also {
            catalogs.add(it)
            AppGraph.appCatalogStateForTest = it
        }

    private fun host(source: ControlledSource, homeWide: Boolean = false) {
        wide.value = homeWide
        useCatalog(source)
        rule.setContent {
            CompositionLocalProvider(LocalClockCompositionObserver provides {
                if (firstClockFrame == null) firstClockFrame = rule.mainClock.currentTime
            }) {
                LauncherTheme {
                    // Change an input to recompose the same instance after replacing AppGraph's catalog.
                    PanelScreen(catalogRevision.value != 0, onLaunchComponent = { components.add(it) },
                        homeWide = wide.value, homeConfig = config.value,
                        onLaunchApp = { launches.add(it) })
                }
            }
        }
    }

    private fun assertWaiting() {
        rule.onNodeWithTag("clock").assertDoesNotExist()
        rule.onNodeWithTag("dock").assertDoesNotExist()
    }

    private fun release(source: ControlledSource, vararg packages: String) {
        rule.runOnIdle { source.release(*packages) }
    }

    @Test fun apps_waitsForReadyAndForeground_butNotHiddenSets_thenRequestsRemainder() {
        val foreground = setOf(panel, idle, dock, settings.packageName)
        val background = setOf(left, right, homeOnly)
        val source = source(foreground + background, holdEntries = true)
        host(source)
        assertWaiting()
        assertTrue(source.requested.isEmpty())
        rule.runOnIdle { source.entriesRelease.complete(Unit) }
        assertWaiting()
        assertEquals(foreground, source.requested)
        rule.onNodeWithTag("icon_$panel").assertDoesNotExist()
        release(source, panel, idle, dock)
        assertWaiting() // Settings is part of the first visible frame, too.
        release(source, settings.packageName)
        rule.onNodeWithTag("clock").assertIsDisplayed()
        rule.onNodeWithTag("icon_$panel").performScrollTo()
            .assertContentDescriptionEquals("Loaded $panel").performClick()
        rule.onNodeWithTag("dock_app_$dock").performScrollTo().performClick()
        rule.onNodeWithTag("dock_settings").performScrollTo().performClick()
        assertEquals(listOf(panel, dock), launches)
        assertEquals(listOf(settings), components)
        assertEquals(foreground + background, source.requested)
        assertFalse(source.gates.getValue(left).isCompleted)
        assertFalse(source.gates.getValue(right).isCompleted)
        assertSame(source.icon, catalogs.single().snapshot.value.info(panel).icon)
        release(source, left, right, homeOnly)
        rule.onNodeWithTag("clock").assertIsDisplayed()
    }

    @Test fun sets_waitsForBothSidesIncludingScrolledSets_butNotAppsOrMedia() {
        val offscreen = "example.offscreen"
        config.value = config.value.copy(panelView = PanelView.Sets,
            screenSets = config.value.screenSets + (1..12).map {
                ScreenSet("extra$it", Slot.Panel, Slot.App(offscreen))
            })
        nowFlow.value = NowPlaying(music, "Song", null, null, null, false, false)
        val foreground = setOf(left, right, offscreen, dock, settings.packageName)
        val source = source(foreground + setOf(panel, idle, music, homeOnly))
        host(source)
        assertWaiting()
        assertEquals(foreground, source.requested)
        release(source, left, right, dock, settings.packageName)
        assertWaiting()
        release(source, offscreen)
        rule.onNodeWithTag("clock").assertIsDisplayed()
        rule.onNodeWithTag("panel_set_pair").assertExists()
        rule.onNodeWithTag("panel_set_extra12").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("media_card").assertDoesNotExist()
        rule.onNodeWithTag("icon_grid").assertDoesNotExist()
        assertEquals(foreground + setOf(panel, idle, music, homeOnly), source.requested)
        rule.onAllNodesWithContentDescription("Loaded $offscreen(アプリが見つかりません)",
            useUnmergedTree = true).assertCountEquals(12)
    }

    @Test fun wide_waitsForSetsAppsAndPlayingApp_thenLeavesAFrameBeforeBackground() {
        nowFlow.value = NowPlaying(music, "Song", null, null, null, false, false)
        val foreground = setOf(left, right, panel, music, dock, settings.packageName)
        val background = setOf(homeOnly, idle)
        val source = source(foreground + background)
        host(source, homeWide = true)
        assertWaiting()
        assertEquals(foreground, source.requested)
        release(source, left, right, panel, dock, settings.packageName)
        assertWaiting()
        release(source, music)
        rule.onNodeWithTag("clock").assertIsDisplayed()
        rule.onNodeWithTag("home_set_pair").assertIsDisplayed()
        rule.onNodeWithTag("home_app_$panel").assertIsDisplayed().performClick()
        rule.onNodeWithTag("player_app_row").assertIsDisplayed()
        rule.onNodeWithTag("player_art").assertIsDisplayed().performClick()
        rule.onNodeWithTag("player_idle_app").assertDoesNotExist()
        assertEquals(listOf(panel, music), launches)
        assertEquals(foreground + background, source.requested)
        val shownAt = requireNotNull(firstClockFrame)
        background.forEach { assertTrue(source.requestedAt.getValue(it) > shownAt) }
        assertFalse(source.gates.getValue(idle).isCompleted)
    }

    @Test fun wide_idleIsRequiredWhenThereIsNoSession() {
        val source = source(setOf(idle, homeOnly))
        host(source, homeWide = true)
        assertWaiting()
        assertFalse(homeOnly in source.requested)
        release(source, idle)
        rule.onNodeWithTag("clock").assertIsDisplayed()
        rule.onNodeWithTag("player_idle_open").performClick()
        assertEquals(listOf(idle), launches)
        assertTrue(homeOnly in source.requested)
    }

    @Test fun apps_playingSessionDoesNotWaitForIdle_andKeepsMediaLaunchTarget() {
        nowFlow.value = NowPlaying(music, "Song", null, null, null, false, false)
        val source = source(setOf(music, idle, left, right, homeOnly))
        host(source)
        assertWaiting()
        assertFalse(idle in source.requested)
        release(source, music)
        rule.onNodeWithTag("clock").assertIsDisplayed()
        rule.onNodeWithTag("artwork").performScrollTo().performClick()
        rule.onNodeWithTag("idle_app").assertDoesNotExist()
        assertEquals(listOf(music), launches)
        assertFalse(source.gates.getValue(idle).isCompleted)
    }

    @Test fun apps_preparesEveryPageCandidate_butNotBeyondPanelLimit_andKeepsMissingSlot() {
        val packages = (1..MAX_PANEL_APPS + 1).map { "example.page$it" }
        val missing = packages[1]
        config.value = config.value.copy(panelApps = packages)
        val foreground = packages.take(MAX_PANEL_APPS).toSet() + setOf(idle, dock, settings.packageName)
        val source = source(foreground + packages.last(),
            installed = (packages - missing).toSet() + setOf(idle, dock, settings.packageName))
        host(source)
        assertWaiting()
        assertEquals(foreground, source.requested)
        release(source, *(foreground - packages[MAX_PANEL_APPS - 1]).toTypedArray())
        assertWaiting()
        release(source, packages[MAX_PANEL_APPS - 1])
        rule.onNodeWithTag("clock").assertIsDisplayed()
        rule.onNodeWithTag("missing_$missing").performScrollTo()
            .assertContentDescriptionEquals("Loaded $missing(アプリが見つかりません)").performClick()
        assertTrue(launches.isEmpty())
        rule.onNodeWithTag("icon_${packages.first()}").performScrollTo().performClick()
        assertEquals(listOf(packages.first()), launches)
        assertTrue(packages.last() in source.requested)
        assertFalse(source.gates.getValue(packages.last()).isCompleted)
        rule.onNodeWithTag("icon_${packages.last()}").assertDoesNotExist()
    }

    @Test fun manualDock_doesNotWaitForFrequentOrFilteredPackages() {
        val frequent = "example.frequent"
        val absent = "example.absent"
        val hidden = HiddenApps.packages.first()
        config.value = config.value.copy(dockApps = listOf(absent, hidden, settings.packageName, dock))
        AppGraph.setFrequentAppsForTest(listOf(FrequentApp(frequent, 10)), true)
        val foreground = setOf(panel, idle, dock, settings.packageName)
        val source = source(foreground + setOf(frequent, absent, hidden),
            installed = foreground + setOf(frequent, hidden))
        host(source)
        assertWaiting()
        assertEquals(foreground, source.requested)
        release(source, *foreground.toTypedArray())
        rule.onNodeWithTag("clock").assertIsDisplayed()
        rule.onNodeWithTag("dock_app_$dock").assertIsDisplayed()
        listOf(frequent, absent, hidden).forEach {
            rule.onNodeWithTag("dock_app_$it").assertDoesNotExist()
            assertTrue(it in source.requested)
            assertFalse(source.gates.getValue(it).isCompleted)
        }
    }

    @Test fun autoDock_waitsOnlyForSelectedFourFrequentApps_notUnusedManualApps() {
        val ranked = (1..5).map { "example.frequent$it" }
        config.value = config.value.copy(dockMode = DockMode.Auto)
        AppGraph.setFrequentAppsForTest(ranked.map { FrequentApp(it, 10) }, true)
        val foreground = setOf(panel, idle, settings.packageName) + ranked.take(4)
        val source = source(foreground + setOf(dock, ranked.last()),
            installed = foreground + setOf(dock, ranked.last()))
        host(source)
        assertWaiting()
        assertEquals(foreground, source.requested)
        release(source, *foreground.toTypedArray())
        rule.onNodeWithTag("clock").assertIsDisplayed()
        ranked.take(4).forEach {
            rule.onNodeWithTag("dock_app_$it").performScrollTo().assertIsDisplayed().performClick()
        }
        assertEquals(ranked.take(4), launches)
        rule.onNodeWithTag("dock_app_$dock").assertDoesNotExist()
        rule.onNodeWithTag("dock_app_${ranked.last()}").assertDoesNotExist()
        assertTrue(setOf(dock, ranked.last()).all { it in source.requested })
    }

    @Test fun configChangeDuringPreparation_waitsForNewForeground_notOldInfo() {
        val replacement = "example.replacement"
        val source = source(setOf(panel, replacement, left, right, homeOnly),
            installed = setOf(panel, replacement, idle, dock, settings.packageName))
        host(source)
        assertWaiting()
        rule.runOnIdle { config.value = config.value.copy(panelApps = listOf(replacement)) }
        assertWaiting()
        assertTrue(replacement in source.requested)
        release(source, replacement)
        rule.onNodeWithTag("clock").assertIsDisplayed()
        rule.onNodeWithTag("icon_$replacement").performScrollTo().performClick()
        assertEquals(listOf(replacement), launches)
        assertFalse(source.gates.getValue(panel).isCompleted)
        assertFalse(panel in catalogs.single().snapshot.value.infos)
    }

    @Test fun viewChangeDuringPreparation_replacesRequiredAppsWithSets() {
        val source = source(setOf(panel, left, right, idle, homeOnly))
        host(source)
        assertWaiting()
        assertFalse(left in source.requested)
        rule.runOnIdle { config.value = config.value.copy(panelView = PanelView.Sets) }
        assertWaiting()
        assertTrue(setOf(left, right).all { it in source.requested })
        release(source, left, right)
        rule.onNodeWithTag("clock").assertIsDisplayed()
        rule.onNodeWithTag("panel_sets").assertIsDisplayed()
        assertFalse(source.gates.getValue(panel).isCompleted)
        assertFalse(source.gates.getValue(idle).isCompleted)
    }

    @Test fun nowChangeDuringPreparation_requestsCurrentMediaWithoutWaitingForOldIdle() {
        val source = source(setOf(idle, music, left, right, homeOnly))
        host(source)
        assertWaiting()
        rule.runOnIdle { nowFlow.value = NowPlaying(music, "Song", null, null, null, false, false) }
        assertWaiting()
        assertTrue(music in source.requested)
        release(source, music)
        rule.onNodeWithTag("clock").assertIsDisplayed()
        rule.onNodeWithTag("artwork").performScrollTo().assertIsDisplayed()
        assertFalse(source.gates.getValue(idle).isCompleted)
    }

    @Test fun afterPreparation_configAndPendingViewChanges_keepScreenVisible() {
        savedConfig = runBlocking { AppGraph.config.config.first() }
        val freshLeft = "example.fresh.left"
        val freshPanel = "example.fresh.panel"
        val source = source(setOf(homeOnly, freshLeft, freshPanel))
        host(source)
        rule.onNodeWithTag("clock").assertIsDisplayed()
        rule.runOnIdle {
            config.value = config.value.copy(panelApps = listOf(freshPanel),
                screenSets = listOf(ScreenSet("fresh", Slot.App(freshLeft), Slot.Panel)))
        }
        rule.onNodeWithTag("clock").assertIsDisplayed()
        assertTrue(freshPanel in source.requested)
        rule.onNodeWithTag("panel_view_sets").performClick()
        rule.onNodeWithTag("clock").assertIsDisplayed()
        rule.onNodeWithTag("panel_view_sets").assertIsSelected()
        rule.onNodeWithTag("panel_set_fresh").assertIsDisplayed()
        assertTrue(freshLeft in source.requested)
        assertFalse(source.gates.getValue(freshLeft).isCompleted)
        rule.waitUntil(5000L) { AppGraph.latestConfig.value?.panelView == PanelView.Sets }
        release(source, freshLeft, freshPanel)
        rule.onNodeWithContentDescription("Loaded $freshLeft(アプリが見つかりません)",
            useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("clock").assertIsDisplayed()
    }

    @Test fun catalogReplacement_resetsPreparationAndRequestsSameForegroundAgain() {
        val old = source(setOf(homeOnly))
        host(old)
        rule.onNodeWithTag("clock").assertIsDisplayed()
        val replacement = source(setOf(panel, homeOnly))
        rule.runOnIdle {
            useCatalog(replacement)
            catalogRevision.value++
        }
        assertWaiting()
        assertEquals(setOf(panel, idle, dock, settings.packageName), replacement.requested)
        release(replacement, panel)
        rule.onNodeWithTag("clock").assertIsDisplayed()
        rule.onNodeWithTag("icon_$panel").performScrollTo()
            .assertContentDescriptionEquals("Loaded $panel")
        assertTrue(homeOnly in replacement.requested)
    }

    @Test fun screenChangeBeforeBackgroundFrame_discardsOldReservation_andRequestsNewForeground() {
        val freshLeft = "example.fresh.left"
        val newHome = "example.new.home"
        val source = source(setOf(panel, left, right, homeOnly, freshLeft, newHome))
        host(source)
        assertWaiting()
        rule.mainClock.autoAdvance = false
        release(source, panel)
        rule.mainClock.advanceTimeUntil(1000L) {
            rule.onAllNodesWithTag("clock").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("clock").assertIsDisplayed()
        assertFalse(homeOnly in source.requested)
        rule.runOnIdle {
            config.value = config.value.copy(panelView = PanelView.Sets,
                screenSets = listOf(ScreenSet("fresh", Slot.App(freshLeft), Slot.Panel)),
                homeApps = listOf(newHome))
        }
        rule.mainClock.advanceTimeUntil(1000L) { freshLeft in source.requested }
        rule.onNodeWithTag("clock").assertIsDisplayed()
        rule.onNodeWithTag("panel_set_fresh").assertIsDisplayed()
        assertFalse(source.gates.getValue(freshLeft).isCompleted)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue(newHome in source.requested)
        assertFalse(homeOnly in source.requested)
        assertFalse(left in source.requested)
        assertFalse(right in source.requested)
    }

    @Test fun appList_stillRequestsEntriesOutsideConfiguredPackages() {
        val extra = "example.unconfigured"
        val source = source(setOf(extra, left),
            installed = setOf(panel, idle, dock, settings.packageName, left, extra))
        host(source)
        rule.onNodeWithTag("clock").assertIsDisplayed()
        assertFalse(extra in source.requested)
        rule.onNodeWithTag("dock_applist").performScrollTo().performClick()
        rule.onNodeWithTag("applist_close").assertIsDisplayed()
        assertTrue(extra in source.requested)
        release(source, extra)
        rule.onNodeWithTag("applist_all").performClick()
        rule.onNode(hasText("Loaded $extra") and hasClickAction()).performClick()
        assertEquals(listOf(ComponentName(extra, "$extra.Main")), components)
        assertTrue(launches.isEmpty())
    }
}
