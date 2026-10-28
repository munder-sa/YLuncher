# Task 14: セットを開く手順(SetRunner)

**最初に `tasks/RULES.md` を読み、従うこと。**

計画 `docs/superpowers/plans/2026-10-28-gt8d-launcher.md` の **Task 14**、仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §4.1・§4.3・§5.3・§7.1 に従う。計画の Global Constraints を守る。

## 作成・変更するファイル(これで全部。許可外の書込みは拒否され、そこで停止する)

- 新規: `app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt`
- 新規: `app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt`
- 変更: `app/src/main/java/com/gt8d/launcher/config/ConfigRepository.kt`(`LastIndexStore` を実装するだけ。`class ConfigRepository(...) : LastIndexStore` とし、既存の `var lastSetIndex` に `override` を付ける。他は変えない)

## 使う既存の部品(読んで使う。変更しない)

- `sets/SetNavigator.kt`: `SetNavigator.HOME`(=0)、`next(current, setCount)`、`prev(current, setCount)`
- `config/Model.kt`: `LauncherConfig`、`ScreenSet(id, left: Slot.App, right: Slot, ratio, appPairLabel)`、`Slot.App(pkg)`、`Slot.Panel`、`SplitRatio`
- `split/SplitLauncher.kt`: `SplitLauncher.open(left, right, ratio): LaunchResult`、`LaunchResult.Started`、`LaunchResult.Failed(reason)`、`FailReason`
- `split/ActivityLauncher.kt`: `ActivityLauncher.startApp(pkg, adjacent): Boolean`、`isResizeable(pkg): Boolean`
- `split/SplitModeSelector.kt`: `current(): SplitMode`(`Vendor`/`AppPair`/`LeftOnly`)、`onSuccess()`、`onFailure(reason, countable)`
- `foreground/ForegroundWatcher.kt`: `ForegroundWatcher.available()`、`bothResumed(left, right, sinceMs)`、`lastForegroundSince(sinceMs)`、`WarmTracker.expectedDelayMs(left, right)`、`markForeground(pkg)`
- `curtain/CurtainController.kt`: `show(target, expectedMs, watcherAvailable)`、`update(target)`、`ready()`、`fail()`、`isShowing`、`onHidden`、`CurtainTarget`、`SlotPreview`、`HideReason`
- `time/Time.kt`: `Scheduler`、`Clock`。テストでは `app/src/test/.../time/FakeScheduler.kt`(`Scheduler` と `Clock` を兼ね、`advanceBy(ms)` で進む)

## 仕様(パッケージ `com.gt8d.launcher.sets`)

```kotlin
data class Opening(val left: String, val right: String, val startedAtMs: Long)
interface LastIndexStore { var lastSetIndex: Int }
interface Messenger { fun showSplitUnavailable(); fun showSwitchFailed() }

data class SetRunnerDeps(
    val config: () -> LauncherConfig,
    val installed: () -> Set<String>,
    val lastIndex: LastIndexStore,
    val selector: SplitModeSelector,
    val vendor: SplitLauncher,
    val appPair: SplitLauncher?,
    val apps: ActivityLauncher,
    val watcher: ForegroundWatcher,
    val warm: WarmTracker,
    val curtain: CurtainController,
    val scheduler: Scheduler,
    val clock: Clock,
    val ownPkg: String,
    val labelFor: (String) -> String,
    val startHome: () -> Unit,
    val messenger: Messenger,
)

class SetRunner(private val d: SetRunnerDeps) {
    val opening: StateFlow<Opening?>
    val currentIndex: Int                       // 最後に開いた(開こうとした)インデックス。0=ホーム。初期値は SetNavigator.clamp(lastIndex.lastSetIndex, setCount)
    val lastOpenedPair: Pair<String, String>?   // 最後に開いたセットの (左, 右) パッケージ。ホームなら null
    fun next(); fun prev(); fun open(index: Int); fun home()
    fun openForBoot(index: Int)
    fun panelResumedInSplit()
    companion object { const val POLL_MS = 200L; const val RETRY_AT_MS = 3000L; const val BOOT_CLEAR_MS = 10000L }
}
```

インデックス: 0 = ホーム、1..N = `config().screenSets[index-1]`。`setCount = screenSets.size`。

### open(index) の手順(この順で。すべてメインスレッドで呼ばれる前提。ロックは不要)

