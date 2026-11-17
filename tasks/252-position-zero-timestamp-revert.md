最初に tasks/RULES.md を読み、従う。

# Task 252: positionAt の「positionUpdatedAt <= 0 なら補間しない」を取り消す

Task 250 で `positionAt` に足した `positionUpdatedAt > 0L` の条件が、既存の UI テストを壊した(全テストで14件失敗: `LyricIndexTest`、`InterludeBoundaryUiTest`、`LyricsPaneTest`、`VisibleUiLifecycleTest`)。これらのテストはテスト用の時計を 0 から始め、`positionUpdatedAt = 0` を正しい時刻として使っている。既存テストが正しい仕様なので、この条件だけを取り消す。

## 作る/変えるファイル(これ以外は触らない)
- write: `app/src/main/java/com/gt8d/launcher/media/NowPlaying.kt`
- write: `app/src/test/java/com/gt8d/launcher/media/NowPlayingTest.kt`

## 仕様
- `NowPlaying.positionAt(nowMs)` と `positionAt(s: SessionSnapshot, nowMs)` の両方で、補間の条件を `playing && nowMs > positionUpdatedAt` にする(`positionUpdatedAt > 0L` を外す)。
- 残すもの: `positionMs < 0L` なら `0L` を返す。`nowMs <= positionUpdatedAt` なら補間しない。`durationMs` によるクランプ。

## テスト(`NowPlayingTest`)
- Task 250 で足した `positionAt_guardsUnknownPositionZeroTimestampAndFutureTimestamp` を `positionAt_guardsUnknownPositionAndFutureTimestamp` に名前を変え、`zeroTimestamp`(`positionUpdatedAt = 0L` で `500_000L` でも `2000L`)の2つのアサーションを消す。`futureTimestamp` と `trackStart` は、`zeroTimestamp` を元にせず、同じ値(`playing = true, positionMs = 2000L, durationMs = 10000L`)の `snapshot(...)` から作り直し、アサーションはそのまま残す。
- 代わりに次を足す: `playing = true, positionMs = 0L, positionUpdatedAt = 0L, durationMs = 10000L, speed = 1f` で `positionAt(.., 1500L) == 1500L`(時刻 0 も正しい時刻として補間する)。`SessionSnapshot` 版と `toNowPlaying()` 版の両方。
- 他の既存テストは変えない。

## 使ってよいコマンド(一字一句このまま)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.NowPlayingTest' --tests 'com.gt8d.launcher.ui.home.LyricIndexTest' --tests 'com.gt8d.launcher.ui.home.InterludeBoundaryUiTest' --tests 'com.gt8d.launcher.ui.home.LyricsPaneTest' --tests 'com.gt8d.launcher.ui.lifecycle.VisibleUiLifecycleTest'`

## 報告
- 変えたファイルと、テストの変更内容
- 実行したコマンドと結果(件数・失敗数)
- RULES.md の 1「決めたこと」
