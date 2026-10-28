# GT8D カスタムランチャー Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** GT8D（Android 15 車載AI Box）用に、画面セットをスワイプ帯で切り替えられるダーク固定のホームランチャーを作る。

**Architecture:** Kotlin＋Jetpack Compose の単一アプリ。ロジック（設定・セット巡回・リンク振り分け・ジェスチャー判定・メディア選択・安全装置）は Android に依存しない純粋な Kotlin として JVM テストで固め、Activity／Service はそれを呼ぶ薄い層にする。最初に家でできる実験（仕様 §10.1）を済ませ、合否で後続を確定させる。

**Tech Stack:** Kotlin 2.1 以上、AGP 8.7 以上、Jetpack Compose（BOM 2025.01 以上）、DataStore 1.1、kotlinx.serialization 1.7、JUnit4、Robolectric 4.14＋compose-ui-test（Compose の JVM テスト）

**Spec:** `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md`

**実装体制:** Claude Code＝タスクの割り振り・レビュー・コミット判断／Antigravity＝各タスクの実装。各タスク完了ごとに Claude Code がレビューしてから次へ進む。

## Global Constraints

- minSdk 35 / targetSdk 35 / compileSdk 35、JDK 17
- applicationId・パッケージ：`com.gt8d.launcher`
- UI 文言は日本語
- ダーク固定（背景は黒系、パネルは濃いグレー）。ライトテーマは作らない
- タッチ領域は最小 72dp 角、本文 20sp 以上、時計は特大
- リアルタイムぼかし等の重いエフェクトは使わない
- `com.android.launcher3` は Recents 担当として残す。コード・手順のどこでも無効化・削除しない
- 走行中でも動作を変えない（速度・走行状態による分岐を一切入れない）
- 署名鍵はリポジトリ外。`keystore.properties`（リポジトリ外パスを指す）から読み、無ければ debug 署名
- 端末操作は `adb -s 192.168.137.248:5555`
- Gradle コマンドは Windows 前提で `.\gradlew.bat`

## Review Focus

1. **設定後にアプリをアンインストール**（セット・ホーム・パネル・バー・停止中アプリに残ったパッケージ）→ クラッシュせず、スロットは「アプリが見つかりません」、一覧からは除外。→ Task 2 `resolve_dropsUninstalledFromLists` / Task 6 `slot_missingApp_showsPlaceholder`
2. **画面セットを全部削除**して0件 → 巡回はホームのみ、次／前でもホームのまま。→ Task 3 `next_withNoSets_staysHome`
3. **曲情報が欠けたセッション**（ラジオ等で duration が -1／0、ジャケット無し、タイトル無し）→ 進行バーを隠し、タイトル無しはアプリ名を表示、ジャケットは代替画像。→ Task 5 `toNowPlaying_unknownDuration_hidesProgress`
4. **設定ファイルが壊れている／未来の version** → 既定値で起動し、壊れたファイルは `config.bak.json` に退避。→ Task 2 `load_corruptFile_fallsBackAndBacksUp`
5. **帯への短いタップ・斜め・外向きスワイプ** → 何もしない（誤切り替え防止）。→ Task 8 `classify_tapOrDiagonal_isNone`

---

## File Structure

