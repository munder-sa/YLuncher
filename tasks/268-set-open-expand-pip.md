最初に tasks/RULES.md を読み、従う。

# Task 268: セットを開く前に、セットのアプリが PiP なら PiP を解く

## 背景(実機で確認済み、2026-11-18、2.3.9-beta1)
- Task 264 でマップをフリードライブ(free=1)で開くようにした。その結果、マップは `isAutoPipEnabled=true` になり、HOME や別のセットへ移ると自動で PiP(`mode=pinned`)に入る。
- PiP のマップを含むセット(マップ+パネル、Apple Music+マップ)を開くと、マップは PiP のまま `ACTIVITY_RESUMED` にならない。そのため、毎回 `onCurtainHidden reason=Safety` で幕が閉じ、分割が整わない。
  - usagestats では、マップは 17:31:04 の PAUSED のあと、17:53 まで RESUMED が無い。
  - SystemUI のログには `exitPip` と `Pinned task is removed` が出る。
- アプリからの startActivity では PiP は解けない(Task 121 で実機確認済み)。パネルでは `PipDetector`(ユーザー補助の `PipWatcherService`)の `isInPip` / `expand`(ACTION_EXPAND)で解いている(`PanelActivity.relaunchPip`)。
- ユーザーの決定: セットを開く前に PiP を解く。ユーザー補助がオフのときは、今までどおり解かずに開く。

## 作る/変えるファイル(これ以外は触らない)
- write: `app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt`
- write: `app/src/main/java/com/gt8d/launcher/AppGraph.kt`
- write: `app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt`

## 仕様
- `SetRunnerDeps` に `val pip: PipDetector = PipDetector { false }` を追加する(既定は「PiP なし」で、既存のテストと呼び出しの動きを変えない)。`AppGraph` では `AndroidPipDetector` を渡す。
- `openSet` の先頭(幕の表示や分割の起動より前)で、セットの `Slot.App` のパッケージ(左右。redirect がある場合は、起動対象の `targetPkg` も)のうち `d.pip.isInPip(pkg)` が true のものを集める。
  - 無ければ、今までどおりそのまま進める(余計な待ちを入れない)。
  - あれば、それぞれに `d.pip.expand(pkg)` を送り、`DiagLog.d("SetRunner", "pip expand pkg=... sent=...")` を残す。そのあと `d.scheduler` で 100ms ごとに `isInPip` を確かめ、全部が false になるか、1500ms(定数)を過ぎたら、本来の `openSet` の処理を続ける。
    - 期限切れのときも、続けて開く(今までと同じ結果になるだけ)。`DiagLog.d("SetRunner", "pip still pinned pkg=... waited=...")` を残す。
  - 待ちのあいだに別の open・HOME・キャンセルが来たら、待ちを捨てて後から来た操作を優先する。
    - 既存の `operationGeneration` / `pending` の仕組みに合わせること。待ちの最中は「開いている途中」(`_opening` などの既存の状態)として扱い、重ねて開かない。
    - 幕を出すかどうか、通知、失敗の数え方は、PiP の待ちが無いときと同じにする。
  - `openForBoot` の経路も同じ `openSet` を通るなら、それに従う。
- 待ちのあいだ、メディアの一時停止(パネルの `pauseMedia`)はしない(対象外)。

## テスト(SetRunnerTest に追加。範囲を広げない)
- PiP なし: `expand` は呼ばれず、従来どおりすぐに起動する。
- PiP のアプリを含むセット: `expand` が呼ばれ、PiP が解けた後に分割の起動(vendor など既存の起動)が行われる。
- PiP が解けないまま 1500ms 経過: そのまま起動へ進む。
- 待ちの最中に別のセットを開く(または HOME): 古い待ちからの起動は行われない。
- 既存テストの期待値は変えない(必要が出たら変えずに止めて報告する)。

## 検証
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.sets.SetRunnerTest'`

## 報告
- 変えた点の要点、テスト結果(件数・失敗)、RULES.md の 1「決めたこと」
