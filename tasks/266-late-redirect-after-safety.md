最初に tasks/RULES.md を読み、従う。

# Task 266: 幕が Safety で閉じたあとに分割が整ったら、遅れて redirect(URI)を送る

## 背景(実機で確認済み、2026-11-18、2.3.9-beta1)
- Task 264 で、セットのマップは「分割が整ったら(waitBoth ready)`startRedirect` で free=1 の URI を送る」ようにした。
- マップが温まっているときは成功する(ready 1秒、`redirect target=com.google.android.apps.maps sent=true`)。
- マップが冷えているとき(起動直後に想定)、マップが分割に出るまで約4秒かかった。そのあいだに幕が `onCurtainHidden reason=Safety waitKind=Both` で閉じ、`timeout not counted: progress` となり、redirect は送られなかった。結果、マップは通常画面のまま。ログ:
  - 17:23:46.507 open result=Started mode=Vendor
  - 17:23:49.872 マップの START
  - 17:23:50.490 onCurtainHidden reason=Safety / timeout not counted: progress
  - 17:23:53.631 panelResumedInSplit
- 既存の redirect(ChatGPT など、メタデータで宣言するもの)も同じ穴を持つ。

## 作る/変えるファイル(これ以外は触らない)
- write: `app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt`
- write: `app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt`

## 仕様
- `waitBoth` で待っていた redirect のうち、`component` か `uri` を持つもの(ready 時に送る対象と同じ条件)を、幕が **Safety** で閉じ、かつ `progressed == true`(`timeout not counted: progress` の場合)のときに限り「遅れて送る」候補として残す。
- 遅れて送る条件は、waitBoth の ready と同じ判定とする:
  - パネルのセット: `panelResumed` が立ち、かつアプリ側のパッケージが `startMs` 以降に resumed。
  - パネルなし: 左右の両方が `startMs` 以降に resumed。
  - 判定には既存の仕組み(`d.watcher` の問い合わせ・`d.scheduler`・`POLL_MS`)を使う。新しいスレッドやタイマーの仕組みは作らない。
- 期限は Safety から 10 秒(定数を追加)。期限を過ぎたら送らずに捨て、`DiagLog.d("SetRunner", "late redirect expired ...")` を残す。
- 次のどれかが起きたら、送らずに捨てる: 別のセットを開く・HOME へ戻る・キャンセル・`operationGeneration` が変わる・監視が使えなくなる。
- 条件を満たしたら `d.apps.startRedirect(redirect, adjacent = false)` を1回だけ送り、`DiagLog.d("SetRunner", "late redirect target=... sent=...")` を残す。
- 通常の ready で送る動き、Safety 以外(Failed・Cancelled・Timed など)の動き、`progressed != true` の Safety の動き、幕・通知・失敗の数え方は変えない。

## テスト(SetRunnerTest に追加。範囲を広げない)
- 冷えたマップの再現: Safety(progress あり)で幕が閉じたあと、パネルとアプリが resumed になると、redirect が1回だけ送られる。
- 期限(10秒)を過ぎてから整っても送られない。
- Safety 後、整う前に別のセットを開く(または HOME)と送られない。
- progress なしの Safety では送られない。
- 既存のテストは変えない(期待値を変える必要が出たら、変えずに止めて報告する)。

## 検証
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.sets.SetRunnerTest'`

## 報告
- 変えた点の要点、テスト結果(件数・失敗)、RULES.md の 1「決めたこと」