```
settings.gradle.kts / build.gradle.kts / gradle/libs.versions.toml
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/com/gt8d/launcher/
  LauncherApp.kt                 Application。SafetyGuard の起動判定
  config/Model.kt                LauncherConfig ほか（仕様 §5.4）
  config/Defaults.kt             既定値（仕様 §8）と resolve
  config/ConfigCodec.kt          JSON 変換・version 判定
  config/ConfigRepository.kt     DataStore 読み書き・reset・lastSetIndex
  sets/SetNavigator.kt           次／前（ループ）
  sets/LaunchPlanner.kt          セット → 起動手順（純粋）
  sets/SetRunner.kt              起動手順を Intent で実行
  media/NowPlaying.kt            表示用モデル・変換・位置補間・セッション選択（純粋）
  media/MediaWatcher.kt          NotificationListenerService＋MediaSessionManager
  ui/theme/Theme.kt              ダーク固定テーマ・寸法定数
  ui/panel/MediaCard.kt / IconGrid.kt / Dock.kt / PanelContent.kt
  ui/home/HomeActivity.kt        HOME。左アイコン一覧＋右パネル、2本指スワイプ
  ui/panel/PanelActivity.kt      分割右側用
  ui/apps/AppListScreen.kt       全アプリ（純正ランチャーを含む）
  edge/EdgeGesture.kt            帯のジェスチャー判定（純粋）
  edge/EdgeStripService.kt       左右オーバーレイ常駐（FGS specialUse）
  edge/BootReceiver.kt
  link/LinkRouter.kt             URL の振り分け（純粋）
  link/BrowserActivity.kt        WebView・Chromeで開き直す
  settings/SettingsActivity.kt と各画面（SetsScreen / ListsScreen / LinkScreen / GestureScreen / PermissionsScreen）
  safety/SafetyGuard.kt          連続クラッシュ判定（純粋）＋記録
  diag/DiagLog.kt                リングバッファ＋logcat
  car/CarHomeAction.kt           純正画面に戻る（実験⑧まで未実装＝非表示）
app/src/debug/java/com/gt8d/launcher/spike/SpikeActivity.kt   実験用（debugビルドのみ）
app/src/test/java/com/gt8d/launcher/...                       JVM テスト
scripts/setup-device.ps1          初回セットアップ（adb）
docs/superpowers/spikes/home-spikes.md     実験①〜⑦の結果
docs/superpowers/checklists/device-check.md 実機確認チェックリスト
design/assets/                    Muse 素材の元データ（README のみ先に置く）
```

---

