最初に tasks/RULES.md を読み、従う。

# Task 118: 取り消しの見張りで、パネルが分割に入った知らせでもホームへ戻す

## 背景(実機のログで確認済み)
- セットを開く途中で取り消す(`cancelOpening`)と、`SetRunner` は `d.startHome()` のあと `startCancelGuard` で 5 秒(`CANCEL_GUARD_MS`)見張る。見張りは UsageStats の前面のアプリ(`d.watcher.lastForegroundSince(since)`)だけを見て、開いていたセットの左右・Vendor・純正ランチャーが前面に出たらホームへ戻す(最大 2 回)。
- 場面テスト `set_cancel_cold` がときどき落ちる。落ちた回のログ(build/device-scenarios/20261101-195813-1695912/set_cancel_cold/logcat.txt):
  - 40.588 openSplit started → 42.676 opening cancelled → 43.808 `[SetRunner] panelResumedInSplit` → 44.411 `[Panel] placement=Right`。以後、分割(左 YouTube | 右 パネル)が残った。
  - YouTube は取り消しより前に前面に出ており、取り消しの後には前面の記録が出なかったため、見張りは一度もホームへ戻さなかった(`cancel guard home` が 0 回)。
  - パネル(PanelActivity は本体 com.gt8d.launcher の画面で、UsageStats の前面には表れない)は、取り消しの後に分割に入ったことを `panelResumedInSplit` と placement=Right で知らせている。
- 合格した回は、YouTube が取り消しの後に前面の記録を出したか、分割が自然に解けていた。

## 変更
### app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt
1. 見張りの状態(期限・戻した回数・`since`)を、`startCancelGuard` の局所変数から、クラスのフィールドに移す(例: `guardDeadlineMs: Long?`、`guardReturns`、`guardSince`、`guardWatchesPanel: Boolean`、`guardLastPanelHomeMs: Long?`)。`stopCancelGuard()` はこれらも消す(期限を null に)。前面のアプリの見張り(poll)の動きは今と同じにする(期限、2 回まで、`since` の更新、watcher が使えないときに止まる)。
2. `guardWatchesPanel` は、取り消したセットの左か右が `d.panelHostPkg` のときだけ true。
3. 見張りの最中(期限が null でない、`d.clock.nowMs() < 期限`、戻した回数 < 2、`guardWatchesPanel`)に、次のどちらかが来たら、ログ `cancel guard home panel` を出して `d.startHome()` を呼び、戻した回数を 1 増やし、`guardSince = now + 1`、`guardLastPanelHomeMs = now` にする。
   - `panelResumedInSplit()` が呼ばれた(今の `panelResumed` の処理はそのまま残す)。
   - 新しい公開関数 `panelPlacementChanged(p: PanelPlacement?)` に `Left` か `Right` が渡された(`Full`・null は何もしない)。
   - ただし `guardLastPanelHomeMs` から `CANCEL_GUARD_PANEL_DEBOUNCE_MS = 500L` 未満のときは何もしない(1 回の分割で両方の知らせが続けて来るため)。
4. 戻した回数は前面の見張りと共有する(合わせて 2 回まで)。2 回に達したら poll も止まる(今と同じ)。見張りでないとき(取り消しの後でない、期限切れ、ほかの操作で止まった)は、パネルの知らせでホームへ戻さない。
5. 期限切れの判定は、パネルの知らせの時点の `d.clock.nowMs()` で行う(poll が止まっていても、期限内なら効く)。ただし watcher が使えずに見張りが始まらなかったとき(`startCancelGuard` が早く return したとき)は、期限を設定しない(=パネルの知らせも効かない。今の「監視できないときは見張らない」に合わせる)。

### app/src/main/java/com/gt8d/launcher/AppGraph.kt
- `reportPanelPlacement` で値が変わったとき、ログの後に `runner.panelPlacementChanged(p)` を呼ぶ。`runner` は `by lazy` なので、呼ぶことで runner が作られてしまう。作られていないときに作ってよいか判断がつかなければ、そこで止まって 3 点セットで報告する(推測で進めない)。

### app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt(テストを足す。既存のテストは変えない)
- `cancelGuard_panelResumedInSplit_goesHome`: パネルを含むセットを開いて取り消し、1000ms 後に `panelResumedInSplit()` → `startHomeCount` が 1 増える。
- `cancelGuard_panelPlacementLeftOrRight_goesHome_fullOrNullDoesNot`: Left・Right はそれぞれ 1 増える。Full・null は増えない。
- `cancelGuard_panelSignals_debouncedAndShareTwoReturnLimit`: 500ms 未満の 2 つ目の知らせは無視、600ms 後の知らせは効く、3 回目は(前面の見張りとあわせて 2 回に達しているので)効かない。
- `cancelGuard_panelSignal_ignoredAfterDeadlineOrWithoutCancel_orWhenSetHasNoPanel`: 期限(5000ms)の後、取り消していないとき、パネルを含まないセットを取り消したとき、ほかの操作(open など)で見張りが止まった後は、増えない。
- 時間の進め方・セットの作り方は既存のテスト(`cancelGuard_*`、`leftPanelEnv`)にならう。

## 作るファイル・変えるファイル(これで全部)
- write app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt
- write app/src/main/java/com/gt8d/launcher/AppGraph.kt
- write app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt

## 使ってよいコマンド
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.sets.*'`

## 報告
- 変えた箇所、決めたこと、実行したコマンドと結果(件数・失敗数)。
