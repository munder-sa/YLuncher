最初に tasks/RULES.md を読み、従う。

# Task 148: 歌詞のキャッシュに単語の時刻も保存する

Task 138 で `LyricLine.words`(単語の時刻)を足したが、`LyricsCache` は保存しないままなので、単語の時刻を持つ歌詞でも、2回目からはキャッシュから読まれて行ごとの強調になってしまう。保存・読み込みで単語の時刻を保つ。

## 変えるファイル(これ以外は変えない)
- `app/src/main/java/com/gt8d/launcher/media/lyrics/LyricsCache.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/LyricsCacheTest.kt`(追加のみ)

## 変更
- 保存形式の版を `"v":3` にする。版 3 の `lines` の各要素は `[timeMs, "text", [[wordTimeMs, charStart], …]]`(単語が無ければ3つ目は空の配列 `[]`)。
- 読み込みは版 3 だけを受け付け、版 2 以前のファイルは今の決まりどおり無いものとして消す(取り直させる。単語の時刻を持つ歌詞を取り直すため)。
- `Plain`・`Missing` の形は今どおり(版の数字だけ 3)。期限・件数の上限・壊れたファイルの扱いは今どおり。

## テスト(LyricsCacheTest に追加)
- `roundTripsWordTimestamps`: `LyricLine(1000, "あいうえ", listOf(LyricWord(1000, 0), LyricWord(1500, 2)))` を含む Synced を put → 別のインスタンスで get して、words まで等しい
- `version2FileIsIgnored`: `"v":2` の synced のファイル → null、ファイルは消える
- `writtenFileHasVersion3`: put したファイルの JSON に `"v":3`
- 既存の `writtenFileHasVersion2` は版が仕様で変わったので `3` に合わせて直してよい(直したら報告)

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.*'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、直したテスト、決めたこと(RULES.md 1)。

## 追記(codex の質問への回答)
- 既存の `differentVersionIsIgnored` の「無効な版」の値 `"3"` を `"4"` に変えてよい(無効な版を拒否する検証は保つ)。報告に書く。