### Task 0: プロジェクト土台

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/gt8d/launcher/LauncherApp.kt`, `design/assets/README.md`
- Test: `app/src/test/java/com/gt8d/launcher/SmokeTest.kt`

**Interfaces:**
- Produces: `com.gt8d.launcher.LauncherApp : Application`（中身は空。Task 11 で SafetyGuard を入れる）

- [ ] **Step 1:** Gradle ラッパー付きで単一 `app` モジュールを作る（Global Constraints の SDK・JDK・applicationId）。依存：compose BOM、material3、material-icons-extended、activity-compose、datastore、kotlinx-serialization-json、junit4、robolectric、compose ui-test-junit4（testImplementation）。`unitTests.isIncludeAndroidResources = true`
- [ ] **Step 2:** 署名設定：ルートの `keystore.properties`（`storeFile` はリポジトリ外の絶対パス）があれば release に適用、無ければ debug 署名
- [ ] **Step 3:** `design/assets/README.md` に仕様 §9.1 の Muse 素材3点（アプリアイコン・代替ジャケット・ダーク壁紙）と形式を転記
- [ ] **Step 4:** `SmokeTest.smoke_runs()` で `assertEquals(2, 1 + 1)`
- [ ] **Step 5:** Run `.\gradlew.bat test assembleDebug` → BUILD SUCCESSFUL
- [ ] **Step 6:** Commit `chore: Android プロジェクトの土台`

---

### Task 1: 家での実験（仕様 §10.1 ①〜⑦）— 合否ゲート

**Files:**
- Create: `app/src/debug/java/com/gt8d/launcher/spike/SpikeActivity.kt`、`app/src/debug/AndroidManifest.xml`、`docs/superpowers/spikes/home-spikes.md`

**Interfaces:**
- Produces: `home-spikes.md`（以降のタスクはこの結果を前提にする）

SpikeActivity は捨てコード。ボタンを並べるだけの画面でよい。

- [ ] **Step 1:** SpikeActivity にボタンを作る：
  - ①「YouTube｜Apple Music を分割」：左を `NEW_TASK` で起動 → 300ms 後に右を `NEW_TASK | LAUNCH_ADJACENT | MULTIPLE_TASK`
  - ②「YouTube｜Spike パネル」：右に自アプリの別 Activity（中身は「PANEL」の文字だけ）を ADJACENT 起動
  - ③「帯を表示」：`TYPE_APPLICATION_OVERLAY` で左右端に幅 8px の窓。タッチを logcat `GT8D-SPIKE` に出す
  - ④「メディア一覧」：通知アクセス許可後、`MediaSessionManager.getActiveSessions` の各セッションのパッケージ・metadata・state・customActions を logcat に出す
  - ⑤「分割中に別の組み合わせ（YouTube｜Googleマップ）を同じ手順で起動」「分割中に Spike パネル付きを起動」「分割中に HOME へ」
- [ ] **Step 2:** debug APK を入れて許可を付与（`adb shell cmd notification allow_listener com.gt8d.launcher/<Spikeのリスナー>`、`adb shell appops set com.gt8d.launcher SYSTEM_ALERT_WINDOW allow`）
- [ ] **Step 3:** 各ボタンを scrcpy で実行し、`dumpsys activity activities | grep -E 'windowingMode|mBounds'` と logcat で結果を確認。⑥は①の結果で左右が指定どおりかを確認。⑦は `adb shell settings get secure navigation_mode` を記録（0=3ボタン、2=ジェスチャー）
- [ ] **Step 4:** `home-spikes.md` に ①〜⑦ それぞれ「合格／不合格・観察内容・採用する手順」を記録。④は ☆（customActions）の有無も記録
- [ ] **Step 5:** **ゲート判定**：①②③のどれかが不合格なら、ここで止めてユーザーに相談（仕様 §10.1 の不合格時対応）。⑤⑥の結果で Task 4 の起動手順（間隔ms・ホーム経由の要否）を確定させ、`home-spikes.md` に「Task 4 採用手順」として書く
- [ ] **Step 6:** Commit `spike: 家での実験①〜⑦の結果`

---

### Task 2: 設定モデル・既定値・保存

**Files:**
- Create: `config/Model.kt`, `config/Defaults.kt`, `config/ConfigCodec.kt`, `config/ConfigRepository.kt`
- Test: `config/DefaultsTest.kt`, `config/ConfigCodecTest.kt`, `config/ConfigRepositoryTest.kt`

**Interfaces:**
- Produces:
  - 仕様 §5.4 のデータクラスをそのまま（`LauncherConfig`, `ScreenSet`, `Slot.App`, `Slot.Panel`, `LinkConfig`, `LinkMode`, `GestureConfig`）
  - `object Defaults { fun build(installed: Set<String>): LauncherConfig }`
  - `fun LauncherConfig.resolve(installed: Set<String>): LauncherConfig` … 一覧系（home/panel/dock）から未インストールを除外。セットのスロットは残す（表示側で placeholder）
  - `object ConfigCodec { fun encode(c: LauncherConfig): String; fun decode(json: String): LauncherConfig? }`（version > 1 または壊れていれば null）
  - `class ConfigRepository(file: File, installed: () -> Set<String>) { val config: Flow<LauncherConfig>; suspend fun update(f: (LauncherConfig) -> LauncherConfig); suspend fun reset(); var lastSetIndex: Int }`
  - `const val MAX_PANEL_APPS = 5`, `const val MAX_DOCK_APPS = 4`

- [ ] **Step 0:** GT8D で `adb shell pm list packages` を実行し、既定値のアプリ（Video、VLC、U-NEXT、Prime Video、TVer、ABEMA、Apple Music、NewPipe、YouTube、Chrome、Googleマップ）のパッケージ名を確定。`Defaults.kt` の定数にする（例：Apple Music＝`com.apple.android.music`）
- [ ] **Step 1: failing tests**
  - `build_allInstalled_matchesSpec`：セット3件が順に（YouTube｜Panel）（YouTube｜Apple Music）（YouTube｜Googleマップ）、home 11件の順序、panel＝Apple Music・YouTube・NewPipe・Googleマップ・Chrome、dock＝NewPipe・Chrome・YouTube・Apple Music、idleApp＝Apple Music、link.mode＝InAppSplit、chromeDomains＝["accounts.google.com"]、gesture＝(8, true, 1000)
  - `build_skipsNotInstalled`：VLC を除いた installed で home に VLC が無い
  - `resolve_dropsUninstalledFromLists`（Review Focus 1）：home/panel/dock から消え、screenSets のスロットは残る
  - `codec_roundTrip`：encode→decode で等価
  - `load_corruptFile_fallsBackAndBacksUp`（Review Focus 4）：壊れた JSON と `"version":2` の2ケースで、config が既定値になり `config.bak.json` が作られる
  - `reset_restoresDefaults_keepsLastSetIndex`
- [ ] **Step 2:** Run `.\gradlew.bat test --tests "com.gt8d.launcher.config.*"` → FAIL
- [ ] **Step 3:** 実装。ConfigRepository は DataStore（`DataStoreFactory.create` に `ConfigCodec` を使う Serializer）。lastSetIndex は SharedPreferences（reset 対象外）
- [ ] **Step 4:** 同コマンド → PASS
- [ ] **Step 5:** Commit `feat(config): 設定モデル・既定値・保存`

---

### Task 3: セット巡回

**Files:**
- Create: `sets/SetNavigator.kt`
- Test: `sets/SetNavigatorTest.kt`

**Interfaces:**
- Produces: `object SetNavigator { const val HOME = 0; fun next(current: Int, setCount: Int): Int; fun prev(current: Int, setCount: Int): Int; fun clamp(saved: Int, setCount: Int): Int }`（index 0＝ホーム、1..setCount＝セット）

- [ ] **Step 1: failing tests**
  - `next_wrapsToHome`：next(3,3)=0、next(0,3)=1
  - `prev_wrapsToLast`：prev(0,3)=3、prev(1,3)=0
  - `next_withNoSets_staysHome`（Review Focus 2）：next(0,0)=0、prev(0,0)=0
  - `clamp_outOfRange_goesHome`：clamp(5,3)=0（セット削除後の lastSetIndex）
- [ ] **Step 2:** Run `.\gradlew.bat test --tests "*SetNavigatorTest"` → FAIL
- [ ] **Step 3:** 実装
- [ ] **Step 4:** PASS を確認
- [ ] **Step 5:** Commit `feat(sets): セット巡回`

---

### Task 4: セットの起動

**Files:**
- Create: `sets/LaunchPlanner.kt`, `sets/SetRunner.kt`
- Test: `sets/LaunchPlannerTest.kt`

**Interfaces:**
- Consumes: `ScreenSet`, `Slot`（Task 2）、`SetNavigator`（Task 3）、`home-spikes.md` の「Task 4 採用手順」
- Produces:
  - `sealed interface LaunchStep { data class App(val pkg: String, val adjacent: Boolean); data class Panel(val adjacent: Boolean); data object Home; data class Wait(val ms: Long) }`
  - `object LaunchPlanner { fun plan(target: ScreenSet?, installed: Set<String>): List<LaunchStep> }`（target=null はホーム）
  - `class SetRunner(context: Context, repo: ConfigRepository) { fun open(index: Int); fun next(); fun prev(); fun home() }`（open で lastSetIndex を保存）

- [ ] **Step 1: failing tests**
  - `plan_appPanel`：[App(left,false), Wait(採用ms), Panel(true)]（ホーム経由が必要と判明していれば先頭に Home）
  - `plan_appApp`：[App(left,false), Wait, App(right,true)]
  - `plan_home`：target=null → [Home]
  - `plan_leftMissing_opensRightAlone`：左未インストールなら右のみ全画面（Panel の場合は Home）
  - `plan_rightMissing_opensLeftAlone`
- [ ] **Step 2:** FAIL を確認
- [ ] **Step 3:** 実装。SetRunner は各 step を Intent に変換（App＝`getLaunchIntentForPackage`＋`NEW_TASK`、adjacent なら `LAUNCH_ADJACENT | MULTIPLE_TASK`、Panel＝`PanelActivity`、Home＝`HomeActivity` を `CLEAR_TOP`）。`ActivityNotFoundException` は Toast「開けませんでした」＋DiagLog
- [ ] **Step 4:** PASS を確認
- [ ] **Step 5:** Commit `feat(sets): セットの起動`

---

### Task 5: 再生情報

**Files:**
- Create: `media/NowPlaying.kt`, `media/MediaWatcher.kt`
- Test: `media/NowPlayingTest.kt`

**Interfaces:**
- Produces:
  - `data class SessionSnapshot(val pkg: String, val appLabel: String, val title: String?, val artist: String?, val artwork: Bitmap?, val durationMs: Long, val positionMs: Long, val positionUpdatedAt: Long, val speed: Float, val playing: Boolean, val hasFavoriteAction: Boolean)`
  - `data class NowPlaying(val pkg: String, val title: String, val artist: String?, val artwork: Bitmap?, val durationMs: Long?, val playing: Boolean, val hasFavorite: Boolean)`（durationMs=null で進行バー非表示）
  - `fun SessionSnapshot.toNowPlaying(): NowPlaying`
  - `fun positionAt(s: SessionSnapshot, nowMs: Long): Long`（再生中は speed で補間し 0..duration に丸め）
  - `fun pickSession(sessions: List<SessionSnapshot>, lastActivePkg: String?): SessionSnapshot?`
  - `object MediaWatcher { val nowPlaying: StateFlow<NowPlaying?>; fun play(); fun pause(); fun next(); fun previous(); fun seekTo(ms: Long); fun favorite() }`、`class MediaListenerService : NotificationListenerService`

- [ ] **Step 1: failing tests**
  - `pick_prefersPlaying`：停止中の Apple Music と再生中の YouTube → YouTube
  - `pick_fallsBackToLastActive`：全部停止 → lastActivePkg のもの
  - `pick_none_returnsNull`
  - `toNowPlaying_unknownDuration_hidesProgress`（Review Focus 3）：duration -1 と 0 → durationMs=null。title=null → appLabel
  - `positionAt_interpolatesAndClamps`：playing、speed 1、更新から 5000ms、position 10000、duration 12000 → 12000
- [ ] **Step 2:** FAIL を確認
- [ ] **Step 3:** 実装。MediaWatcher は `MediaSessionManager.addOnActiveSessionsChangedListener(listener, ComponentName(MediaListenerService))` で監視し、各 controller の callback で snapshot を更新。favorite は Task 1 ④で確認した customAction 名があるときだけ `hasFavoriteAction=true`
- [ ] **Step 4:** PASS を確認
- [ ] **Step 5:** Commit `feat(media): 再生情報`

---

### Task 6: テーマとパネルの部品

**Files:**
- Create: `ui/theme/Theme.kt`, `ui/panel/MediaCard.kt`, `ui/panel/IconGrid.kt`, `ui/panel/Dock.kt`, `ui/panel/PanelContent.kt`
- Test: `ui/panel/PanelContentTest.kt`（Robolectric＋compose-ui-test）

**Interfaces:**
- Consumes: `NowPlaying`、`MediaWatcher` の操作（Task 5）、`LauncherConfig`（Task 2）
- Produces:
  - `object Dims { val MinTouch = 72.dp; val BodyText = 20.sp }`、`@Composable fun LauncherTheme(content)`
  - `@Composable fun MediaCard(now: NowPlaying?, idleApp: AppInfo?, onAction: (MediaAction) -> Unit, onLaunch: (String) -> Unit)`
  - `@Composable fun IconGrid(apps: List<AppInfo>, columns: Int, onLaunch: (String) -> Unit, onAdd: (() -> Unit)?)`
  - `@Composable fun Dock(status: DockStatus, dockApps: List<AppInfo>, showCar: Boolean, onHome, onSplit, onCar, onAppList, onAppListLongPress, onLaunch: (String) -> Unit)`、`data class DockStatus(val wifiOn: Boolean, val bluetoothOn: Boolean?)`（BT 許可なしは null＝アイコン非表示）
  - `@Composable fun PanelContent(state: PanelState, actions: PanelActions)`、`PanelState` は `needsPermission: Boolean` を含む（true で「許可が必要」の帯を出し、タップで設定の許可チェックへ）
  - `data class AppInfo(val pkg: String, val label: String, val icon: ImageBitmap?, val installed: Boolean)`、`enum class MediaAction { Play, Pause, Next, Prev, Favorite }`、`MediaAction.Seek` は `onSeek(ms)` で別引数

- [ ] **Step 1: failing tests**
  - `mediaCard_idle_showsClockAndIdleApp`：now=null → 時計ノード（testTag `clock`）と idleApp ラベル
  - `mediaCard_playing_showsTitleArtistControls`：曲名・アーティスト・前/再生/次ボタン、`hasFavorite=false` で ☆ が無い
  - `mediaCard_unknownDuration_noProgressBar`（testTag `progress` が無い）
  - `dock_hasButtonsInOrder`：ホーム・分割・アプリ一覧、`showCar=false` で車が無い
  - `slot_missingApp_showsPlaceholder`（Review Focus 1）：`installed=false` の AppInfo で「アプリが見つかりません」
  - `dock_status_hidesBluetoothWithoutPermission`：`bluetoothOn=null` で BT アイコンが無い、Wi-Fi アイコンはある
  - `panel_needsPermission_showsBanner`
  - `panel_narrowWidth_noOverflow`：幅 576dp 相当（3:7 の右）で全ボタンが表示され、各ボタンの幅・高さ ≥ 72dp
- [ ] **Step 2:** FAIL を確認
- [ ] **Step 3:** 実装。色は黒系背景／濃いグレーのパネル。アイコンは Material Symbols（Home、ViewColumn（分割）、DirectionsCar、Apps、SkipPrevious、PlayArrow、Pause、SkipNext、StarBorder）。ジャケット無しは `R.drawable.artwork_placeholder`（Muse 素材が届くまでは単色の仮 VectorDrawable）
- [ ] **Step 4:** PASS を確認
- [ ] **Step 5:** Commit `feat(ui): テーマとパネル部品`

---

### Task 7: ホーム・パネル画面・アプリ一覧

**Files:**
- Create: `ui/home/HomeActivity.kt`, `ui/panel/PanelActivity.kt`, `ui/apps/AppListScreen.kt`
- Modify: `AndroidManifest.xml`
- Test: `ui/apps/AppListScreenTest.kt`

**Interfaces:**
- Consumes: Task 2〜6
- Produces: `HomeActivity`（`MAIN`＋`HOME`＋`DEFAULT`、`launchMode=singleTask`）、`PanelActivity`（`resizeableActivity=true`、通常タスク）、`fun stockLauncherIntent(): Intent`（`com.android.launcher3/.uioverrides.QuickstepLauncher` 明示）

- [ ] **Step 1: failing tests**
  - `appList_alwaysIncludesStockLauncher`：起動可能アプリ一覧に Launcher3 が無いケースでも「純正ランチャー」項目がある
  - `appList_sortedByLabel`
- [ ] **Step 2:** FAIL を確認
- [ ] **Step 3:** 実装。HomeActivity＝左 `IconGrid`（homeApps、大きめ列数）＋右 `PanelContent`。2本指の左右スワイプで `SetRunner.next/prev`（おまけ扱い）。HOME 起動時：初回起動のみ `SetRunner.open(SetNavigator.clamp(lastSetIndex))`（SafetyGuard が退避中なら何もしない）。Manifest に `<queries>`（MAIN/LAUNCHER、http VIEW）。分割ボタン＝設定のセット新規作成画面（Task 10）を開き、保存後そのセットを open
- [ ] **Step 4:** PASS を確認し、GT8D に入れて既定のホームに設定 → ホーム表示・パネル表示・アイコン起動を scrcpy で確認
- [ ] **Step 5:** Commit `feat(ui): ホーム・パネル画面・アプリ一覧`

---

### Task 8: スワイプ帯

**Files:**
- Create: `edge/EdgeGesture.kt`, `edge/EdgeStripService.kt`, `edge/BootReceiver.kt`
- Modify: `AndroidManifest.xml`
- Test: `edge/EdgeGestureTest.kt`

**Interfaces:**
- Consumes: `GestureConfig`（Task 2）、`SetRunner`（Task 4）
- Produces:
  - `enum class Side { Left, Right }`、`enum class EdgeAction { Next, Prev, Home, None }`
  - `object EdgeGesture { fun classify(side: Side, downX: Float, downY: Float, upX: Float, upY: Float, durationMs: Long, longPressMs: Int, density: Float): EdgeAction }`
  - `class EdgeStripService : Service`（`startForeground`、type `specialUse`）、`fun EdgeStripService.Companion.ensureRunning(context)`

判定ルール（ここで固定）：内向き移動 ≥ 48dp かつ |dy| < 内向き移動の 0.5 倍 → 左帯は Next、右帯は Prev。移動 < 16dp かつ押下時間 ≥ longPressMs → Home。それ以外 None。

- [ ] **Step 1: failing tests**
  - `classify_leftInward_isNext`、`classify_rightInward_isPrev`
  - `classify_longPress_isHome`：移動 4dp・1100ms
  - `classify_tapOrDiagonal_isNone`（Review Focus 5）：移動 4dp・100ms／内向き 60dp・縦 50dp／外向き 60dp
- [ ] **Step 2:** FAIL を確認
- [ ] **Step 3:** 実装。左右に幅 `stripWidthPx`・高さ全面の `TYPE_APPLICATION_OVERLAY` 窓（`FLAG_NOT_FOCUSABLE`）。glow=ON なら押下中に帯を白 30% でフェード表示。BootReceiver（`BOOT_COMPLETED`）と HomeActivity/PanelActivity の onResume で `ensureRunning`。通知は「GT8D Launcher 動作中」1件
- [ ] **Step 4:** PASS を確認し、GT8D で分割中・パネル中・ホームの3状態で 次／前／ホーム が効くことを確認
- [ ] **Step 5:** Commit `feat(edge): スワイプ帯`

---

### Task 9: リンクの受け口と内蔵ブラウザ

**Files:**
- Create: `link/LinkRouter.kt`, `link/BrowserActivity.kt`
- Modify: `AndroidManifest.xml`
- Test: `link/LinkRouterTest.kt`

**Interfaces:**
- Consumes: `LinkConfig`（Task 2）
- Produces:
  - `enum class FrontState { Home, Fullscreen, Split }`
  - `sealed interface LinkRoute { data object Chrome; data object InAppFullscreen; data object InAppAdjacent; data object InAppReplaceRight }`
  - `object LinkRouter { fun route(url: String, cfg: LinkConfig, front: FrontState): LinkRoute }`
  - `BrowserActivity`（http/https の VIEW＋BROWSABLE、ROLE_BROWSER 申請は設定画面から）

- [ ] **Step 1: failing tests**
  - `route_chromeMode_alwaysChrome`
  - `route_googleLogin_goesChrome`：`https://accounts.google.com/...` は InAppSplit でも Chrome
  - `route_homeFront_fullscreen`、`route_fullscreenFront_adjacent`、`route_splitFront_replaceRight`
