# Task 15a: 部品の組み立て・ホーム・パネル画面・パネルの転送判定

**最初に `tasks/RULES.md` を読み、従うこと。**

計画 `docs/superpowers/plans/2026-10-28-gt8d-launcher.md` の **Task 15** の前半、仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §4.4・§4.5・§5.2(HomeActivity・PanelActivity)・§7.1 に従う。計画の Global Constraints を守る。アプリ一覧・2本指スワイプ・ドックの「分割」「アプリ一覧」の接続は次の Task(15b)で行うので、ここではしない。

## 作成・変更するファイル(これで全部。許可外の書込みは拒否され、そこで停止する)

- 新規: `app/src/main/java/com/gt8d/launcher/AppGraph.kt`
- 新規: `app/src/main/java/com/gt8d/launcher/ui/home/HomeActivity.kt`
- 新規: `app/src/main/java/com/gt8d/launcher/ui/panel/PanelActivity.kt`
- 新規: `app/src/main/java/com/gt8d/launcher/ui/panel/PanelForwarder.kt`
- 新規: `app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt`(パネルの状態を集めて `PanelContent` に渡す Composable)
- 新規: `app/src/main/java/com/gt8d/launcher/apps/AppCatalog.kt`
- 新規: `app/src/test/java/com/gt8d/launcher/ui/panel/PanelForwarderTest.kt`
- 変更: `app/src/main/java/com/gt8d/launcher/media/MediaWatcher.kt`(`FAVORITE_ACTION_IDS` に `"com.apple.android.music.playback.action.FAVORITE"` を入れるだけ)
- 変更: `app/src/main/java/com/gt8d/launcher/LauncherApp.kt`(`AppGraph` の初期化を足すだけ)
- 変更: `app/src/main/AndroidManifest.xml`

## 仕様

### PanelForwarder(パッケージ `com.gt8d.launcher.ui.panel`、純粋な Kotlin)
```kotlin
class PanelForwarder(private val opening: () -> Opening?, private val scheduler: Scheduler) {
    companion object { const val NO_FLAG_WAIT_MS = 1500L }
    fun onShown(inMultiWindow: Boolean, forward: () -> Unit)
    fun onMultiWindowChanged(inMultiWindow: Boolean)
    fun onOpeningCleared()
    fun onHidden()
}
```
規則(仕様 §5.2 PanelActivity):
- `onShown`: `forward` を覚える。分割の中なら何もしない。分割の外で、`opening()` が非 null なら待つ(転送しない)。`opening()` が null なら `NO_FLAG_WAIT_MS` を予約し、その時点でまだ分割の外なら `forward()`
- `onMultiWindowChanged(true)`: 予約を取り消す。`false` になったら `onShown` と同じ判定をやり直す
- `onOpeningCleared()`: 表示中かつ分割の外なら、すぐ `forward()`
- `onHidden()`: 予約を取り消し、表示中でない扱いにする
- `forward()` は1回の表示につき最大1回

### AppCatalog(パッケージ `com.gt8d.launcher.apps`)
```kotlin
class AppCatalog(private val context: Context) {
    fun installed(): Set<String>                  // MAIN/LAUNCHER を持つパッケージ名
    fun label(pkg: String): String                // 取れなければ pkg
    fun info(pkg: String): AppInfo                // ui/panel/PanelModels.kt の AppInfo(icon は ImageBitmap、取れなければ null。installed は installed() に含まれるか)
}
```
アイコンは `PackageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()`、結果はパッケージ名ごとにメモリに保持する。

### AppGraph(パッケージ `com.gt8d.launcher`)
```kotlin
object AppGraph {
    fun init(app: Application)                        // LauncherApp.onCreate で1回
    val config: ConfigRepository
    val catalog: AppCatalog
    val bridge: VendorBridge
    val selector: SplitModeSelector                   // FileSplitModeStore(filesDir/split_mode.json)、dReady = { false }(D は Task 19)
    val watcher: ForegroundWatcher                    // UsageStatsForegroundEvents
    val warm: WarmTracker                             // ownTaskExists = ActivityManager.appTasks が空でない
    val curtain: CurtainController                    // view = CurtainViewProxy(EdgeStripService.curtainView に転送。null なら何もしない)
    val runner: SetRunner
    val forwarderFactory: () -> PanelForwarder
    val latestConfig: StateFlow<LauncherConfig?>      // config.config を IO で集めた最新値(SetRunner の config() はこれを使い、null なら Defaults.build)
}
```
- `ConfigRepository` は `File(filesDir, "config.json")` と `catalog::installed` で作る(アプリ全体で1つだけ)
- `SetRunner` の依存: `vendor = VendorSplitLauncher(bridge)`、`appPair = null`、`apps = AndroidActivityLauncher(app)`、`scheduler = MainThreadScheduler()`、`clock = ElapsedClock`、`ownPkg = app.packageName`、`labelFor = catalog::label`、`startHome = HomeActivity を CLEAR_TOP|NEW_TASK で起動`、`messenger = Toast で「分割が使えなくなりました」「切り替えに失敗しました」`、`lastIndex = config`
- `EdgeStripService.listener` を設定: `Next`→`runner.next()`、`Prev`→`runner.prev()`、`Home`→`runner.home()`。`EdgeStripService.gestureConfig` は `latestConfig` の `gesture` を反映
- `runner.opening` の変化を監視し、非 null → null になったら、生きている `PanelForwarder` に `onOpeningCleared()` を伝える(`forwarderFactory` が作ったものを弱参照で保持してよい)

