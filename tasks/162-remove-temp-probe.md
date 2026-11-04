最初に tasks/RULES.md を読み、従う。

# Task 162: Task 157 の一時の記録を外す

`app/src/main/java/com/gt8d/launcher/media/MediaWatcher.kt` の `// TEMP(157)` の印の付いた一時の記録(`media id pkg=… std=… apple=…` を出す部分と、そのためだけの比較の履歴)を取り除く。`LyricsMetadataProbe`(曲 ID の読み取り。Task 161 で使っている)は残す。ほかの動作・記録は変えない。

## 変えるファイル
- `app/src/main/java/com/gt8d/launcher/media/MediaWatcher.kt`
- (一時の記録を確かめるテストがあれば)`app/src/test/java/com/gt8d/launcher/media/LyricsMetadataProbeTest.kt`(その部分を消すだけ。報告する)

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.*'`

## 報告
- 実行したコマンドと結果、決めたこと(RULES.md 1)。
