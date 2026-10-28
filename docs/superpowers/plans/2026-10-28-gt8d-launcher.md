# GT8D カスタムランチャー Implementation Plan（r2）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** GT8D（Android 15 車載AI Box）用に、メーカーの分割入口で「左アプリ｜右アプリ or パネル」の画面セットを比率つきで切り替える、ダーク固定のホームランチャーを作る。

**Architecture:** Kotlin＋Jetpack Compose の単一アプリ。判断の部分（設定・巡回・方式の選択・幕の時間制御・セットの起動手順・起動時の手順・パネルの転送・リンクの振り分け・安全装置）は Android に依存しない純粋な Kotlin として JVM テストで固め、Activity／Service／Android API の呼び出しは、それを呼ぶ薄い層（インターフェースの実装）にする。分割は `VendorBridge` 経由でメーカー入口（E）を呼び、使えなければ左だけ全画面、準備が揃えばアプリペア方式（D）に退避する。

**Tech Stack:** Kotlin 2.1、AGP 8.7、Jetpack Compose（BOM 2025.01）、DataStore 1.1、kotlinx.serialization 1.7、kotlinx-coroutines-test 1.9、JUnit4、Robolectric 4.14＋compose-ui-test

**Spec:** `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md`（r2.1）。実験の記録：`docs/superpowers/spikes/home-spikes.md`、`docs/superpowers/spikes/vendor-split-entry.md`

**実装体制:** Claude Code＝タスクの割り振り・レビュー・実機操作・コミット判断／Antigravity＝各タスクのコード（`agy-run tasks/NNN-xxx.md`。作る・変えるファイルは `.allow` に1つずつ列挙。完了後は `agy-verify` と差分レビュー）。実機（adb）の操作を含むステップは Claude Code が行う。

## Global Constraints

- minSdk 35 / targetSdk 35 / compileSdk 35、JDK 17。applicationId・パッケージ：`com.gt8d.launcher`
- ビルドとテストは WSL で `/home/munde/tools/gradle-run.sh <引数>`（`./gradlew` を直接呼ばない）
- 端末操作は Windows の `adb.exe -s 192.168.137.248:5555`（WSL の adb は届かない）。再起動のたびにユーザーが端末でワイヤレスデバッグを有効化する
- UI 文言は日本語。ダーク固定（ライトテーマ・`isSystemInDarkTheme` 分岐を作らない）。配色は `Palette`（背景 `#0B0B0F`〜`#17171D`、パネル `#2E2E38`、アクセント `#8FE63B`）
- タッチ領域は最小 72dp 角（`Dims.MinTouch`）、本文 20sp 以上（`Dims.BodyText`）、時計は特大。ぼかし等の重い効果は使わない
- `com.android.launcher3` は Recents 担当として残す。コード・手順のどこでも無効化・削除しない（E の受け口もこのプロセスにある）
- 走行中でも動作を変えない（速度・走行状態による分岐を一切入れない）
- 署名鍵はリポジトリ外。`keystore.properties`（リポジトリ外パスを指す）から読み、無ければ debug 署名
- メーカー入口の値（綴りは原文のまま）：パッケージ `com.sd.factoryconfig`、クラス `com.sd.factoryconfig.SplitActivity`、extras `split_shortcur_package`＝`"左|右"`、`split_percent`＝`"3:7"/"2:3"/"1:1"/"3:2"/"7:3"`
- 比率の表示と E の値：3:7→`3:7`、4:6→`2:3`、5:5→`1:1`、6:4→`3:2`、7:3→`7:3`。右がパネルのときは 4:6・5:5・6:4 のみ
- 幕：フェードイン 120ms、フェードアウト 150ms、進行線は 0.8 秒超で表示、時間で外す目安は暖かい 0.8 秒／冷えた 3.5 秒、**安全タイマー 4 秒（最初の呼び出しから合計。延長しない）**
- 起動時：カウントダウン 3 秒、上限 10 秒。パネルの転送の待ち 1.5 秒。連続失敗 3 回で E を使えない扱い
- debug 専用の実験コード（`app/src/debug/.../spike/`）は release に含めない。製品コードから参照しない

## Review Focus

1. **幕が外れずに車の画面が固まる**（前面化イベントが来ない、サービスが例外で止まる）→ 何があっても 4 秒で外れる。→ Task 12 `safety_hidesAt4000_evenWithoutReady`、Task 13 `curtainWindow_hideInFinally_onException`
2. **アップデートで v1 の設定が消える** → v1 は移行され、設定が残り、退避ファイルも作らない。→ Task 8 `migrate_v1_to_v2_keepsSettings`、`load_v1File_doesNotBackUp`
3. **冷えた起動でパネルが自分でホームに逃げる**（E がパネルをいったん全画面で起動する）→ 分割を開いている最中は転送しない。→ Task 15 `forwarder_noForwardWhileOpening`、Task 20 実験⑮
4. **メーカーアプリの更新で入口が消える／呼ぶと例外** → クラッシュせず、「分割が使えなくなりました」＋左だけ全画面。D は準備が揃うときだけ。次の起動で入口があれば E に自動復帰。→ Task 10 `entryMissing_switchesToLeftOnly_whenDNotReady`、`onBoot_entryFound_restoresVendor`、Task 14 `entryMissing_opensLeftOnlyAndShowsMessage`
5. **帯を素早く何度もスワイプ** → 行き先だけ更新され、起動は最後の行き先1回だけ、E を重ねて撃たない。→ Task 14 `rapidSwipes_onlyLastTargetLaunched_noOverlap`

---

## 完了済み（r1 計画で実施）

| r1 Task | 内容 | コミット |
|---|---|---|
| 0 | プロジェクト土台（Gradle、素材の res 変換、SmokeTest） | `12b5f59` |
| 1 | 家での実験（①〜⑦と追加実験。E・D の発見と検証） | `bb7e862`〜`941b707` |
| 2 | 設定モデル・既定値・保存（`config/`） | `96cd3f1` |
| 3 | セット巡回（`SetNavigator`） | `a94b75a` |
| 5 | 再生情報（`media/`。`NowPlaying.positionAt` は `SystemClock.elapsedRealtime()` 基準） | `253d0bd`、`5ea9f0e` |
| 6 | テーマとパネルの部品（`ui/theme/`、`ui/panel/`） | `5ea9f0e`、`2d77dd1` |

