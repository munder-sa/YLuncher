# Task 079: 仕切りのダブルタップの入れ替えを、画面セットに保存する(v1.5 Task 6)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
v1.5(設計書 `docs/superpowers/specs/2026-10-31-v1.5-left-panel-design.md` **§3.3 を必ず読む**、§4.3、計画 Task 6。この指示書が優先)。
- 実機で、システムの仕切りをダブルタップすると左右が入れ替わり、PanelActivity は作り直されず窓だけ動く。Task 075 で、パネルの位置が `AppGraph.panelPlacement: StateFlow<PanelPlacement?>`(Left/Right/Full/null)で共有されている。
- Task 076 で `SetRunner` に `panelLaunched(pkg)`、空の口 `onSetReady(set)`・`onSetEnded(reason)` ができている(呼ばれる場所は 076 の報告どおり。`SetRunner.kt` を読むこと)。
- 保存の考え方は「区間(セッション)の中で、落ち着いた位置とセットのパネルの側を比べて収束させる」。変化を1回ずつ拾う作りにしない。

## 変更・作成するファイル(これで全部)
- create `app/src/main/java/com/gt8d/launcher/sets/PanelSwapSaver.kt`
- `app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt`
- `app/src/main/java/com/gt8d/launcher/AppGraph.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelActivity.kt`(パネルからの起動の成功で `AppGraph.runner.panelLaunched(pkg)` を呼ぶだけ)
- create `app/src/test/java/com/gt8d/launcher/sets/PanelSwapSaverTest.kt`
- `app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.sets.*' --tests 'com.gt8d.launcher.ui.panel.*' --tests 'com.gt8d.launcher.boot.*'`

## 仕様

### A. PanelSwapSaver
```kotlin
class PanelSwapSaver(
    scope: CoroutineScope,
    latestConfig: () -> LauncherConfig?,
    updateConfig: suspend ((LauncherConfig) -> LauncherConfig) -> Unit,
    onSaved: (ScreenSet) -> Unit,
    settleMs: Long = 500,
)
fun begin(set: ScreenSet, partner: String)   // 区間の開始(パネルを含むセットのみ。それ以外は end と同じ)
fun end(reason: String)                      // 区間の終了。待っている判断も取り消す
fun partnerChanged(pkg: String)              // パネルからアプリを替えた
fun placementChanged(p: PanelPlacement?)     // 位置の変化
```
- 区間は `snapshot: ScreenSet`(開いた時点の内容)と `partner: String` を持つ。
- `placementChanged`: 区間が無い・`p` が Full か null → 何もしない(待っている判断は取り消す)。Left/Right → `settleMs` 待って(その間に別の値が来たら待ち直す)、判断する。
- 判断(設計書 §3.3 の 1〜4):
  1. `p` の側 == `snapshot.panelSide` → 何もしない。
  2. `partner != snapshot の appPkgAt(反対側)` → `swapSkipped reason=partner`、何もしない。
  3. `updateConfig { cfg -> ... }` の中で、`cfg.screenSets` から `snapshot.id` のセットを探し、`== snapshot` でなければ(変更・削除)設定を変えずに返し、区間を終える(`swapSkipped reason=edited`)。同じなら、そのセットを `snapshot.swapped()` に置き換えた設定を返す。
  4. 保存できたら `snapshot = swapped`、`DiagLog.d("SetRunner", "swapSaved id=<id> panel=<Left|Right>")`、`onSaved(swapped)`。
- 保存は1つずつ(Mutex)。保存が終わったら、**最新の位置**でもう一度判断する(保存中に位置が戻っていたら、元に戻す保存が続く=最後の位置に収束)。最新の位置は `placementChanged` で最後に受けた値を持っておく。
- 例外は外に出さずログ(`swapSkipped reason=error`)。

### B. SetRunner
1. `SetRunnerDeps` に `updateConfig: suspend ((LauncherConfig) -> LauncherConfig) -> Unit = {}` と `scope: CoroutineScope? = null` を足す(既定値で既存のテストを壊さない)。`scope` が null なら保存しない(テストで渡す)。
2. `SetRunner` が `PanelSwapSaver` を持つ。`onSetReady(set)` → `saver.begin(set, appPkg)`(パネルを含むときのみ)。`onSetEnded(reason)` → `saver.end(reason)`。`panelLaunched(pkg)` が相手を替えたら `saver.partnerChanged(pkg)`。`fun onPanelPlacementChanged(p: PanelPlacement?)` を足し `saver.placementChanged(p)`。`onSaved` で `lastOpenedPair` の左右を入れ替える。
3. `AppGraph`: SetRunner の deps に `updateConfig = { f -> config.update(f) }` と、アプリの寿命の `scope` を渡す。`panelPlacement` を collect して `runner.onPanelPlacementChanged(it)` を呼ぶ(アプリの寿命の scope で1回だけ)。
4. `PanelActivity`: `onLaunchApp` と `onLaunchComponent` で起動が成功し、かつ分割中(`isInMultiWindowMode`)なら `AppGraph.runner.panelLaunched(pkg)`(component はそのパッケージ)。

## テスト(足す。既存は弱めない)
`PanelSwapSaverTest`(`kotlinx.coroutines.test` の `StandardTestDispatcher`/`runTest` で時間を進める。`updateConfig` は設定を持つ偽物で、遅らせられるようにする):
- `swap_settled_savesSwapped`: `begin(ScreenSet("s1", App("yt"), Panel, R6_4), "yt")` → `placementChanged(Left)` → 500ms 後、設定の `s1` が `ScreenSet("s1", Panel, App("yt"), R4_6, null)`、`onSaved` が1回。
- `swap_beforeSettle_ignored`: Left → 300ms 後 Right → 1000ms 進めても保存しない。
- `swap_twice_withSlowSave_convergesToFinal`: 保存を 2000ms 遅らせる。Left(500ms 後に保存開始)→ 保存中に Right → 最後は設定が元の `App("yt") | Panel R6_4`。
- `noSave_outsideSession`(begin 前、end 後)、`noSave_afterEndSafety`(`end("safety")` の後の Left)、`noSave_afterPartnerChanged`(`partnerChanged("maps")` の後の Left)、`noSave_whenSetEdited`(保存の時点の設定で `s1` の内容が違う → 設定は変わらず、その後の位置の変化でも保存しない)、`noSave_whenSetDeleted`、`noSave_fullOrNull`、`save_usesIdAfterReorder`(設定のセットの順番を入れ替えても `s1` が更新される)。
`SetRunnerTest`:
- `session_beginOnReady_endOnHomeNextLinkFailSafety`: 左パネルのセットが ready で begin、`home()`・次のセットの open・`openForLink`・失敗・Safety で end(偽の saver か、ログ/状態で確かめる)。
- `panelLaunched_forwardsPartner`。
- `placement_wiring`: `onPanelPlacementChanged(Left)` が保存につながる(偽の `updateConfig` と `scope`)。

## この Task でしないこと
- リンクの変更(Task 080)、設定画面の変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、**決めたこと**。
