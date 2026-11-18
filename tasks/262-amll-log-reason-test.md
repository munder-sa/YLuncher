最初に `tasks/RULES.md` を読み、従う。

# Task 262: LyricsRepositoryTest の AMLL 診断ログ期待値を Task 259 の理由コードへ更新

Task 259 で `TtmlParser` の Invalid 診断が `"Invalid timing-or-limit"` の1種類から固定理由コードに変わった。`AmllClient` はこの診断を `amll parse ...` としてログに出すため、全テストで次の1件だけが失敗した。

- `LyricsRepositoryTest.logsAmllFailuresAndLrclibSelection`(`LyricsRepositoryTest.kt` 69行目付近): 入力 `<p begin="bad"/>` の期待値 `"Invalid timing-or-limit"`

## 変えるファイル(これ以外は触らない)
- write: `app/src/test/java/com/gt8d/launcher/media/lyrics/LyricsRepositoryTest.kt`

## 仕様
- 上記の期待値の文字列だけを `"Invalid time-format"` に変える(`begin="bad"` は時刻形式不正で `time-format` になる仕様)。テスト名・入力・ほかの検証(歌詞文字列を出さない確認など)は変えない。RULES.md 4 の例外として、この1か所の期待値変更だけを許可する。
- 製品コードは変えない。

## 使ってよいコマンド(一字一句このまま)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.LyricsRepositoryTest'`

## 報告
- 差分、コマンドと結果(件数・失敗数)、決めたこと