r1 の Task 4・7〜13 は、以下の Task 7〜21 に置き換える。

## File Structure（追加・変更分）

```
app/src/main/java/com/gt8d/launcher/
  diag/DiagLog.kt                    リングバッファ＋logcat（Task 7）
  safety/SafetyGuard.kt              連続クラッシュ判定と記録（Task 7）
  config/Model.kt / ConfigCodec.kt / Defaults.kt   v2・移行・比率（Task 8）
  split/SplitLauncher.kt             SplitLauncher・LaunchResult・FailReason（Task 9）
  split/ActivityLauncher.kt          起動の抽象と Android 実装（Task 9）
  split/VendorBridge.kt              メーカー入口のまとめ役（Task 9）
  split/SplitModeSelector.kt         方式の選択と失敗回数（Task 10）
  foreground/ForegroundWatcher.kt    前面化の判定・暖かい起動の記録（Task 11）
  foreground/UsageStatsForegroundEvents.kt   UsageStats 実装（Task 11）
  curtain/CurtainController.kt       幕の時間制御（Task 12）
  curtain/CurtainContent.kt          幕の Compose（Task 12）
  edge/EdgeGesture.kt / EdgeStripService.kt / CurtainWindow.kt / BootReceiver.kt（Task 13）
  sets/SetRunner.kt                  セットを開く手順（Task 14）
  ui/home/HomeActivity.kt、ui/panel/PanelActivity.kt、ui/panel/PanelForwarder.kt、ui/apps/AppListScreen.kt（Task 15）
  boot/BootSequence.kt、ui/home/BootScreen.kt（Task 16）
  link/LinkRouter.kt、link/BrowserActivity.kt（Task 17）
  settings/*（Task 18）
  pairs/AppPairService.kt、pairs/PairScanner.kt、pairs/AppPairSplitLauncher.kt（Task 19）
scripts/setup-device.ps1、docs/superpowers/checklists/device-check.md（Task 20）
```

共通の時間の抽象（Task 12 で定義し、以後のタスクが使う）：
```kotlin
// com.gt8d.launcher.time
fun interface Clock { fun nowMs(): Long }
interface Scheduler { fun schedule(delayMs: Long, action: () -> Unit): Cancellable }
fun interface Cancellable { fun cancel() }
```
テストでは `FakeScheduler`（`advanceBy(ms)` で予定を実行）を `app/src/test/java/com/gt8d/launcher/time/FakeScheduler.kt` に置く。

---

### Task 7: 診断ログと安全装置

**Files:**
- Create: `diag/DiagLog.kt`, `safety/SafetyGuard.kt`
- Modify: `LauncherApp.kt`
- Test: `diag/DiagLogTest.kt`, `safety/SafetyGuardTest.kt`

**Interfaces:**
- Produces:
  - `object DiagLog { fun d(tag: String, msg: String); fun dump(): List<String> }`（最大 500 行、logcat タグ `GT8D`）
  - `object SafetyGuard { fun shouldFallback(crashTimesSinceStart: List<Long>): Boolean; fun recordStart(context: Context); fun recordCrash(context: Context); fun clear(context: Context); fun isFallbackActive(context: Context): Boolean }`

判定ルール：起動から 10 秒以内のクラッシュが **3 回連続** → 退避。10 秒を超えて動けば連続カウントを 0 に戻す。

- [ ] **Step 1:** failing tests：`threeQuickCrashes_fallback`（[2000, 3000, 9000] → true）、`slowCrash_resetsStreak`（[2000, 15000, 3000] → false）、`diagLog_keepsLast500`（501 行書いて先頭が消える）
- [ ] **Step 2:** `gradle-run.sh test` → FAIL を確認
- [ ] **Step 3:** 実装。`LauncherApp.onCreate` 先頭で `recordStart`、`Thread.setDefaultUncaughtExceptionHandler` で `recordCrash` してから既定のハンドラへ渡す。記録は `filesDir/safety.json`
- [ ] **Step 4:** `gradle-run.sh test` → PASS
- [ ] **Step 5:** Commit `feat(safety): 診断ログと安全装置`

---

### Task 8: 設定 v2（比率・起動設定・v1 からの移行）