- [ ] **Step 2:** FAIL を確認
- [ ] **Step 3:** 実装。FrontState は「自アプリ HOME が前面か」と `isInMultiWindowMode` 相当の情報から判定（取得方法は Task 1 の結果に合わせる。判定できなければ Fullscreen 扱い）。ReplaceRight は Task 1 ⑥の結果どおり（右を狙えなければ ADJACENT）。BrowserActivity 上部に「Chromeで開き直す」ボタン（`com.android.chrome` 明示）
- [ ] **Step 4:** PASS を確認
- [ ] **Step 5:** Commit `feat(link): リンクの受け口と内蔵ブラウザ`

---

### Task 10: 設定画面

**Files:**
- Create: `settings/SettingsActivity.kt`, `settings/SetsScreen.kt`, `settings/ListsScreen.kt`, `settings/LinkScreen.kt`, `settings/GestureScreen.kt`, `settings/PermissionsScreen.kt`
- Test: `settings/SettingsScreensTest.kt`

**Interfaces:**
- Consumes: `ConfigRepository`（Task 2）、`stockLauncherIntent()`（Task 7）
- Produces: `SettingsActivity`（extra `open_new_set=true` でセット新規作成画面から開始。保存時に新セットの index を result で返す）

仕様 §6 の 1〜7 をこの順で1画面のリスト＋各詳細画面にする。

