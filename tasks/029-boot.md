# Task 16: 起動時の手順(起動画面・カウントダウン・取り消し・純正ランチャーへの退避)

**最初に `tasks/RULES.md` を読み、従うこと。** 計画 `docs/superpowers/plans/2026-10-28-gt8d-launcher.md` の **Task 16**、仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §4.8・§7.2 に従う。計画の Global Constraints を守る。§4.8 の 6(車との接続を待つ設定)はこの Task ではしない。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

## 作成・変更するファイル(これで全部。許可外の書込みは拒否され、そこで停止する)

新規作成:
- `M/boot/BootSequence.kt`
- `M/ui/home/BootScreen.kt`
- `T/boot/BootSequenceTest.kt`
- `T/ui/home/BootScreenTest.kt`
- `T/ui/home/HomeActivityTest.kt`

変更:
- `M/sets/SetRunner.kt`(`readyCount` を足す・`targetFor` を公開にする。ほかは変えない)
- `T/sets/SetRunnerTest.kt`(テストを1つ足す。既存のテストは変えない)
- `M/AppGraph.kt`、`M/ui/home/HomeActivity.kt`、`M/edge/BootReceiver.kt`

使ってよいコマンド: `/home/munde/tools/gradle-run.sh test` のみ。**Gradle はバックグラウンドに回さず、終わるまで待つ形で実行する**。全テストは 4〜8 分かかる(テストのクラスごとに JVM を起動するため。各クラスの最初のテストの前に 1〜2 分の無出力があるのは正常)。

## 仕様

### BootSequence.kt(パッケージ `com.gt8d.launcher.boot`)
```kotlin
enum class BootPhase { Idle, Countdown, Opening, Done, StayedHome }

class BootSequence(
    private val scheduler: Scheduler,          // com.gt8d.launcher.time.Scheduler
    private val safetyActive: () -> Boolean,
    private val openLast: () -> Unit,
    private val splitAppeared: () -> Boolean,
    private val goHome: () -> Unit,
    private val record: (String) -> Unit,
) {
    companion object {
        const val COUNTDOWN_MS = 3000L
        const val BOOT_LIMIT_MS = 10000L
        const val POLL_MS = 200L
    }
    val phase: StateFlow<BootPhase>            // 初期値 Idle
    val secondsLeft: StateFlow<Int>            // 初期値 3
    fun start()
    fun openNow()
    fun stayHome()
}
```
規則:
- `start()`: `phase` が `Idle` のときだけ動く(2回目以降は何もしない)。`safetyActive()` が true なら `record("boot_skipped_safety")`、`phase = StayedHome` で終わり(何も開かない)。そうでなければ `phase = Countdown`、`secondsLeft = 3`。開始から 1000ms で 2、2000ms で 1、`COUNTDOWN_MS`(3000ms)で「開く」
- 「開く」: `phase = Opening`、`openLast()` を1回呼ぶ。そこから `POLL_MS` ごとに `splitAppeared()` を確かめ、true になったら終わりの処理。「開く」から `BOOT_LIMIT_MS` たっても true にならなければ時間切れの処理
- `openNow()`: `Countdown` のときだけ、カウントダウンを取り消してすぐ「開く」
- `stayHome()`:
  - `Countdown` のとき: タイマーを全部取り消し、`record("boot_stay_home")`、`phase = StayedHome`。`openLast()` は呼ばない
  - `Opening` のとき: 取り消しとして `record("boot_cancel")` を記録し、待ちは続ける(このあと終わりの処理か時間切れの処理で `goHome()` を呼ぶ)
  - それ以外のときは何もしない
- 終わりの処理(分割が出た): 取り消し済みなら `goHome()` を呼んで `phase = StayedHome`。取り消していなければ `phase = Done`
- 時間切れの処理: 取り消し済みなら `goHome()` を呼んで `phase = StayedHome`(`boot_timeout` は記録しない)。取り消していなければ `record("boot_timeout")`、`phase = Done`(ホームのまま止まる)
- 終わったら(`Done`・`StayedHome`)タイマーは全部取り消し、`goHome()` は多くても1回
- **このクラスは `SplitModeSelector` を持たない**(起動時の時間切れ・取り消しは、E の連続失敗に数えない。仕様 §4.8 の 4)

