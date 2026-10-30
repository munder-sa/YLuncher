# Task 046: E の呼び直しは「E が何も動かした形跡が無い」ときだけにする(冷えた起動でパネルが分割から外れる問題)

最初に `tasks/RULES.md` を読み、従うこと。仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` §4.3「呼び直し」・§7.1(2026-10-30 に書き換え済み)に従う。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

## 背景(司令塔が 2026-10-30 に GT8D で確かめた事実)
冷えた状態(パネルのタスクが無い)で「アプリ | パネル」を E で開くと、E はパネルを全画面で起動し、既定のホーム(純正ランチャー)を経て分割を組む。
これが 3 秒を少し超える(実測 3.3〜3.6 秒)。`SetRunner.waitBoth` は 3 秒(`RETRY_AT_MS`)で両側がそろっていないと E を呼び直すため、
組み立ての途中で2回目の `SplitActivity` が開き、システム(WM Shell)が「分割の片側が空」と判断して分割を解く予約をし、パネルが分割から外れる
(ログ: `splitTransition deduced Dismiss due to APP_FINISHED` → `onTaskVanished: task=<パネル>`)。
E を1回だけ呼んだ場合は、分割が自力で成立し保たれることを確かめた。

## 作成・変更するファイル(これで全部)
- `M/sets/SetRunner.kt`
- `M/ui/panel/PanelActivity.kt`(`onResume` に1行足すだけ)
- `M/ui/home/HomeActivity.kt`(`onResume` に1行足すだけ)
- `T/sets/SetRunnerTest.kt`(テストを足す。既存のテストは変えない)

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.sets.SetRunnerTest --tests com.gt8d.launcher.ui.panel.PanelActivityTest --tests com.gt8d.launcher.ui.home.HomeActivityTest`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様

### SetRunner.kt
1. 呼び出しの「形跡」を記録する:
   - `private var ownScreenSeen: Boolean`(`openInternal` で開き始めるとき、`panelResumed = false` と同じ所で false に戻す)。
   - 公開の関数 `fun ownScreenResumed()` を足す: 開いている最中(`_opening.value != null`)なら `ownScreenSeen = true`。DiagLog は不要。
2. `waitBoth` の呼び直しのタイマー(`RETRY_AT_MS`)の中で、`!ready` のとき、呼び直す前に形跡を見る:
   ```kotlin
   val seen = d.watcher.foregroundPkgsSince(startMs)
   val progressed = ownScreenSeen || panelResumed ||
       left in seen || (!isPanelRight && right in seen) || STOCK_LAUNCHER_PKG in seen
   ```
   - `progressed` が true なら呼び直さず、`DiagLog.d("SetRunner", "retry skipped: progress left=$left right=$right")` だけ記録する(幕・安全タイマー・失敗の数え方は今のまま)。
   - false なら今のまま呼び直す。
   - `STOCK_LAUNCHER_PKG = "com.android.launcher3"` を companion object に置く(`apps/StockLauncher.kt` と同じ値。そちらは変えない)。
   - `foregroundPkgsSince` の呼び出しで例外・取得失敗が起きる場合の扱いは、今の `stopMonitoringIfUnavailable` の流れを崩さないこと(呼ぶ順番を見て、今の `isBothReady()` の後に置く)。
3. ほかの部分(幕を外す合図、安全タイマー、起動時の扱い、リンクの扱い)は変えない。

### PanelActivity.kt / HomeActivity.kt
- `onResume` の先頭近く(安全モードの判定より前でよい)で `AppGraph.runner.ownScreenResumed()` を呼ぶ。分割の内外を問わない。
  (HomeActivity の `recreate()` で早く戻る分岐がある場合は、その後でよい。どちらでもよいが、報告の「決めたこと」に書く)

## テスト(先に書き、修正前に失敗することを確かめる)
`T/sets/SetRunnerTest.kt` に足す(既存の `TestEnv`・`FakeForegroundEvents` の書き方に合わせる):
- `retrySkipped_whenOwnScreenResumedDuringOpening`: セット1(右がパネル)を開き、1000ms で `ownScreenResumed()` → 3000ms 経っても E の呼び出しは1回。4000ms で幕が外れる(安全タイマー。今の仕様のまま)。
- `retrySkipped_whenStockLauncherResumed`: セット2(アプリ | アプリ)を開き、1500ms で `com.android.launcher3` の前面化イベント → 3000ms で E は1回のまま。
- `retrySkipped_whenLeftResumed`: セット2で、500ms で左(`youtube`)の前面化イベントだけ → 3000ms で E は1回のまま。
- `retryHappens_whenNothingSeen`: セット2で何のイベントも無い → 3000ms で E が2回目に呼ばれる(既存の再試行テストと同じ前提の確認)。
- `ownScreenResumed_outsideOpening_isIgnored`: 開いていないときに `ownScreenResumed()` → 次にセットを開いたとき、何も無ければ 3000ms で呼び直す。
- `ownScreenSeen_resetsOnNextOpen`: 1回目の開く操作で `ownScreenResumed()`、完了(または安全タイマー)の後、2回目を開いて何も無ければ 3000ms で呼び直す。
- 既存の再試行のテスト(`retryEntryMissing_…`、`retryException_…`、`bootRetryException_…`、`linkRetryFailure_…`)は変えずに通ること。

## 手順(TDD)
1. 上のテストを書く。2. 対象テストを実行し、修正前に失敗することを確かめる(どのテストがどう失敗したかを報告に書く)。
3. 実装する。4. 対象テストが全部通るまで直す(最大3回)。通ったら `assembleDebug`。

## この Task でしないこと
- `RETRY_AT_MS`・安全タイマー・その他の時間の値の変更。ForegroundWatcher・PanelForwarder・LinkEntryActivity の変更。
- 全テストの実行、実機の操作、git のコミット等。

## 報告
実行したコマンドと結果(件数・失敗件数、修正前の失敗の内容、assembleDebug)、作成・変更したファイル、**決めたこと**。