- [ ] **Step 1: failing tests**
  - `reset_requiresConfirm`：「デフォルトに戻す」タップだけでは reset が呼ばれず、ダイアログ「はい」で1回呼ばれる
  - `sets_editRightSlot_canChoosePanel_leftCannot`
  - `lists_dockLimitedTo4`、`lists_panelLimitedTo5`
  - `permissions_listsFiveItems`：既定のホーム／通知へのアクセス／他のアプリの上に表示／既定のブラウザ／Bluetooth 接続
- [ ] **Step 2:** FAIL を確認
- [ ] **Step 3:** 実装。分割非対応アプリの警告は `ActivityInfo.resizeMode` が `RESIZE_MODE_UNRESIZEABLE` のとき表示。権限の遷移先：`Settings.ACTION_HOME_SETTINGS`、`ACTION_NOTIFICATION_LISTENER_SETTINGS`、`ACTION_MANAGE_OVERLAY_PERMISSION`、`RoleManager.createRequestRoleIntent(ROLE_BROWSER)`、Bluetooth は runtime permission 要求。「純正ランチャーに戻す」＝`ACTION_HOME_SETTINGS`
- [ ] **Step 4:** PASS を確認
- [ ] **Step 5:** Commit `feat(settings): 設定画面`

---

### Task 11: 安全装置と診断ログ

