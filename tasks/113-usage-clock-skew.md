# Task 113: 使用状況の記録の時計のずれを補正し、前面化の見落としを無くす

最初に `tasks/RULES.md` を読み、従うこと。

## 背景(実機で確認済み、2026-11-01)
- 分割は成立しているのに、`waitBoth` が両側の前面化を拾えず 4 秒の Safety で失敗する。セット 2「YouTube | マップ」でも起きた。
- 原因: `UsageStatsManager` の event の timeStamp が、`System.currentTimeMillis()` より **0.5〜1 秒ほど遅れている**(UsageStatsService は起動時の elapsed↔wall の対応を使い、2 秒未満の時計の補正では対応を作り直さないため、ずれがたまる)。例: `wm_on_resume_called` が 15:54:22.415 の YouTube の RESUMED が、usagestats では 15:54:21 秒台。`UsageStatsForegroundEvents.resumedSince` は `timeMs = event.timeStamp - (今の wall - 今の elapsed)` で elapsed に直し、`timeMs >= sinceMs` で絞るため、開いた直後の前面化が「開く前」と見なされて捨てられる。
- そのうえ、Safety の時間切れは `SplitModeSelector.onFailure(Timeout, countable = true)` で数えられ、3 回で `vendorUsable = false`(再起動か「メーカー方式に戻す」まで全部のセットが全画面になる)。実機は実際にこの状態になっていた。

## 変更・作成するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/foreground/UsageStatsForegroundEvents.kt`
- 作成: `app/src/main/java/com/gt8d/launcher/foreground/OwnResumeClock.kt`
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeActivity.kt`(onResume で 1 行だけ)
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelActivity.kt`(onResume で 1 行だけ)
- `app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt`(時間切れの数え方だけ)
- テスト: `app/src/test/java/com/gt8d/launcher/foreground/UsageStatsForegroundEventsTest.kt`、`app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.foreground.*' --tests 'com.gt8d.launcher.sets.*'`

## 仕様
1. **ずれの測り方(自分の画面の再開を目印にする)**:
   - `object OwnResumeClock`: `fun mark(className: String, elapsedMs: Long = SystemClock.elapsedRealtime())` で、自分の Activity(`HomeActivity`・`PanelActivity`)の `onResume` の時刻を、クラス名ごとに直近 1 件だけ持つ(スレッド安全に)。`fun latest(): Pair<String, Long>?`(最も新しいもの)。テスト用に `reset()`。
   - `HomeActivity.onResume` と `PanelActivity.onResume` の最初で `OwnResumeClock.mark(javaClass.name)`。
   - `UsageStatsForegroundEvents.resumedSince(sinceMs)`:
     - 問い合わせの範囲の始まりを `min(sinceMs, 目印の時刻) - 3000ms`(wall に直して)に広げる。
     - 自分のパッケージ(`context.packageName`)で、クラス名が目印と同じ RESUMED の event のうち、elapsed に直した時刻が目印から ±3000ms 以内で最も近いものを探し、`skew = その時刻 - 目印の時刻` とする(遅れていれば負)。見つかれば、その skew を覚える(最後に得た値を使い続ける)。|skew| > 3000ms は使わない。
     - 全部の event の時刻を `timeMs = event を elapsed に直した値 - skew` に直してから、`timeMs >= sinceMs` で絞る(今の絞り方のまま。補正だけ足す)。skew が一度も得られていなければ 0(今のまま)。
     - 補正を `DiagLog.d("Foreground", "skew=…")` に、値が 100ms 以上変わったときだけ残す。
   - 既存の `queryResumed` の差し替え口(テスト用)はそのまま。補正を試せるよう、テスト用に「生の event(パッケージ・クラス・種類・wall の時刻)を返す」差し替え口と、wall/elapsed の時計の差し替え口を足してよい。
2. **時間切れの数え方**: `onCurtainHidden` の Safety(`WaitKind.Both`)で `selector.onFailure(Timeout, …)` を呼ぶのは、**E が何も動かした形跡が無いときだけ**にする。形跡の判定は、呼び直しの判定(`retry skipped: progress`)と同じ式(自分の画面の再開・パネルの再開・左右のどちらかの前面化・純正ランチャーの前面化)。形跡があるときは数えず、`DiagLog.d("SetRunner", "timeout not counted: progress")` を残す。通知帯「開くのに時間がかかっています」は今どおり出す。

## テスト(足す。既存は弱めない)
- `UsageStatsForegroundEventsTest`:
  - 使用状況の時計が 800ms 遅れている場面: 目印(HomeActivity の再開、elapsed 10_000)と、その event(wall 換算で elapsed 9_200)、開く(sinceMs=12_000)の後の YouTube の event(実際は 12_300、記録は 11_500)→ 補正後 12_300 として返る。補正なし(今の作り)だと捨てられることも確かめる(比較のため、目印が無い場合に返らないことを確かめる)。
  - 時計が 500ms 進んでいる場面でも、開く前の event(実際 11_800、記録 12_300)が返らない。
  - 目印に合う event が無い・|skew| > 3000 → 補正なし。
  - 目印の後に得た skew が、次の問い合わせでも使われる。
- `SetRunnerTest`:
  - 形跡あり(左右のどちらかが前面化)の Safety → `selector` の連続失敗が増えない、通知帯は出る。
  - 形跡なしの Safety → 今どおり増える。3 回で `LeftOnly` になる既存のテストは、形跡なしの場面で今どおり通る。

## この Task でしないこと
- `ForegroundWatcher` の判定の式・幕・E の呼び直しの時刻の変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、**決めたこと**。