### HomeActivity(パッケージ `com.gt8d.launcher.ui.home`)
- `ComponentActivity`。Compose、`LauncherTheme`。背景 `R.drawable.wallpaper`(`ContentScale.Crop`、中央基準)
- レイアウト: 左に `IconGrid`(`homeApps` を `AppCatalog.info` に変換、列数 4、`onAdd = null`)、右に `PanelScreen`(幅は画面の 45%)
- `onResume`: `EdgeStripService.ensureRunning(this)`
- アイコンを押す: `AndroidActivityLauncher.startApp(pkg, adjacent = false)`
- 起動時に最後のセットを開く処理は、まだ入れない(Task 16)

### PanelActivity(パッケージ `com.gt8d.launcher.ui.panel`)
- `ComponentActivity`。Compose、`LauncherTheme`。全面に `PanelScreen`
- `onResume`: `EdgeStripService.ensureRunning(this)`。`isInMultiWindowMode` なら `AppGraph.runner.panelResumedInSplit()`。`forwarder.onShown(isInMultiWindowMode) { HomeActivity を開いて finish() }`
- `onMultiWindowModeChanged(isInMultiWindowMode, newConfig)`: `forwarder.onMultiWindowChanged(...)`。true になったら `panelResumedInSplit()`
- `onPause`: `forwarder.onHidden()`
- パネルのアイコンを押す: 分割の中(`isInMultiWindowMode`)なら `startApp(pkg, adjacent = true)`(左を入れ替え)、外なら `adjacent = false`

### PanelScreen(パッケージ `com.gt8d.launcher.ui.panel`)
```kotlin
@Composable fun PanelScreen(inSplit: Boolean, onLaunchApp: (String) -> Unit)
```
- `PanelState` を組み立てて `PanelContent` を表示する:
  - `now` = `MediaWatcher.nowPlaying`、`positionMs` = 再生中なら 500ms ごとに `now.positionAt(SystemClock.elapsedRealtime())` を再計算
  - `idleApp` = `catalog.info(config.idleApp)`、`panelApps` = `config.panelApps` を `info` に、`dockApps` = `config.dockApps` を `info` に
  - `dockStatus` = Wi-Fi は `WifiManager.isWifiEnabled`、Bluetooth は `BLUETOOTH_CONNECT` が許可されていれば `BluetoothManager.adapter.isEnabled`、されていなければ null
  - `showCar` = `AppGraph.bridge.carHomeAvailable`
  - `needsPermission` = 「通知へのアクセス(`NotificationManagerCompat.getEnabledListenerPackages` に自分が含まれない)・他のアプリの上に表示(`Settings.canDrawOverlays`)・使用状況へのアクセス(`watcher.available()`)」のどれかが未許可なら true
- `PanelActions`: `onMediaAction` → `MediaWatcher` の各操作、`onSeek` → `MediaWatcher.seekTo`、`onLaunch` → `onLaunchApp`、`onHome` → `AppGraph.runner.home()`、`onCar` → `AppGraph.bridge.switchToCarScreen()`、`onOpenPermissions` → `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`(設定画面は Task 18 で差し替える)、`onAdd`・`onSplit`・`onAppList`・`onAppListLongPress` → 15b で接続するので、ここでは何もしない空のラムダ(コメントで「Task 15b で接続」)

### AndroidManifest.xml
- `HomeActivity`: `exported=true`、`launchMode="singleTask"`、`MAIN`+`HOME`+`DEFAULT` の intent-filter、`resizeableActivity=true`
- `PanelActivity`: `exported=true`、`android:label="GT8D パネル"`、`MAIN`+`LAUNCHER` の intent-filter、`resizeableActivity=true`(自作アプリのランチャー用の入口はこれ1つだけ)
- `<queries>` に `MAIN`+`LAUNCHER` の intent と、`android.intent.action.VIEW`+`scheme=http` の intent を足す(既存の `com.sd.factoryconfig` はそのまま)
- `android.permission.BLUETOOTH_CONNECT` を足す
- `LauncherApp` は既存の `android:name=".LauncherApp"` のまま

## 手順(TDD)

1. `PanelForwarderTest` を書く(純粋な JUnit、`FakeScheduler`):
   - `forwarder_noForwardWhileOpening`: opening 非 null、分割の外で onShown → advanceBy(10000) しても forward 0 回
   - `forwarder_forwardAfterOpeningClearedIfOutside`: 上の続きで opening を null にして `onOpeningCleared()` → forward 1 回
   - `forwarder_noFlag_waits1500_thenForward`: opening null、分割の外で onShown → advanceBy(1499) で 0 回、advanceBy(1) で 1 回
   - `forwarder_enteringSplitWithin1500_cancels`: opening null、外で onShown → advanceBy(1000) → onMultiWindowChanged(true) → advanceBy(1000) で 0 回
   - `forwarder_inSplit_neverForwards`: 分割の中で onShown → onOpeningCleared → advanceBy(5000) で 0 回
   - `forwarder_atMostOncePerShow`: opening null・外で onShown → advanceBy(1500) → onOpeningCleared → forward は 1 回のまま
2. `/home/munde/tools/gradle-run.sh test` を実行し、新しいテストが失敗することを確認する
3. 実装する
4. `/home/munde/tools/gradle-run.sh test assembleDebug` で全部通るまで直す(最大3回。通らなければ失敗したテスト名とエラーの先頭30行を報告して止まる)

## 報告

`test assembleDebug` の結果(実行件数と失敗件数)、作成・変更したファイル名、**決めたこと**。