**Files:**
- Create: `safety/SafetyGuard.kt`, `diag/DiagLog.kt`
- Modify: `LauncherApp.kt`, `ui/home/HomeActivity.kt`
- Test: `safety/SafetyGuardTest.kt`, `diag/DiagLogTest.kt`

**Interfaces:**
- Produces:
  - `object SafetyGuard { fun shouldFallback(crashTimesSinceStart: List<Long>): Boolean; fun recordStart(); fun recordCrash(); fun clear(); val isFallbackActive: Boolean }`
  - `object DiagLog { fun d(tag: String, msg: String); fun dump(): List<String> }`（最大 500 行のリングバッファ、logcat タグ `GT8D`）

判定ルール：起動から 10 秒以内のクラッシュが **3 回連続** → 退避。10 秒を超えて動けば連続カウントを 0 に戻す。

- [ ] **Step 1: failing tests**
  - `threeQuickCrashes_fallback`：[2000, 3000, 9000] → true
  - `slowCrash_resetsStreak`：[2000, 15000, 3000] → false
  - `diagLog_keepsLast500`
- [ ] **Step 2:** FAIL を確認
- [ ] **Step 3:** 実装。`LauncherApp.onCreate` 先頭で `Thread.setDefaultUncaughtExceptionHandler` から recordCrash。HomeActivity は退避中なら `stockLauncherIntent()` を起動し、自動で最後のセットを開く処理をしない。設定画面の §6-5 の下に「安全モードを解除」（`clear()`）を追加
- [ ] **Step 4:** PASS を確認
- [ ] **Step 5:** Commit `feat(safety): 安全装置と診断ログ`