**Files:**
- Modify: `config/Model.kt`, `config/ConfigCodec.kt`, `config/Defaults.kt`
- Test: `config/ConfigCodecTest.kt`, `config/DefaultsTest.kt`, `config/SplitRatioTest.kt`, `config/ConfigRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 2 の `LauncherConfig`、`ScreenSet`、`ConfigCodec`、`ConfigRepository`
- Produces:
  - `const val CONFIG_VERSION = 2`
  - `@Serializable enum class SplitRatio(val label: String, val vendorValue: String) { R3_7("3:7","3:7"), R4_6("4:6","2:3"), R5_5("5:5","1:1"), R6_4("6:4","3:2"), R7_3("7:3","7:3"); companion object { val forPanel: List<SplitRatio> /* R4_6, R5_5, R6_4 */ } }`
  - `fun SplitRatio.allowedFor(right: Slot): Boolean`
  - `ScreenSet(id, left, right, ratio: SplitRatio = SplitRatio.R5_5, appPairLabel: String? = null)`
  - `@Serializable data class BootConfig(val waitForCar: Boolean = false)`、`LauncherConfig(version = CONFIG_VERSION, …, boot: BootConfig = BootConfig())`
  - `ConfigCodec.decode(json: String): LauncherConfig?` … **null は version > CONFIG_VERSION または壊れているときだけ**。version 1 は移行して返す（`ratio`=R5_5、`appPairLabel`=null、`boot`=既定）

- [ ] **Step 1:** failing tests
  - `ratio_vendorValues`：5 つの `vendorValue` が `3:7, 2:3, 1:1, 3:2, 7:3`
  - `panelRatios_only46_55_64`：`R3_7.allowedFor(Slot.Panel)==false`、`R4_6/R5_5/R6_4` は true、`Slot.App` ならすべて true
  - `migrate_v1_to_v2_keepsSettings`：r1 形式の v1 JSON（`ratio` 無し）を decode → screenSets・homeApps・link が元のまま、全セットの `ratio==R5_5`、`version==2`
  - `decode_version3_returnsNull`（既存の `"version":2` を null とするテストを、`"version":3` に置き換える）
  - `load_v1File_doesNotBackUp`：v1 の JSON を置いた `ConfigRepository` の config が v1 の中身（比率補完）で、`config.bak.json` が無い
  - `build_allInstalled_matchesSpec` を更新：セット1＝(YouTube｜Panel, R6_4)、セット2＝(YouTube｜Apple Music, R6_4)、セット3＝(YouTube｜Googleマップ, R5_5)
- [ ] **Step 2:** FAIL を確認
- [ ] **Step 3:** 実装（Json は `ignoreUnknownKeys = true` のまま、欠けたフィールドは既定値で埋まる。version 判定のみ変更）
- [ ] **Step 4:** PASS を確認
- [ ] **Step 5:** Commit `feat(config): 設定 v2 と v1 からの移行`

---

### Task 9: メーカー入口のまとめ役と分割の抽象

**Files:**
- Create: `split/SplitLauncher.kt`, `split/ActivityLauncher.kt`, `split/VendorBridge.kt`
- Modify: `AndroidManifest.xml`（`<queries><package android:name="com.sd.factoryconfig"/></queries>`）
- Test: `split/VendorBridgeTest.kt`

**Interfaces:**
- Consumes: `SplitRatio`（Task 8）
- Produces:
  - `enum class FailReason { EntryMissing, Exception, Timeout, OneSideMissing, NotReady }`
  - `sealed interface LaunchResult { data object Started : LaunchResult; data class Failed(val reason: FailReason) : LaunchResult }`
  - `interface SplitLauncher { fun open(left: String, right: String, ratio: SplitRatio): LaunchResult }`
  - `data class LaunchSpec(val pkg: String, val cls: String, val extras: Map<String, String>)`
  - `interface ActivityLauncher { fun canResolve(pkg: String, cls: String): Boolean; fun start(spec: LaunchSpec); fun startApp(pkg: String, adjacent: Boolean): Boolean; fun isResizeable(pkg: String): Boolean }`（`AndroidActivityLauncher(context)` が実装。`start` は `NEW_TASK`、`startApp` の adjacent は `NEW_TASK | LAUNCH_ADJACENT | MULTIPLE_TASK`、起動インテントが無ければ false。`isResizeable` は起動 Activity の `resizeMode != RESIZE_MODE_UNRESIZEABLE`）
  - `class VendorBridge(launcher: ActivityLauncher) { fun splitAvailable(): Boolean; fun openSplit(left: String, right: String, ratio: SplitRatio): LaunchResult; val carHomeAvailable: Boolean /* 実験⑧まで false */; fun switchToCarScreen(): Boolean /* 実験⑧まで false を返すだけ */ }`
  - `class VendorSplitLauncher(bridge: VendorBridge) : SplitLauncher`

- [ ] **Step 1:** failing tests（`FakeActivityLauncher` を使う）
  - `openSplit_buildsExpectedSpec`：`openSplit("a","b",R6_4)` → 1 回 `start`、spec＝(`com.sd.factoryconfig`, `com.sd.factoryconfig.SplitActivity`, {`split_shortcur_package`:"a|b", `split_percent`:"3:2"})、戻り値 `Started`
  - `openSplit_entryMissing_returnsEntryMissing_andDoesNotStart`
  - `openSplit_startThrows_returnsException`
  - `carHome_unavailableByDefault`：`carHomeAvailable==false`、`switchToCarScreen()==false`
- [ ] **Step 2:** FAIL を確認
- [ ] **Step 3:** 実装。すべての呼び出しと結果を `DiagLog.d("Vendor", …)` に記録
- [ ] **Step 4:** PASS を確認
- [ ] **Step 5:** Commit `feat(split): メーカー入口のまとめ役`

---

### Task 10: 方式の選択と失敗回数

**Files:**
- Create: `split/SplitModeSelector.kt`
- Test: `split/SplitModeSelectorTest.kt`

**Interfaces:**
- Consumes: `FailReason`（Task 9）
- Produces:
  - `enum class SplitMode { Vendor, AppPair, LeftOnly }`
  - `@Serializable data class SplitModeState(val vendorUsable: Boolean = true, val consecutiveFailures: Int = 0, val lastFailure: FailReason? = null)`
  - `interface SplitModeStore { fun load(): SplitModeState; fun save(state: SplitModeState) }`、`class FileSplitModeStore(file: File) : SplitModeStore`（壊れていたら既定値）
  - `class SplitModeSelector(store: SplitModeStore, dReady: () -> Boolean) { fun current(): SplitMode; val state: SplitModeState; fun onSuccess(); fun onFailure(reason: FailReason, countable: Boolean); fun onBoot(entryFound: Boolean); fun restoreVendor() }`

規則：`EntryMissing` は即 `vendorUsable=false`。それ以外は `countable` のときだけ連続回数を増やし、3 回で `vendorUsable=false`。`onSuccess` で回数 0。`current()`＝vendorUsable なら Vendor、そうでなく dReady なら AppPair、どちらでもなければ LeftOnly。`onBoot(true)` と `restoreVendor()` は `vendorUsable=true`・回数 0。`onBoot(false)` は `vendorUsable=false`。

- [ ] **Step 1:** failing tests
  - `threeCountableFailures_disableVendor`、`uncountableFailures_ignored`（Timeout を countable=false で 5 回 → Vendor のまま）、`success_resetsCount`
  - `entryMissing_switchesToLeftOnly_whenDNotReady`、`entryMissing_switchesToAppPair_whenDReady`
  - `onBoot_entryFound_restoresVendor`（使えない状態・回数 2 → Vendor・回数 0）、`restoreVendor_manual`
  - `state_persistsAcrossInstances`（`FileSplitModeStore` で保存→新しいインスタンスで読める）
- [ ] **Step 2:** FAIL を確認
- [ ] **Step 3:** 実装
- [ ] **Step 4:** PASS を確認
- [ ] **Step 5:** Commit `feat(split): 方式の選択と失敗回数`

---

### Task 11: 前面化の検知と暖かい起動の記録

**Files:**
- Create: `foreground/ForegroundWatcher.kt`, `foreground/UsageStatsForegroundEvents.kt`
- Test: `foreground/ForegroundWatcherTest.kt`

**Interfaces:**
- Produces:
  - `data class ResumeEvent(val pkg: String, val timeMs: Long)`
  - `interface ForegroundEvents { fun available(): Boolean; fun resumedSince(sinceMs: Long): List<ResumeEvent> }`（`UsageStatsForegroundEvents(context)`：`AppOpsManager` の `GET_USAGE_STATS` が許可なら available、`UsageStatsManager.queryEvents` の `ACTIVITY_RESUMED` を返す）
  - `class ForegroundWatcher(events: ForegroundEvents) { fun available(): Boolean; fun bothResumed(left: String, right: String, sinceMs: Long): Boolean; fun lastForegroundSince(sinceMs: Long): String? }`
  - `class WarmTracker(ownPkg: String, ownTaskExists: () -> Boolean) { fun markForeground(pkg: String); fun isWarm(pkg: String): Boolean; fun expectedDelayMs(left: String, right: String): Long /* 両方暖かい 800、それ以外 3500 */ }`

- [ ] **Step 1:** failing tests
  - `bothResumed_trueOnlyWhenBothAfterSince`（片方だけ／since より前は false）
  - `unavailable_bothResumedFalse`
  - `warm_bothMarked_800`、`cold_oneUnmarked_3500`、`ownPkg_warmWhenOwnTaskExists`、`ownPkg_coldWhenNoTask_3500`
- [ ] **Step 2:** FAIL を確認
- [ ] **Step 3:** 実装
- [ ] **Step 4:** PASS を確認
- [ ] **Step 5:** Commit `feat(foreground): 前面化の検知と暖かい起動の記録`

---

### Task 12: 切り替えの幕（時間制御と表示）

**Files:**
- Create: `time/Time.kt`（上記 `Clock`・`Scheduler`・`Cancellable`）、`curtain/CurtainController.kt`, `curtain/CurtainContent.kt`
- Test: `time/FakeScheduler.kt`, `curtain/CurtainControllerTest.kt`, `curtain/CurtainContentTest.kt`（Robolectric）

**Interfaces:**
- Consumes: `SplitRatio`（Task 8）、`Palette`・`Dims`（Task 6）
- Produces:
  - `sealed interface SlotPreview { data class App(val pkg: String, val label: String) : SlotPreview; data object Panel : SlotPreview }`
  - `data class CurtainTarget(val setName: String, val left: SlotPreview, val right: SlotPreview, val ratio: SplitRatio, val position: Int, val count: Int)`
  - `enum class HideReason { Ready, Timed, Safety, Failed }`
  - `interface CurtainView { fun show(target: CurtainTarget); fun update(target: CurtainTarget); fun showProgress(); fun hide(reason: HideReason) }`
  - `class CurtainController(scheduler: Scheduler, view: CurtainView) { fun show(target: CurtainTarget, expectedMs: Long, watcherAvailable: Boolean); fun update(target: CurtainTarget); fun ready(); fun fail(); val isShowing: Boolean; var onSafetyTimeout: (() -> Unit)? }`
  - 定数 `SAFETY_MS = 4000L`、`PROGRESS_AFTER_MS = 800L`
  - `@Composable fun CurtainContent(target: CurtainTarget, showProgress: Boolean)`

規則：`show` で安全タイマー（4000ms）と進行線タイマー（800ms）を開始。`watcherAvailable==false` のときだけ `expectedMs` で `hide(Timed)`。`update` はタイマーを延長しない。`ready()` は即 `hide(Ready)`。安全タイマーで `hide(Safety)` し `onSafetyTimeout` を呼ぶ。hide 後は全タイマーを取り消す。

- [ ] **Step 1:** failing tests
  - `safety_hidesAt4000_evenWithoutReady`（3999ms で表示中、4000ms で `Safety`、`onSafetyTimeout` 1 回）
  - `update_doesNotExtendSafety`（3000ms で update → 4000ms で Safety）
  - `timedHide_whenWatcherUnavailable_atExpected`（expected 800 → 800ms で `Timed`）、`noTimedHide_whenWatcherAvailable`
  - `ready_hidesImmediately_andCancelsTimers`、`progress_shownAfter800_only`
  - `curtainContent_showsSetNameAndRatioBlocks`（testTag `curtain_set_name`、`curtain_left`・`curtain_right` の幅の比が R6_4 で 0.6±0.02、`curtain_progress` は showProgress=true のときだけ）
- [ ] **Step 2:** FAIL を確認
- [ ] **Step 3:** 実装（配置図＝比率どおりの2ブロックにアイコン・アプリ名、Panel はパネルの図。セット名 32sp 以上、位置の点、背景は `R.drawable.wallpaper` を暗く。ぼかし不可）
- [ ] **Step 4:** PASS を確認
- [ ] **Step 5:** Commit `feat(curtain): 切り替えの幕`

---

### Task 13: スワイプ帯と幕の窓

**Files:**
- Create: `edge/EdgeGesture.kt`, `edge/EdgeStripService.kt`, `edge/CurtainWindow.kt`, `edge/BootReceiver.kt`
- Modify: `AndroidManifest.xml`（`SYSTEM_ALERT_WINDOW`、`FOREGROUND_SERVICE`・`FOREGROUND_SERVICE_SPECIAL_USE`、`RECEIVE_BOOT_COMPLETED`、サービス `specialUse`、BootReceiver）
- Test: `edge/EdgeGestureTest.kt`, `edge/CurtainWindowTest.kt`

**Interfaces:**
- Consumes: `GestureConfig`（Task 2）、`CurtainView`・`CurtainContent`（Task 12）
- Produces:
  - `enum class Side { Left, Right }`、`enum class EdgeAction { Next, Prev, Home, None }`
  - `object EdgeGesture { fun classify(side: Side, downX: Float, downY: Float, upX: Float, upY: Float, durationMs: Long, longPressMs: Int, density: Float): EdgeAction }`
  - `class EdgeStripService : Service`、`companion object { fun ensureRunning(context: Context); var listener: ((EdgeAction) -> Unit)?; val curtainView: CurtainView? }`
  - `class CurtainWindow(windowManager: WindowManagerFacade) : CurtainView`（`TYPE_APPLICATION_OVERLAY`、全画面、タッチ遮断。帯の窓は幕の追加後に付け直して上に置く）

判定ルール（r1 と同じ）：内向き移動 ≥ 48dp かつ |dy| < 内向き移動の 0.5 倍 → 左帯は Next、右帯は Prev。移動 < 16dp かつ押下時間 ≥ longPressMs → Home。それ以外 None。

- [ ] **Step 1:** failing tests
  - `classify_leftInward_isNext`、`classify_rightInward_isPrev`、`classify_longPress_isHome`（4dp・1100ms）、`classify_tapOrDiagonal_isNone`（4dp・100ms／内向き 60dp・縦 50dp／外向き 60dp）
  - `curtainWindow_hideInFinally_onException`（`FakeWindowManagerFacade` の `addView` 後、描画で例外 → `removeView` が呼ばれる）
  - `curtainWindow_stripsReaddedAboveCurtain`（show 後に帯の窓が remove→add される）
- [ ] **Step 2:** FAIL を確認
- [ ] **Step 3:** 実装。帯は左右に幅 `stripWidthPx`・高さ全面、`FLAG_NOT_FOCUSABLE`。glow=ON なら押下中に `Palette.Accent` 30% でフェード。通知は「GT8D Launcher 動作中」1件。BootReceiver は `ensureRunning` のみ（E の再判定は Task 16）
- [ ] **Step 4:** PASS を確認
- [ ] **Step 5:** Commit `feat(edge): スワイプ帯と幕の窓`

---

### Task 14: セットを開く手順（SetRunner）

**Files:**
- Create: `sets/SetRunner.kt`
- Test: `sets/SetRunnerTest.kt`

**Interfaces:**
- Consumes: `SetNavigator`（Task 3）、`LauncherConfig`・`SplitRatio`（Task 8）、`SplitLauncher`・`VendorSplitLauncher`・`ActivityLauncher`（Task 9）、`SplitModeSelector`（Task 10）、`ForegroundWatcher`・`WarmTracker`（Task 11）、`CurtainController`・`CurtainTarget`（Task 12）、`Scheduler`・`Clock`
- Produces:
  - `data class Opening(val left: String, val right: String, val startedAtMs: Long)`
  - `interface LastIndexStore { var lastSetIndex: Int }`（`ConfigRepository` が満たす）
  - `interface Messenger { fun showSplitUnavailable(); fun showSwitchFailed() }`（パネル上部の短い表示）
  - `class SetRunner(deps: SetRunnerDeps) { fun next(); fun prev(); fun open(index: Int); fun home(); fun openForBoot(index: Int); fun panelResumedInSplit(); val opening: StateFlow<Opening?>; val currentIndex: Int; val lastOpenedPair: Pair<String, String>? }`
  - `data class SetRunnerDeps(val config: () -> LauncherConfig, val installed: () -> Set<String>, val lastIndex: LastIndexStore, val selector: SplitModeSelector, val vendor: SplitLauncher, val appPair: SplitLauncher?, val apps: ActivityLauncher, val watcher: ForegroundWatcher, val warm: WarmTracker, val curtain: CurtainController, val scheduler: Scheduler, val clock: Clock, val ownPkg: String, val startHome: () -> Unit, val messenger: Messenger)`

手順（仕様 §4.3・§5.3・§7.1）：
1. 行き先を決め `lastSetIndex` を保存、`curtain.show(target, warm.expectedDelayMs(l, r), watcher.available())`、`opening` を設定
2. 右が Panel なら右のパッケージは `ownPkg`。左が未インストールなら右だけ全画面（右が Panel ならホーム）、右が未インストールなら左だけ全画面。左右どちらかが分割非対応（`apps.isResizeable` が false）なら左だけ全画面＋`messenger.showSplitUnavailable()`
3. `selector.current()`：Vendor→`vendor.open`、AppPair→`appPair.open`、LeftOnly→`apps.startApp(left,false)`＋`messenger.showSplitUnavailable()`
4. 200ms ごとに `watcher.bothResumed(l, r, startedAt)` を確認（右が Panel のときは `panelResumedInSplit()` も右の合図）。揃ったら `curtain.ready()`・`selector.onSuccess()`・`warm.markForeground` 両方・`opening=null`
5. 3000ms で未成立かつ watcher が使えるなら 1 回だけ同じ方式で呼び直す（幕は延長しない）
6. `curtain.onSafetyTimeout` → `selector.onFailure(Timeout, countable = watcher.available())`・`messenger.showSwitchFailed()`・`opening=null`
7. `Failed(reason)` が返ったら幕を `fail()`、`selector.onFailure(reason, countable=true)`、左だけ全画面＋`showSplitUnavailable()`
8. 実行中（`opening != null`）の `next/prev/open` は行き先だけ更新（`curtain.update`）。完了または失敗の後、最後の行き先が今のセットと違えばそれを開く
9. `home()`：幕を短く出し `startHome()`、すぐ `curtain.ready()`
10. `openForBoot(index)`：幕を出さずに 1〜8 と同じ手順で開く（起動時用。失敗の数え方は Task 16 の規則に従い、呼び出し側が判断する）

- [ ] **Step 1:** failing tests（すべて Fake／`FakeScheduler`）
  - `next_showsCurtainAndCallsVendor_withVendorRatio`（セット2＝R6_4 → vendor に R6_4、`lastSetIndex` 保存）
  - `panelSet_passesOwnPkgAsRight`
  - `rapidSwipes_onlyLastTargetLaunched_noOverlap`（実行中に next×3 → vendor 呼び出しは最初の1回、完了後に最後の行き先で1回、計2回。`curtain.update` が3回）
  - `entryMissing_opensLeftOnlyAndShowsMessage`
  - `leftUninstalled_opensRightAlone`、`rightUninstalled_opensLeftAlone`
  - `bothResumed_hidesCurtain_andRecordsSuccess`
  - `notReady_retriesOnceAt3000_noCurtainExtension`（vendor 2 回、安全タイマーは 4000 のまま）
  - `safety_recordsCountableFailureOnlyWhenWatcherAvailable`
  - `openingFlag_setDuringOpen_clearedAfter`
  - `home_startsHome_andHidesCurtain`
  - `unresizeableApp_opensLeftOnly`
  - `openForBoot_doesNotShowCurtain`
- [ ] **Step 2:** FAIL を確認
- [ ] **Step 3:** 実装（呼び出しと結果を `DiagLog` に記録）
- [ ] **Step 4:** PASS を確認
- [ ] **Step 5:** Commit `feat(sets): セットを開く手順`

---

### Task 15: ホーム・パネル画面・アプリ一覧

**Files:**
- Create: `ui/home/HomeActivity.kt`, `ui/panel/PanelActivity.kt`, `ui/panel/PanelForwarder.kt`, `ui/apps/AppListScreen.kt`, `AppGraph.kt`（アプリ全体の部品を1つ組み立てる）
- Modify: `AndroidManifest.xml`, `LauncherApp.kt`, `ui/panel/PanelContent.kt`（最小幅 516dp）
- Test: `ui/panel/PanelForwarderTest.kt`, `ui/apps/AppListScreenTest.kt`, `ui/panel/PanelContentTest.kt`

**Interfaces:**
- Consumes: Task 2〜14
- Produces:
  - `HomeActivity`（`MAIN`＋`HOME`＋`DEFAULT`、`singleTask`）
  - `PanelActivity`（`MAIN`＋`LAUNCHER`、`android:label="GT8D パネル"`、`resizeableActivity=true`。自作アプリのランチャー用の入口はこれ1つ）
  - `class PanelForwarder(opening: () -> Opening?, scheduler: Scheduler) { fun onShown(inMultiWindow: Boolean, forward: () -> Unit); fun onMultiWindowChanged(inMultiWindow: Boolean); fun onOpeningCleared(); fun onHidden() }` 定数 `NO_FLAG_WAIT_MS = 1500L`
  - `object AppGraph { fun get(context: Context): AppGraphInstance }`（`ConfigRepository`・`SetRunner`・`SplitModeSelector` 等を1つずつ。`ConfigRepository` はここでだけ作る）
  - `fun stockLauncherIntent(): Intent`（`com.android.launcher3/.uioverrides.QuickstepLauncher` 明示）

PanelForwarder の規則（仕様 §5.2）：分割の中なら何もしない。`opening` があるあいだは転送しない。`onOpeningCleared` の時点で分割の外なら転送。`opening` が無い状態で分割の外に出たら 1500ms 待ち、その間に分割に入らなければ転送。

- [ ] **Step 1:** failing tests
  - `forwarder_noForwardWhileOpening`、`forwarder_forwardAfterOpeningClearedIfOutside`、`forwarder_noFlag_waits1500_thenForward`、`forwarder_enteringSplitWithin1500_cancels`、`forwarder_inSplit_neverForwards`
  - `appList_alwaysIncludesStockLauncher`、`appList_sortedByLabel`、`appList_excludesOwnPanel`
  - `PanelContentTest.panel_narrowWidth_noOverflow` の幅を 576dp → **516dp** に変更
- [ ] **Step 2:** FAIL を確認
- [ ] **Step 3:** 実装。ホーム＝左 `IconGrid`（homeApps）＋右 `PanelContent`、背景 `R.drawable.wallpaper`（`ContentScale.Crop`）。パネルのアイコン：分割の中なら `apps.startApp(pkg, adjacent=true)`（左を入れ替え、幕なし）、そうでなければ `adjacent=false`。PanelActivity の `onResume` で分割の中なら `SetRunner.panelResumedInSplit()`。2本指の左右スワイプで `next/prev`。両 Activity の `onResume` で `EdgeStripService.ensureRunning`、帯の listener を `SetRunner` に接続。`PanelState.needsPermission` は「通知へのアクセス・他のアプリの上に表示・使用状況へのアクセス」のどれかが未許可なら true（タップで設定の許可チェックへ）。`Messenger` はパネル上部の短い表示で実装。`MediaCard` の位置は `SystemClock.elapsedRealtime()` で `NowPlaying.positionAt`、☆の識別子に `com.apple.android.music.playback.action.FAVORITE` を `MediaWatcher` に設定。`<queries>` に MAIN/LAUNCHER と http VIEW
- [ ] **Step 4:** PASS を確認
- [ ] **Step 5:** Commit `feat(ui): ホーム・パネル画面・アプリ一覧`

---

### Task 16: 起動時の手順

**Files:**
- Create: `boot/BootSequence.kt`, `ui/home/BootScreen.kt`
- Modify: `edge/BootReceiver.kt`, `ui/home/HomeActivity.kt`
- Test: `boot/BootSequenceTest.kt`

**Interfaces:**
- Consumes: `SetRunner`（Task 14）、`SplitModeSelector`・`VendorBridge`（Task 9・10）、`SafetyGuard`（Task 7）、`ForegroundWatcher`
- Produces:
  - `enum class BootPhase { Countdown, Opening, Done, StayedHome }`
  - `class BootSequence(scheduler: Scheduler, safetyActive: () -> Boolean, openLast: () -> Unit, splitAppeared: () -> Boolean, goHome: () -> Unit, record: (String) -> Unit) { fun start(); fun openNow(); fun stayHome(); val phase: StateFlow<BootPhase>; val secondsLeft: StateFlow<Int> }` 定数 `COUNTDOWN_MS = 3000L`、`BOOT_LIMIT_MS = 10000L`
  - `@Composable fun BootScreen(target: CurtainTarget, secondsLeft: Int, onOpenNow: () -> Unit, onStayHome: () -> Unit)`（タッチを遮らない普通の画面。testTag `boot_open_now`、`boot_stay_home`）

規則：`safetyActive` なら何もしない（StayedHome）。HomeActivity は SafetyGuard の退避中なら `stockLauncherIntent()` を起動する（仕様 §7.2）。3 秒後に `openLast`（`openNow` で即時）。カウントダウン中の `stayHome` は `openLast` を呼ばない。`openLast` 後の `stayHome` は取り消しとして記録し、`splitAppeared()` が true になった時点か 10 秒で `goHome`。10 秒で分割が出なければ記録して Done。**起動時の時間切れ・取り消しは `selector.onFailure` に渡さない**。BootReceiver は `selector.onBoot(bridge.splitAvailable())` を呼ぶ。HomeActivity は起動後の最初の表示で `BootSequence.start()`（`lastSetIndex` が 0 なら起動画面を出さない）。

- [ ] **Step 1:** failing tests
  - `countdown_opensAfter3000`、`openNow_opensImmediately`
  - `stayHomeDuringCountdown_neverOpens`
  - `stayHomeAfterOpen_goesHomeWhenSplitAppears`、`stayHomeAfterOpen_goesHomeAt10000`
  - `bootTimeout_recordsAndStops_withoutFailureCount`
  - `safetyActive_doesNothing`
  - `homeActivity_safetyActive_startsStockLauncher`（Robolectric。退避中に HomeActivity を起動すると `com.android.launcher3/.uioverrides.QuickstepLauncher` の明示 Intent が出る）
- [ ] **Step 2:** FAIL を確認
- [ ] **Step 3:** 実装（`openLast` は `SetRunner.openForBoot(lastSetIndex)`。起動時は幕を使わない）
- [ ] **Step 4:** PASS を確認
- [ ] **Step 5:** Commit `feat(boot): 起動時の手順`

---

### Task 17: リンクの受け口と内蔵ブラウザ

**Files:**
- Create: `link/LinkRouter.kt`, `link/BrowserActivity.kt`（分割の右用・全画面用の2つの Activity クラス）
- Modify: `AndroidManifest.xml`
- Test: `link/LinkRouterTest.kt`

**Interfaces:**
- Consumes: `LinkConfig`（Task 2）、`SetRunner.lastOpenedPair`（Task 14）、`ForegroundWatcher`（Task 11）、`VendorBridge`（Task 9）
- Produces:
  - `sealed interface FrontState { data object Home; data object AppPanel; data class AppApp(val left: String, val right: String); data class Fullscreen(val pkg: String); data object Unknown }`
  - `data class FrontClues(val homeResumed: Boolean, val panelInSplit: Boolean, val lastPair: Pair<String, String>?, val otherForegroundAfterLastPair: Boolean, val referrer: String?)`
  - `sealed interface LinkRoute { data object Chrome; data object FullscreenBrowser; data object PanelTaskBrowser; data class VendorSplitThenBrowser(val left: String) }`
  - `object LinkRouter { fun detectFront(clues: FrontClues): FrontState; fun route(url: String, cfg: LinkConfig, front: FrontState): LinkRoute }`
  - `BrowserActivity`（分割の右用。`taskAffinity` はパネルと同じ、`NEW_TASK` なし）、`FullscreenBrowserActivity`（別の `taskAffinity`）。両方に「Chromeで開き直す」（`com.android.chrome` 明示）

- [ ] **Step 1:** failing tests
  - `route_chromeMode_alwaysChrome`、`route_googleLogin_goesChrome`
  - `detect_homeResumed_isHome`、`detect_panelInSplit_isAppPanel`、`detect_lastPairAndReferrerMatches_isAppApp`、`detect_referrerOnly_isFullscreen`、`detect_noClues_isUnknown`
  - `route_home_fullscreenBrowser`、`route_appPanel_panelTaskBrowser`、`route_appApp_vendorSplitWithLeft`、`route_fullscreen_vendorSplitWithThatApp`、`route_unknown_fullscreenBrowser`
- [ ] **Step 2:** FAIL を確認
- [ ] **Step 3:** 実装。`VendorSplitThenBrowser(left)`＝`SetRunner` の幕つきで `vendor.open(left, ownPkg, R5_5)` を呼び、パネルのタスクに `BrowserActivity` を積む（PanelActivity が保留中の URL を受け取って `BrowserActivity` を開く）。E が使えないときは `FullscreenBrowser`
- [ ] **Step 4:** PASS を確認。実機で「全画面のブラウザから戻るとホーム」「分割の右のブラウザから戻るとパネル（転送されない）」を確認（Claude Code）
- [ ] **Step 5:** Commit `feat(link): リンクの受け口と内蔵ブラウザ`

---

### Task 18: 設定画面

**Files:**
- Create: `settings/SettingsActivity.kt`, `settings/SetsScreen.kt`, `settings/ListsScreen.kt`, `settings/LinkScreen.kt`, `settings/GestureScreen.kt`, `settings/BootScreenSettings.kt`, `settings/PermissionsScreen.kt`, `settings/SplitStatusSection.kt`
- Test: `settings/SettingsScreensTest.kt`

**Interfaces:**
- Consumes: `ConfigRepository`（Task 2・8）、`SplitModeSelector`（Task 10）、`ForegroundWatcher.available()`（Task 11）、`stockLauncherIntent()`・`SafetyGuard.clear`
- Produces: `SettingsActivity`（extra `open_new_set=true` でセット新規作成から開始、保存時に新セットの index を result で返す）。D の準備の画面（§6-7）は Task 19 で足すため、ここでは入口（ボタン）だけ置く

仕様 §6 の 1〜10 をこの順で1画面のリスト＋詳細画面にする。

- [ ] **Step 1:** failing tests
  - `reset_requiresConfirm`（「デフォルトに戻す」だけでは reset されず、ダイアログ「はい」で1回）
  - `sets_editRightSlot_canChoosePanel_leftCannot`
  - `sets_ratioChoices_panelHas3_appHas5`（表示は「3:7・4:6・5:5・6:4・7:3」）
  - `lists_dockLimitedTo4`、`lists_panelLimitedTo5`
  - `permissions_listsItems`（既定のホーム／通知へのアクセス／他のアプリの上に表示／既定のブラウザ／Bluetooth 接続／使用状況へのアクセス。D のときだけアクセシビリティ）
  - `splitStatus_showsMode_andRestoreButton`（Vendor＝「メーカー方式で動作中」、LeftOnly＝「分割が使えなくなりました」、AppPair＝「予備方式で動作中」。「メーカー方式に戻す」で `restoreVendor`）
- [ ] **Step 2:** FAIL を確認
- [ ] **Step 3:** 実装。分割非対応アプリの警告は `RESIZE_MODE_UNRESIZEABLE`。遷移先：`ACTION_HOME_SETTINGS`、`ACTION_NOTIFICATION_LISTENER_SETTINGS`、`ACTION_MANAGE_OVERLAY_PERMISSION`、`RoleManager.createRequestRoleIntent(ROLE_BROWSER)`、`ACTION_USAGE_ACCESS_SETTINGS`、`ACTION_ACCESSIBILITY_SETTINGS`、Bluetooth は runtime permission。「安全モードを解除」＝`SafetyGuard.clear`
- [ ] **Step 4:** PASS を確認
- [ ] **Step 5:** Commit `feat(settings): 設定画面`

---

### Task 19: 退避策 D（アプリペア）

**Files:**
- Create: `pairs/PairScanner.kt`, `pairs/AppPairService.kt`, `pairs/AppPairSplitLauncher.kt`, `pairs/DReadiness.kt`, `settings/AppPairPrepScreen.kt`, `res/xml/app_pair_service.xml`
- Modify: `AndroidManifest.xml`, `AppGraph.kt`
- Test: `pairs/PairScannerTest.kt`, `pairs/DReadinessTest.kt`

**Interfaces:**
- Consumes: `SplitLauncher`・`LaunchResult`（Task 9）、`ScreenSet.appPairLabel`（Task 8）、`SplitModeSelector` の `dReady`（Task 10）
- Produces:
  - `object PairScanner { fun pairLabels(nodeTexts: List<String>): List<String> }`（`|` を1つだけ含み、両側が空でない文字列だけ。重複除去）
  - `class DReadiness(a11yEnabled: () -> Boolean, sets: () -> List<ScreenSet>, scanned: () -> Set<String>) { fun ready(): Boolean }`（a11y ON かつ全セットの `appPairLabel` が非 null で scanned に含まれる）
  - `class AppPairService : AccessibilityService`（タッチ探索なし、`canRetrieveWindowContent=true`、`typeWindowStateChanged`。`fun scan(): List<String>`、`fun clickLabel(label: String): Boolean`。HOME ではなく `stockLauncherIntent()` で純正ランチャーを開いてから探す）
  - `class AppPairSplitLauncher(labelFor: (left: String, right: String) -> String?, service: () -> AppPairService?) : SplitLauncher`（ラベル無し→`Failed(NotReady)`、サービス無し→`Failed(NotReady)`、クリック失敗→`Failed(EntryMissing)`）

- [ ] **Step 1:** failing tests
  - `pairLabels_extractsOnlyPairs`（`["設定","YouTube|Apple Music","YouTube|GT8D パネル","a|","|b","x|y|z"]` → 2件）
  - `ready_requiresA11yAndAllLabels`（a11y OFF→false、1セットだけラベル無し→false、全部揃う→true）
  - `appPairLauncher_noLabel_notReady`
- [ ] **Step 2:** FAIL を確認
- [ ] **Step 3:** 実装。設定 §6-7：許可の案内、ペアの作り方の案内、「ペアを走査する」→ 見つかったラベルを各セットに割り当て（`appPairLabel` を保存）
- [ ] **Step 4:** PASS を確認。実機で、E を一時的に使えない扱い（`SplitModeState` を手で書き換え）にし、D でセットが開くことを確認（Claude Code）
- [ ] **Step 5:** Commit `feat(pairs): 退避策 D`

---

### Task 20: 実機の最初の確認・セットアップ・チェックリスト

**Files:**
- Create: `scripts/setup-device.ps1`, `docs/superpowers/checklists/device-check.md`, `docs/superpowers/spikes/first-checks.md`
- Modify（結果次第）：`foreground/*`、`curtain/*`、`boot/*`、`ui/panel/PanelForwarder.kt`

**Interfaces:**
- Produces: `first-checks.md`（⑫〜⑮の結果）

- [ ] **Step 1:** `setup-device.ps1`：`adb install -r`、`cmd notification allow_listener com.gt8d.launcher/com.gt8d.launcher.media.MediaListenerService`、`appops set com.gt8d.launcher SYSTEM_ALERT_WINDOW allow`、`appops set com.gt8d.launcher GET_USAGE_STATS allow`、`cmd package set-home-activity com.gt8d.launcher/.ui.home.HomeActivity`。最後に「既定のブラウザは端末の設定で選択」と表示
- [ ] **Step 2:** release ビルドを入れてセットアップ（Claude Code、実機）
- [ ] **Step 3:** ⑫ 使用状況の前面化イベント：E の後に左右両方の `ACTIVITY_RESUMED` が 4 秒以内に取れるか。取れなければ `UsageStatsForegroundEvents.available()` を false にする扱いを確認
- [ ] **Step 4:** ⑬ 幕の下で E が成立するか（`screenrecord --output-format=raw-frames` のコマ送りで、純正ホームが1コマも映らないこと）
- [ ] **Step 5:** ⑭ 起動直後に最後のセットが 10 秒以内に開くか（ユーザーに再起動とワイヤレスデバッグの有効化を依頼）
- [ ] **Step 6:** ⑮ 右パネルのセットを冷えた状態（`am force-stop com.gt8d.launcher` 後ではなく、パネルのタスクが無い状態）で開き、パネルが途中でホームに転送されないこと
- [ ] **Step 7:** `first-checks.md` に ⑫〜⑮ の「合格／不合格・観察・対処」を記録。不合格なら該当タスクのコードを直す指示書を作って委譲
- [ ] **Step 8:** `device-check.md` に仕様 §4（操作表・幕・起動時・リンク）、§7.1（全行）、§7.2 の確認項目をチェックボックスで列挙し、GT8D で確認
- [ ] **Step 9:** `gradle-run.sh test assembleRelease` → BUILD SUCCESSFUL
- [ ] **Step 10:** Commit `feat: セットアップと実機の最初の確認`

---

### Task 21（納車後 2026-11-21 以降）：車での実験（仕様 §10.2 ⑧〜⑪）

**Files:**
- Create: `docs/superpowers/spikes/car-spikes.md`
- Modify（結果次第）：`split/VendorBridge.kt`（車ボタン）、`config/Model.kt`（`BootConfig.waitForCar` の既定）、`edge/*`、`ui/panel/*`、`sets/SetRunner.kt`（解像度変更時の呼び直し）

- [ ] **Step 1:** ⑧ 車ボタン：`com.carsyso.mainsdk.manager.SystemManager.requestSwitchOriginalMpu`（MainAiBox の `MainService` への bind）と、純正ランチャーの車ボタン押下時の logcat で呼び出し方を特定。できたら `VendorBridge.carHomeAvailable=true`・`switchToCarScreen()` を実装し、Dock の車ボタンを表示
- [ ] **Step 2:** ⑨ 車の画面で帯のスワイプ・長押し、2本指スワイプ、幕の見え方を確認。届きにくければ帯幅の既定を上げる
- [ ] **Step 3:** ⑩ 車接続中の `wm size`・`dumpsys display`、E の比率どおりの分割、D のペア配置、**起動 → CarPlay 接続のタイミングと途中の解像度変更で分割が崩れないか**。「車との接続を待ってから開く」の既定値と検知方法（ディスプレイ構成変更／`SmartBoxCarPlayManager`）を決めて実装。崩れるなら解像度変更時に E を呼び直す処理を `SetRunner` に足す
- [ ] **Step 4:** ⑪ CarPlay 接続中の自作ホーム・分割の見え方。Launcher3 が前面に来るなら記録してユーザーに相談
- [ ] **Step 5:** `car-spikes.md` に記録して Commit `spike: 車での実験⑧〜⑪`