1. **実行中なら保留**: `opening.value != null` のときは、`pending = index` とし、`curtain.update(targetFor(index))` を呼んで**終わる**(E を呼ばない)
2. `index == 0` なら `home()` と同じ処理をして終わる
3. `currentIndex = index`、`lastIndex.lastSetIndex = index`
4. 左右のパッケージを決める: 左 = `set.left.pkg`、右 = `Slot.App` ならその pkg、`Slot.Panel` なら `ownPkg`
5. `opening = Opening(left, right, clock.nowMs())`、`lastOpenedPair = left to right`
6. `curtain.show(targetFor(index), warm.expectedDelayMs(left, right), watcher.available())`
7. **片側が使えないとき**(ここでは E を呼ばない):
   - 左が `installed()` に無い: 右が App で installed なら `apps.startApp(右, false)`、右が Panel なら `startHome()`。`waitSingle(...)` へ
   - 右が App で installed に無い: `apps.startApp(左, false)`、`waitSingle(左)` へ
   - 左・右(App のとき)のどちらかが `apps.isResizeable` が false: `apps.startApp(左, false)`、`messenger.showSplitUnavailable()`、`waitSingle(左)` へ
8. `when (selector.current())`:
   - `Vendor` → `result = vendor.open(left, right, set.ratio)`
   - `AppPair` → `appPair` が null なら `LeftOnly` と同じ。あれば `result = appPair.open(left, right, set.ratio)`
   - `LeftOnly` → `apps.startApp(left, false)`、`messenger.showSplitUnavailable()`、`waitSingle(left)` へ
9. `result` が `Failed(reason)`: `selector.onFailure(reason, countable = true)`、`apps.startApp(left, false)`、`messenger.showSplitUnavailable()`、`curtain.fail()`(→ 下の「幕が外れたとき」で opening が消える)
10. `result` が `Started`: `waitBoth(left, right, mode)` へ

### 待ち(`scheduler` で `POLL_MS` ごと)

- `waitBoth`: 毎回 `isBothReady()` を確認。`isBothReady` = 右が Panel なら「`watcher.lastForegroundSince(start)` 等で左の前面化を確認できる(`watcher.bothResumed(left, left, start)` で可)、かつ `panelResumedInSplit()` が呼ばれている」、右が App なら `watcher.bothResumed(left, right, start)`。真になったら: `curtain.ready()`、`selector.onSuccess()`、`warm.markForeground(left)`・`warm.markForeground(right)`、ポーリングを止める
- `RETRY_AT_MS`(開始から 3000ms)で、まだ揃っておらず `watcher.available()` なら、**1回だけ**同じ方式で同じ引数で呼び直す(`vendor.open` か `appPair.open`)。幕は延長しない(`curtain` は呼ばない)
- `waitSingle(pkg)`: `watcher.lastForegroundSince(start) == pkg` になったら `curtain.ready()`、`warm.markForeground(pkg)`。失敗回数には関わらない
- ポーリングは、幕が外れたら(下)止める

### 幕が外れたとき(`curtain.onHidden` を `SetRunner` の初期化で設定)

- **理由に関係なく**: ポーリングと呼び直しの予定を止め、`opening = null`
- 理由が `Safety` のとき: 実行中の操作が分割(`waitBoth`)なら `selector.onFailure(FailReason.Timeout, countable = watcher.available())`、`messenger.showSwitchFailed()`。`waitSingle` なら何も数えない
- 理由が `Timed`(使用状況が使えず時間で外れた)は、成功にも失敗にも数えない
- そのあと、`pending` があり、`pending != currentIndex` なら `val p = pending; pending = null; open(p)`。そうでなければ `pending = null`

### next / prev / home / openForBoot / panelResumedInSplit

- `next()`: 基準 = `pending ?: currentIndex`。`open(SetNavigator.next(基準, setCount))`。`prev()` も同様
- `home()`: 実行中なら `pending = 0` と `curtain.update(targetFor(0))` で終わる。そうでなければ `currentIndex = 0`、`lastIndex.lastSetIndex = 0`、`lastOpenedPair = null`、`curtain.show(targetFor(0), 800, watcher.available())`、`startHome()`、`curtain.ready()`
- `openForBoot(index)`: 幕を使わない版。`curtain` を一切呼ばずに 3〜10 の手順を行う(待ちが揃ったら `selector.onSuccess()` 等。`waitBoth` の成功時の処理は同じ)。`BOOT_CLEAR_MS` で、まだ揃っていなければ `opening = null` にしてポーリングを止める(**失敗には数えない**)。`Failed(reason)` は通常どおり `onFailure(reason, true)` と左だけ全画面
- `panelResumedInSplit()`: 実行中の操作の「パネルが分割の中で表示された」印を立てる

### targetFor(index)

