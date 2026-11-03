最初に tasks/RULES.md を読み、従う。

# Task 157: MEDIA_ID の一時記録(実機で Apple Music の曲 ID か確かめるため)

計画 `docs/superpowers/plans/2026-11-04-ttml-lyrics.md` の **Task 157 の Step 1〜2** だけを実施する(Step 3〜5 は人と司令塔が行う。設計書・計画は変えない)。

## 作る・変えるファイル(これ以外は変えない)
- 作る: `app/src/main/java/com/gt8d/launcher/media/LyricsMetadataProbe.kt`、`app/src/test/java/com/gt8d/launcher/media/LyricsMetadataProbeTest.kt`
- 変える: `app/src/main/java/com/gt8d/launcher/media/MediaWatcher.kt`(一時の記録だけ。`// TEMP(157): 正式版の前に外す` の印を付ける)

## 記録の形
- 曲の metadata が変わったとき(pkg+曲名が前回と違う、または2つのキーの値が変わったとき)だけ、`DiagLog.d("Lyrics", "media id pkg=<pkg> title=<曲名> std=<android.media.metadata.MEDIA_ID の値と型> apple=<com.apple.android.music.playback.metadata.METADATA_KEY_MEDIA_ID の値と型>")`。値は文字列なら先頭 80 文字。

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.LyricsMetadataProbeTest'`

## 報告
- 実行したコマンドと結果、決めたこと(RULES.md 1)。
