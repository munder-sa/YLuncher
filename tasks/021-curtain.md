# Task 12: 切り替えの幕(時間制御と表示)

**最初に `tasks/RULES.md` を読み、従うこと。**

計画 `docs/superpowers/plans/2026-10-28-gt8d-launcher.md` の **Task 12**、仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §4.3 に従う。計画の Global Constraints を守る。

## 作成するファイル(これで全部。許可外の書込みは拒否され、そこで停止する)

- `app/src/main/java/com/gt8d/launcher/time/Time.kt`
- `app/src/main/java/com/gt8d/launcher/curtain/CurtainController.kt`
- `app/src/main/java/com/gt8d/launcher/curtain/CurtainContent.kt`
- `app/src/test/java/com/gt8d/launcher/time/FakeScheduler.kt`
- `app/src/test/java/com/gt8d/launcher/curtain/CurtainControllerTest.kt`
- `app/src/test/java/com/gt8d/launcher/curtain/CurtainContentTest.kt`

既存のファイルは変更しない。

## 仕様

`Time.kt`(パッケージ `com.gt8d.launcher.time`):
```kotlin
fun interface Clock { fun nowMs(): Long }
fun interface Cancellable { fun cancel() }
interface Scheduler { fun schedule(delayMs: Long, action: () -> Unit): Cancellable }
class MainThreadScheduler : Scheduler      // Handler(Looper.getMainLooper()).postDelayed。cancel で removeCallbacks
object ElapsedClock : Clock                // SystemClock.elapsedRealtime()
```

`FakeScheduler`(テスト用、パッケージ `com.gt8d.launcher.time`): `Scheduler` と `Clock` を実装。`fun advanceBy(ms: Long)` で時刻を進め、期限が来た予定を時刻順に実行(実行中に追加された予定も、期限内なら同じ `advanceBy` の中で実行)。`cancel` された予定は実行しない。

`CurtainController.kt`(パッケージ `com.gt8d.launcher.curtain`、純粋な Kotlin):
```kotlin
sealed interface SlotPreview {
    data class App(val pkg: String, val label: String) : SlotPreview
    data object Panel : SlotPreview
}
data class CurtainTarget(val setName: String, val left: SlotPreview, val right: SlotPreview, val ratio: SplitRatio, val position: Int, val count: Int)
enum class HideReason { Ready, Timed, Safety, Failed }
interface CurtainView {
    fun show(target: CurtainTarget)
    fun update(target: CurtainTarget)
    fun showProgress()
    fun hide(reason: HideReason)
}
class CurtainController(private val scheduler: Scheduler, private val view: CurtainView) {
    companion object { const val SAFETY_MS = 4000L; const val PROGRESS_AFTER_MS = 800L }
    val isShowing: Boolean
    var onHidden: ((HideReason) -> Unit)? = null
    fun show(target: CurtainTarget, expectedMs: Long, watcherAvailable: Boolean)
    fun update(target: CurtainTarget)
    fun ready()
    fun fail()
}
```
規則:
- `show`: `view.show(target)`。安全タイマー(`SAFETY_MS`)と進行線タイマー(`PROGRESS_AFTER_MS` で `view.showProgress()`)を予約。`watcherAvailable == false` のときだけ、`expectedMs` で `hide(Timed)` を予約。表示中に再度 `show` が呼ばれたら、`update` と同じ扱い(タイマーは延長しない)
- `update`: 表示中なら `view.update(target)` だけ。**タイマーを延長・再予約しない**。非表示なら何もしない
- `ready()` → `hide(Ready)`、`fail()` → `hide(Failed)`、安全タイマー → `hide(Safety)`、時間 → `hide(Timed)`
- hide 共通: 表示中のときだけ、全タイマーを取り消し、`view.hide(reason)`、そのあと `onHidden?.invoke(reason)` を**1回だけ**呼ぶ。非表示のときの hide は何もしない

`CurtainContent.kt`:
```kotlin
@Composable fun CurtainContent(target: CurtainTarget, showProgress: Boolean, iconFor: (String) -> ImageBitmap? = { null })
```
- 全面を覆う。背景は `R.drawable.wallpaper`(`ContentScale.Crop`)の上に `Palette.Background0` の 70% 不透明の層を重ねる(ぼかし禁止)
- 上部中央にセット名(32sp 以上、白)。testTag `curtain_set_name`
- 中央に**配置図**: 横幅 640dp・高さ 300dp 程度の枠の中に、左右2ブロックを `ratio` の比率(R6_4 なら 6:4。`label` の数字から計算)で並べる。間隔 8dp。ブロックは `Palette.Panel` の角丸。App はアイコン(`iconFor(pkg)`、null なら先頭1文字の円)とアプリ名(20sp 以上)、Panel は「パネル」の文字と簡単な図(縦3本の帯など)。testTag `curtain_left`、`curtain_right`
- 下部に巡回の位置の点(`count` 個、`position` 番目だけ `Palette.Accent`)。testTag `curtain_dots`
- `showProgress` のときだけ、配置図の下に細い(4dp)`Palette.Accent` の不確定の進行線。testTag `curtain_progress`
- 文字列は日本語

## 手順(TDD)

1. テストを書く:
   - `CurtainControllerTest`(純粋な JUnit。`FakeScheduler` と、呼び出しを記録する `FakeCurtainView`):
     - `safety_hidesAt4000_evenWithoutReady`: watcherAvailable=true で show → advanceBy(3999) で `isShowing==true` → advanceBy(1) で `view.hide(Safety)`、`onHidden(Safety)` が1回
     - `update_doesNotExtendSafety`: show → advanceBy(3000) → update → advanceBy(1000) で Safety
     - `timedHide_whenWatcherUnavailable_atExpected`: watcherAvailable=false、expected=800 → advanceBy(800) で `Timed`
     - `noTimedHide_whenWatcherAvailable`: watcherAvailable=true、expected=800 → advanceBy(800) で表示中のまま
     - `ready_hidesImmediately_andCancelsTimers`: ready → `Ready`、その後 advanceBy(5000) しても hide も onHidden も増えない
     - `progress_shownAfter800_only`: advanceBy(799) で showProgress 0 回、advanceBy(1) で 1 回。ready 後は呼ばれない
     - `onHidden_calledOnceForEveryReason`: Ready・Timed・Safety・Failed それぞれで、onHidden がちょうど1回
   - `CurtainContentTest`(Robolectric、`@Config(sdk = [35])`、`createComposeRule()`、`LauncherTheme` で包む):
     - `curtainContent_showsSetNameAndRatioBlocks`: R6_4 で `curtain_set_name` にセット名、`curtain_left` と `curtain_right` の幅の比 `left/(left+right)` が 0.6±0.02
     - `curtainContent_progressOnlyWhenRequested`: showProgress=false で `curtain_progress` が存在しない、true で存在する
     - `curtainContent_panelSlotShowsPanelLabel`: right=Panel で「パネル」の文字がある
2. `/home/munde/tools/gradle-run.sh test` を実行し、新しいテストが失敗することを確認する
3. 実装する
4. `/home/munde/tools/gradle-run.sh test assembleDebug` で全部通るまで直す(最大3回。通らなければ失敗したテスト名とエラーの先頭30行を報告して止まる)

## 報告

`test assembleDebug` の結果(実行件数と失敗件数)、作成したファイル名、**決めたこと**(RULES.md の 1)。
