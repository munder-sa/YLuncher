最初に tasks/RULES.md を読み、従う。

# Task 139: Apple Music の歌詞に関係しそうな metadata の値の形を記録する(調査用)

実機の記録(Task 137 の `metadata keys`)で、Apple Music の MediaSession の metadata に `com.apple.android.music.playback.metadata.CUSTOM_LYRICS` と `com.apple.android.music.playback.metadata.OFFLINE_LYRICS_FILE_PATH` があると分かった。値が何か(歌詞の本文・有無の印・ファイルの場所)を、本文を記録せずに確かめたい。

## 変えるファイル(これ以外は変えない)
- `app/src/main/java/com/gt8d/launcher/media/MediaWatcher.kt`

## 変更
- 既存の `metadata keys` の記録(211 行目付近、曲が変わったときだけ1回)の直後に、キー名が `LYRICS` を含む(大文字小文字を区別しない)キーごとに、1行ずつ記録する:
  `DiagLog.d("Lyrics", "metadata lyrics key=<キー名> type=<値のクラスの単純名|null> len=<文字列なら長さ、数値・真偽ならその値、それ以外は -> head=<文字列なら先頭 40 文字の改行を \\n に置き換えたもの> file=<文字列が / で始まるなら exists=<File.exists()> canRead=<File.canRead()> size=<読めれば length()>、それ以外は ->")`
  - 値の取り出しは `metadata.bundle`(`MediaMetadata.getBundle()` は無いので、`keySet()` と `getText`・`getString`・`getLong`・`getRating`・`getBitmap` を順に試すか、リフレクションでなく公開 API の範囲で)。`getText(key)` が null でなければ文字列として扱い、null なら `getLong(key)`(0 でも記録)を記録する、の2段でよい。
  - 例外は握って `type=error <例外の単純名>` と記録する。ファイルの中身は読まない(存在・読めるか・大きさだけ)。
- ほかの動作は変えない。

## テスト
- 追加しない(実機の値を見るための一時的な記録。単体テストで再現できない)。既存の `:app:testDebugUnitTest --tests 'com.gt8d.launcher.media.*'` が通ること。

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.*'`

## 報告
- 実行したコマンドと結果、決めたこと(RULES.md 1)。