---

### Task 12: 車ボタンの枠・セットアップ・実機確認

**Files:**
- Create: `car/CarHomeAction.kt`, `scripts/setup-device.ps1`, `docs/superpowers/checklists/device-check.md`

**Interfaces:**
- Produces: `interface CarHomeAction { val isAvailable: Boolean; fun invoke() }`、既定実装 `UnknownCarHomeAction`（`isAvailable=false`）。Dock の `showCar` はこれに連動

- [ ] **Step 1:** CarHomeAction と既定実装を入れ、Dock に接続（車ボタンは非表示になる）
- [ ] **Step 2:** `setup-device.ps1`：`adb install -r`、`cmd notification allow_listener com.gt8d.launcher/.media.MediaListenerService`、`appops set com.gt8d.launcher SYSTEM_ALERT_WINDOW allow`、`cmd package set-home-activity com.gt8d.launcher/.ui.home.HomeActivity`。最後に「既定のブラウザは端末の設定で選択」と表示
- [ ] **Step 3:** `device-check.md` に仕様 §4（操作表の全行）・§7.1（全行）・§7.2 の確認項目をチェックボックスで列挙し、GT8D で全項目を確認してチェック
- [ ] **Step 4:** `.\gradlew.bat test assembleRelease` → BUILD SUCCESSFUL
- [ ] **Step 5:** Commit `feat: 車ボタンの枠・セットアップ・実機確認`

---

### Task 13（納車後 2026-11-21 以降）：車での実験（仕様 §10.2 ⑧〜⑪）

**Files:**
- Create: `docs/superpowers/spikes/car-spikes.md`
- Modify（結果次第）：`car/CarHomeAction.kt`、`edge/EdgeStripService.kt`、`ui/panel/*`

- [ ] **Step 1:** ⑧：純正ランチャーの車ボタンを押す前後で `logcat -c` → 押下 → `logcat -d` を取得し、呼び出し（Intent・ブロードキャスト等）を特定。特定できたら `CarHomeAction` の実装を追加し `isAvailable=true`
- [ ] **Step 2:** ⑨：車の画面で帯のスワイプ・長押し、2本指スワイプを試し記録。届きにくければ設定で帯幅を上げる
- [ ] **Step 3:** ⑩：車接続中に `adb shell wm size` と `dumpsys display` の解像度を記録し、パネル・ホームが崩れないことを確認
- [ ] **Step 4:** ⑪：CarPlay 接続時に自作ランチャーが表示されるか確認。Launcher3 が前面に来る場合は記録してユーザーに相談
- [ ] **Step 5:** `car-spikes.md` に結果を記録して Commit `spike: 車での実験⑧〜⑪`