### SetRunner.kt の変更
- `var readyCount: Int = 0` を足す(外からは読むだけ: `private set`)。`waitBoth` で両方揃ったと判定したとき、`waitSingle` で前面化を確認したときに、それぞれ 1 増やす(幕の有無に関係なく)
- `private fun targetFor(index: Int): CurtainTarget` を `fun targetFor(index: Int): CurtainTarget`(公開)に変える。中身は変えない

### BootScreen.kt(パッケージ `com.gt8d.launcher.ui.home`)
```kotlin
@Composable
fun BootScreen(
    target: CurtainTarget,
    secondsLeft: Int,
    opening: Boolean,
    onOpenNow: () -> Unit,
    onStayHome: () -> Unit,
    modifier: Modifier = Modifier,
)
```
- 画面全体を覆わない、中央のカード(幅 640dp まで、背景 `Palette.Panel`、角丸)。ホームのほかの部分は押せるままにする
- 上に案内文: `opening` が false なら「${secondsLeft} 秒後に前回のセットを開きます」、true なら「前回のセットを開いています…」(testTag `boot_message`)
- その下に行き先の配置図: 既存の `com.gt8d.launcher.curtain.CurtainContent` の中の配置図の部品を使ってよい。使えなければ、セット名(`target.setName`)と左右のアプリ名を並べた簡単な図でよい(どちらにしたかを「決めたこと」に書く)
- ボタン2つ(押せる領域 72dp 以上): 「今すぐ開く」(testTag `boot_open_now`。`opening` が true のときは出さない)、「ホームのまま使う」(testTag `boot_stay_home`。常に出す)
- 色・文字の大きさは `ui/theme` の `Palette`・`Dims` を使う

### AppGraph.kt の変更
- `var bootHandled: Boolean = false` を足す(このプロセスで起動の手順を一度判定したら true)
- `val boot: BootSequence by lazy { ... }` を足す:
  - `scheduler = MainThreadScheduler()`
  - `safetyActive = { SafetyGuard.isFallbackActive(app) }`
  - `openLast = { readyAtOpen = runner.readyCount; runner.openForBoot(config.lastSetIndex) }`(`readyAtOpen` は lazy の中のローカル変数)
  - `splitAppeared = { runner.readyCount > readyAtOpen }`
  - `goHome = { runner.openForBoot(SetNavigator.HOME) }`
  - `record = { msg -> DiagLog.d("Boot", msg) }`

### HomeActivity.kt の変更
- 定数 `BOOT_WINDOW_MS = 180_000L`(端末の起動からこの時間内だけ、起動の手順を動かす)
- `onCreate`: **最初に** `SafetyGuard.isFallbackActive(this)` を確かめる。true なら、`AppGraph` に触らず、中央に「安全のため純正ランチャーを使っています」とだけ出す画面を `setContent` して `return`
- `onResume`: 退避中(`SafetyGuard.isFallbackActive(this)`)なら `runCatching { startActivity(stockLauncherIntent()) }` して `return`(`EdgeStripService.ensureRunning` も起動の手順もしない)。そうでなければ今までどおり `EdgeStripService.ensureRunning(this)` のあと、`AppGraph.bootHandled` が false なら true にして、`SystemClock.elapsedRealtime() < BOOT_WINDOW_MS` かつ `AppGraph.config.lastSetIndex != 0` のときだけ `AppGraph.boot.start()`
- 画面: 既存の表示の上に、`AppGraph.boot.phase` が `Countdown` か `Opening` のあいだだけ `BootScreen` を重ねる(`target = AppGraph.runner.targetFor(AppGraph.config.lastSetIndex)`、`secondsLeft` は `AppGraph.boot.secondsLeft`、`opening = phase == Opening`、`onOpenNow = AppGraph.boot::openNow`、`onStayHome = AppGraph.boot::stayHome`)

### BootReceiver.kt の変更
- `ACTION_BOOT_COMPLETED` で、今の `EdgeStripService.ensureRunning(context)` に加えて、`AppGraph.selector.onBoot(AppGraph.bridge.splitAvailable())` を呼ぶ(`runCatching` で囲む)

