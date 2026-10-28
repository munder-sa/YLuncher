package com.gt8d.launcher.config

object Defaults {
    const val PKG_VIDEO = "com.zdj.videoplayer"
    const val PKG_VLC = "org.videolan.vlc"
    const val PKG_UNEXT = "jp.unext.mediaplayer"
    const val PKG_PRIME_VIDEO = "com.amazon.avod.thirdpartyclient"
    const val PKG_TVER = "jp.hamitv.hamiand1"
    const val PKG_ABEMA = "tv.abema"
    const val PKG_APPLE_MUSIC = "com.apple.android.music"
    const val PKG_NEWPIPE = "org.schabi.newpipe"
    const val PKG_YOUTUBE = "com.google.android.youtube"
    const val PKG_CHROME = "com.android.chrome"
    const val PKG_MAPS = "com.google.android.apps.maps"

    private val defaultScreenSets = listOf(
        ScreenSet("set1", Slot.App(PKG_YOUTUBE), Slot.Panel),
        ScreenSet("set2", Slot.App(PKG_YOUTUBE), Slot.App(PKG_APPLE_MUSIC)),
        ScreenSet("set3", Slot.App(PKG_YOUTUBE), Slot.App(PKG_MAPS)),
    )

    private val defaultHomeApps = listOf(
        PKG_VIDEO,
        PKG_VLC,
        PKG_UNEXT,
        PKG_PRIME_VIDEO,
        PKG_TVER,
        PKG_ABEMA,
        PKG_APPLE_MUSIC,
        PKG_NEWPIPE,
        PKG_YOUTUBE,
        PKG_CHROME,
        PKG_MAPS,
    )

    private val defaultPanelApps = listOf(
        PKG_APPLE_MUSIC,
        PKG_YOUTUBE,
        PKG_NEWPIPE,
        PKG_MAPS,
        PKG_CHROME,
    )

    private val defaultDockApps = listOf(
        PKG_NEWPIPE,
        PKG_CHROME,
        PKG_YOUTUBE,
        PKG_APPLE_MUSIC,
    )

    fun build(installed: Set<String>): LauncherConfig = LauncherConfig(
        version = 1,
        screenSets = defaultScreenSets,
        homeApps = defaultHomeApps.filter { it in installed },
        panelApps = defaultPanelApps.filter { it in installed },
        dockApps = defaultDockApps.filter { it in installed },
        idleApp = PKG_APPLE_MUSIC,
        link = LinkConfig(),
        gesture = GestureConfig(),
    )
}

fun LauncherConfig.resolve(installed: Set<String>): LauncherConfig = copy(
    homeApps = homeApps.filter { it in installed },
    panelApps = panelApps.filter { it in installed },
    dockApps = dockApps.filter { it in installed },
)
