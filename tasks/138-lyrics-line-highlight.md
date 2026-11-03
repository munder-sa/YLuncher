最初に tasks/RULES.md を読み、従う。

# Task 138: 歌詞は行ごとに強調(単語の時刻があるときだけ塗る)、間奏に「●●●」

ユーザーの指示(2026-11-03、参考の録画で): 「歌詞の現在位置が詳細に取れない場合は、行ごと強調させる」。参考の録画では、今の行は行全体が白く、他の行は薄い。間奏では今の行の場所に「●●●」が出て、点が1つずつ白くなる。

## 変えるファイル(これ以外は変えない)
- `app/src/main/java/com/gt8d/launcher/media/lyrics/Lyrics.kt`(`LyricLine` に単語の時刻を足す)
- `app/src/main/java/com/gt8d/launcher/media/lyrics/LrcParser.kt`
- `app/src/main/java/com/gt8d/launcher/ui/home/LyricsPane.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/LrcParserTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/LyricsPaneTest.kt`

LyricsCache は変えない(単語の時刻は保存しない。保存から読んだ歌詞は行ごとの強調になる。これでよい)。

## 変更
### Lyrics.kt
- `data class LyricWord(val timeMs: Long, val charStart: Int)`(本文の中で、その単語が始まる文字の位置)
- `data class LyricLine(val timeMs: Long, val text: String, val words: List<LyricWord> = emptyList())`(既定値つきで足すので、既存の呼び出しは変えない)

### LrcParser.parse
- 拡張 LRC の単語の時刻 `<mm:ss.xx>`・`<mm:ss.xxx>`・`<mm:ss>` が本文の中にあれば、それを取り除いた本文を `text` にし、各時刻を `LyricWord(時刻 - offset(0 未満は 0), 取り除いた後の本文での文字位置)` として `words` に入れる(時刻の順)。本文の前後の空白を除いたことで位置がずれないようにする。
- 単語の時刻が1つも無ければ `words` は空。範囲外の単語の時刻は、その時刻だけ無視する。

### LyricsPane(Synced)
- **今の行の強調**:
  - `words` が空の行(ほとんど): 行全体を白(不透明度 1.0)にする。塗りは無し。
  - `words` がある行: Task 136 の塗りを使う。ただし割合ではなく、`positionMs` 以下で最後の単語の `charStart` と、次の単語の `charStart`(無ければ本文の長さ)の間を、その2つの時刻で線形に補間した文字位置まで塗る(最初の単語より前は 0)。
  - `fillFraction` は使わなくなれば消してよい(関係するテストも、仕様の変更として消してよい。消したら報告)。
- **間奏の「●●●」**: 本文が「♪」の行(空の行)が今の行のときと、最初の行より前で、最初の行の時刻が再生位置より 5 秒以上先のとき(前奏)は、その行の代わりに、左揃えで直径 12dp の丸3つ(間 8dp、高さは 1 行分)を出す。丸は白で、間奏の始まり〜終わり(「♪」の行の時刻〜次の行の時刻。前奏は 0〜最初の行の時刻)を3等分し、経過した分だけ1つずつ不透明度 1.0 にする(まだの丸は 0.35)。今の行でない「♪」の行は、薄い丸3つ(0.25)で表す。testTag は今の間奏の丸の並びに `player_lyrics_interlude`。
- 他の行の不透明度、全行 24sp・Bold・左揃え、縦の中央、行送り、上下のぼかし、measure・layout で状態に書き込まない作りは、今のまま。

## テスト(追加・変更。既存は弱めない。仕様が変わったところだけ期待値を直して報告)
`LrcParserTest`:
- `parsesWordTimestamps`: `[00:10.00]<00:10.00>あい<00:11.00>うえ` → text「あいうえ」、words [(10000,0),(11000,2)]
- `wordTimestampsWithOffset`: `[offset:+500]` で words の時刻も 500 引かれる
- `noWordTimestampsMeansEmpty`
`LyricsPaneTest`:
- `lineLevelHighlightWhenNoWords`: words の無い行で、`player_lyrics_current` の文字が全体として描かれ、塗りの重ね(Task 136 の白い重ね Text)が無い(重ねに testTag `player_lyrics_fill` を付け、words の無い行では存在しないことを確かめる)
- `wordFillWhenWordsPresent`: words のある行では `player_lyrics_fill` がある
- `interludeDotsOnEmptyLine`: 今の行が「♪」→ `player_lyrics_interlude` がある
- `introDotsBeforeFirstLine`: 最初の行 10000、位置 1000 → `player_lyrics_interlude` がある。位置 6000(残り 4 秒)→ 無い
- `interludeDotsFillProgress`: 間奏の丸の白い数を返す `internal fun interludeLitDots(startMs, endMs, positionMs): Int`(0〜3)を作り、開始 0・終了 3000 で位置 0 → 0、1000 → 1、2000 → 2、3000 → 3

歌詞の文字列は架空のダミー文を使う。

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.media.lyrics.LrcParserTest'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、直した・消した既存テストとその理由、決めたこと(RULES.md 1)。

## 追記(codex の質問への回答)
- 最後の単語(次の単語が無い)の塗りは、最後の単語の時刻から「次の行の時刻」まで、本文の長さへ向けて線形に補間する。最後の行なら、終わりは「行の始まり + 5000ms(`durationMs` があり、それより小さければ `durationMs`)」(Task 136 の終わりと同じ)。