## 手順(TDD)

1. テストを書く:
   - `T/boot/BootSequenceTest.kt`(純粋な JUnit、既存の `com.gt8d.launcher.time.FakeScheduler` を使う。`opened`・`homed` は呼ばれた回数、`records` は記録の一覧、`appeared` は `splitAppeared` が返す値)
     - `countdown_opensAfter3000`: start → `secondsLeft` 3 → advanceBy(1000) で 2 → advanceBy(1000) で 1 → advanceBy(999) で opened 0 → advanceBy(1) で opened 1、phase `Opening`
     - `openNow_opensImmediately`: start → advanceBy(500) → openNow → opened 1 → advanceBy(5000) で opened 1 のまま
     - `stayHomeDuringCountdown_neverOpens`: start → advanceBy(1000) → stayHome → advanceBy(20000) → opened 0、homed 0、phase `StayedHome`、records `["boot_stay_home"]`
     - `stayHomeAfterOpen_goesHomeWhenSplitAppears`: start → advanceBy(3000) → stayHome → advanceBy(1000) で homed 0 → appeared = true → advanceBy(200) で homed 1、phase `StayedHome`、records `["boot_cancel"]` → advanceBy(20000) で homed 1 のまま
     - `stayHomeAfterOpen_goesHomeAt10000`: start → advanceBy(3000) → stayHome → advanceBy(9999) で homed 0 → advanceBy(1) で homed 1、phase `StayedHome`、records `["boot_cancel"]`
     - `bootTimeout_recordsAndStops_withoutFailureCount`: start → advanceBy(3000) → advanceBy(9999) で records 空・phase `Opening` → advanceBy(1) で records `["boot_timeout"]`、phase `Done`、homed 0
     - `splitAppears_done`: start → advanceBy(3000) → appeared = true → advanceBy(200) で phase `Done`、records 空、homed 0
     - `safetyActive_doesNothing`: safetyActive が true → start → advanceBy(20000) → opened 0、phase `StayedHome`、records `["boot_skipped_safety"]`
     - `start_twice_opensOnce`: start → start → advanceBy(3000) → opened 1
   - `T/sets/SetRunnerTest.kt` に1つ足す: `readyCount_incrementsWhenSplitReady`(既存の、分割が揃って成功するテストと同じ準備で、開く前は 0、揃ったあとは 1)
   - `T/ui/home/BootScreenTest.kt`(Robolectric の Compose テスト。既存の `T/ui/panel/DockTest.kt` の準備の書き方に合わせる)
     - `bootScreen_countdown_showsButtons`: `opening = false`、`secondsLeft = 3` → `boot_message` に「3 秒後に前回のセットを開きます」、`boot_open_now`・`boot_stay_home` が表示され、どちらも幅・高さ 72dp 以上。押すとそれぞれのコールバックが1回呼ばれる
     - `bootScreen_opening_hidesOpenNow`: `opening = true` → `boot_open_now` が無い、`boot_stay_home` は表示、`boot_message` に「前回のセットを開いています…」
   - `T/ui/home/HomeActivityTest.kt`(Robolectric、`@Config(sdk = [35])`)
     - `homeActivity_safetyActive_startsStockLauncher`: アプリの Context で `SafetyGuard.recordStart` のあと `SafetyGuard.recordCrash` を3回呼んで退避中にする(`isFallbackActive` が true になることを先に assert)→ `Robolectric.buildActivity(HomeActivity::class.java).setup()` → `shadowOf(activity).nextStartedActivity.component` が `ComponentName("com.android.launcher3", "com.android.launcher3.uioverrides.QuickstepLauncher")`
2. `/home/munde/tools/gradle-run.sh test` を実行し、新しいテストが失敗する(またはコンパイルできない)ことを確認する
3. 実装する
4. `/home/munde/tools/gradle-run.sh test` で全部通るまで直す(最大3回。通らなければ失敗したテスト名とエラーの先頭30行を報告して止まる)

## 報告

`test` の結果(実行件数と失敗件数)、作成・変更したファイル名、BootScreen の配置図をどう作ったか、**決めたこと**。