- 0: `CurtainTarget("ホーム", SlotPreview.App(…"ホーム"), SlotPreview.Panel, SplitRatio.R6_4, position = 0, count = setCount + 1)`(左は `SlotPreview.App("", "ホーム")`)
- 1..N: `CurtainTarget("セット$index", 左 = SlotPreview.App(left, labelFor(left)), 右 = Panel なら SlotPreview.Panel、App なら SlotPreview.App(pkg, labelFor(pkg)), set.ratio, position = index, count = setCount + 1)`

すべての呼び出しと結果を `DiagLog.d("SetRunner", ...)` に記録する。

## 手順(TDD)

1. テストを書く(純粋な JUnit。すべて Fake。`FakeScheduler` を Scheduler と Clock の両方に使う。`CurtainController` は本物を `FakeCurtainView` と組み合わせて使う。`SplitModeSelector` は本物をメモリの store で使う。`ForegroundWatcher` は本物を `FakeForegroundEvents`(available と イベントを後から足せる)で使う。`WarmTracker` は本物)。既定の config は3セット:(set1 = YouTube｜Panel、R6_4)、(set2 = YouTube｜Apple Music、R6_4)、(set3 = YouTube｜Maps、R5_5)、installed は全部、`ownPkg = "com.gt8d.launcher"`:
   - `next_showsCurtainAndCallsVendor_withVendorRatio`: currentIndex=1 から `next()` → vendor が `("youtube","applemusic", R6_4)` で1回、`lastSetIndex == 2`、幕が表示中
   - `panelSet_passesOwnPkgAsRight`: `open(1)` → vendor の右が `"com.gt8d.launcher"`
   - `rapidSwipes_onlyLastTargetLaunched_noOverlap`: `open(1)` の実行中に `next()`、`next()`、`prev()`(行き先は 2→3→2)→ vendor はまだ1回、`FakeCurtainView.update` が3回。その後、左右の前面化イベントとパネルの印で完了させる → 保留中の行き先(セット2)で vendor が2回目に `("youtube","applemusic", R6_4)` で呼ばれる。合計2回。セット3では呼ばれない
   - `entryMissing_opensLeftOnlyAndShowsMessage`: vendor が `Failed(EntryMissing)` → `apps.startApp(left,false)`、`showSplitUnavailable` 1回、`selector.current() == LeftOnly`、`opening == null`
   - `leftUninstalled_opensRightAlone`: set2 で YouTube が未インストール → vendor 0回、`startApp("applemusic", false)`
   - `rightUninstalled_opensLeftAlone`
   - `unresizeableApp_opensLeftOnly`: `isResizeable("applemusic")==false` → vendor 0回、`startApp("youtube", false)`、`showSplitUnavailable`
   - `bothResumed_hidesCurtain_andRecordsSuccess`: `open(2)` → 300ms 後に両方の ResumeEvent を足し `advanceBy(200)` → 幕が `Ready` で外れる、`opening == null`、`selector.state.consecutiveFailures == 0`
   - `notReady_retriesOnceAt3000_noCurtainExtension`: 前面化イベントを足さない → 3000ms で vendor 2回目、4000ms で幕が `Safety` で外れる(延長されない)、それ以降 vendor は増えない
   - `safety_recordsCountableFailureOnlyWhenWatcherAvailable`: available=true で Safety → `consecutiveFailures == 1`。available=false の別インスタンスでは、時間(3500ms、冷えた起動)で `Timed` になり、失敗回数 0
   - `watcherUnavailable_timedHide_clearsOpening_andRunsPendingTarget`: available=false、WarmTracker で両方暖かい(expected 800)。`open(2)` の実行中に `next()` → 800ms の `Timed` で `opening == null` になり、保留中の行き先で vendor が2回目に呼ばれる。失敗回数は 0
   - `openingFlag_setDuringOpen_clearedAfter`
   - `home_startsHome_andHidesCurtain`: `home()` → `startHome` 1回、幕は `Ready` で外れる、`lastSetIndex == 0`
   - `openForBoot_doesNotShowCurtain`: `openForBoot(2)` → `FakeCurtainView.show` 0回、vendor 1回。10000ms で揃わなければ `opening == null`、失敗回数 0
2. `/home/munde/tools/gradle-run.sh test` を実行し、新しいテストが失敗することを確認する
3. 実装する
4. `/home/munde/tools/gradle-run.sh test assembleDebug` で全部通るまで直す(最大3回。通らなければ失敗したテスト名とエラーの先頭30行を報告して止まる)

## 報告

`test assembleDebug` の結果(実行件数と失敗件数)、作成・変更したファイル名、**決めたこと**。
