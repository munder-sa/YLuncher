最初に tasks/RULES.md を読み、従う。

# Task 129: 歌詞の型と LRC の読み取り

仕様: `docs/superpowers/specs/2026-11-03-lyrics-design.md` の §3.3(曲の鍵)・§3.4(ファイル名)・§4。計画: `docs/superpowers/plans/2026-11-03-lyrics.md` の Task 129。

## 作るファイル(これ以外は作らない・変えない)
- `app/src/main/java/com/gt8d/launcher/media/lyrics/Lyrics.kt`
- `app/src/main/java/com/gt8d/launcher/media/lyrics/LrcParser.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/LrcParserTest.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/LyricsQueryTest.kt`

## 作るもの(名前・型はこのとおり。後の Task が使う)
`package com.gt8d.launcher.media.lyrics`

```kotlin
data class LyricLine(val timeMs: Long, val text: String)

sealed interface LyricsState {
    data object Off : LyricsState
    data object Loading : LyricsState
    data class Synced(val lines: List<LyricLine>) : LyricsState
    data class Plain(val text: String) : LyricsState
    data object Missing : LyricsState
    data object Offline : LyricsState
    data object Failed : LyricsState
}

data class LyricsQuery(val title: String, val artist: String, val album: String?, val durationSec: Long?) {
    val cacheKey: String // 下記
    companion object {
        fun of(title: String?, artist: String?, album: String?, durationMs: Long?): LyricsQuery?
    }
}

object LrcParser {
    fun parse(lrc: String): List<LyricLine>
    fun currentIndex(lines: List<LyricLine>, positionMs: Long): Int
}
```

### LyricsQuery
- `of`: `title`・`artist` は前後の空白を除き、空なら null を返す。`album` は前後の空白を除き、空なら null。`durationSec` は `durationMs` を秒に四捨五入(`(ms + 500) / 1000`)し、1〜3600 のときだけ。それ以外・null なら null。
- `cacheKey`: `[title, artist, album ?: "", durationSec?.toString() ?: ""]` を各々 `trim().lowercase()` にして、kotlinx.serialization の `JsonArray`(`JsonPrimitive` の並び)の文字列にし、その UTF-8 の SHA-256 を小文字の16進(64文字)にしたもの。

### LrcParser.parse(仕様 §4)
- 1行ごとに、先頭に続く `[mm:ss.xx]`・`[mm:ss.xxx]`・`[mm:ss]` をすべて読む(mm は1桁以上、ss は2桁で 00〜59、小数は2桁なら ×10ms、3桁なら ms)。時刻の後ろの文字列を `trim()` したものが本文。本文が空なら `"♪"`。
- 時刻の形でも秒が 60 以上など範囲外のものが1つでもあれば、その行ごと捨てる。
- 時刻で始まらない行(`[ar:…]`・`[ti:…]` などのタグ、普通の文字の行、空行)は捨てる。
- `[offset:+500]`・`[offset:-500]`・`[offset:500]` の行は、最初の1つだけを使い、全行の時刻から引く(結果が 0 未満なら 0)。2つ目以降は無視。
- 時刻の昇順に並べる。同じ時刻は元の順(安定な並べ替え)。
- 改行は `\n`・`\r\n` のどちらでもよい。

### LrcParser.currentIndex
- 時刻が `positionMs` 以下の最後の行の添字。最初の行より前、または空なら -1。二分探索で求める。

## テスト(この名前で書く)
`LrcParserTest`:
- `parsesThreeTimeFormats`: `[01:02.34]a` → 62340、`[01:02.345]b` → 62345、`[01:02]c` → 62000
- `dropsOutOfRangeTime`: `[01:75.00]x` の行は出ない
- `multipleTimestamps`: `[00:10.00][00:20.00]x` → (10000,"x")・(20000,"x")
- `dropsTagLines`: `[ar:foo]`・`[ti:bar]`・ただの文字の行は出ない
- `appliesOffset`: `[offset:+500]` と `[00:10.00]x` → 9500。`-500` → 10500。`+20000` → 0。`[offset:+500]` の後に `[offset:+9000]` があっても 9500
- `emptyTextBecomesNote`: `[00:05.00]` → text `"♪"`
- `sameTimeKeepsOrder`: `[00:05.00]a` `[00:05.00]b` → a, b の順
- `sortsByTime`: `[00:20.00]b` `[00:10.00]a` → a, b
- `currentIndexBoundaries`: 時刻 [1000, 2000, 3000] で 999 → -1、1000 → 0、2500 → 1、99999 → 2。空 → -1

`LyricsQueryTest`:
- `blankTitleOrArtistIsNull`: title `"  "` → null、artist null → null
- `trimsFields`: `" a "`, `" b "`, `" "`, null → title "a"、artist "b"、album null
- `durationRange`: 0 → null、999ms → 1、3600400ms → 3600、3601000ms → null、null → null
- `cacheKeyNormalizes`: 大文字小文字・前後の空白だけが違う2つは同じ鍵。アルバムが違えば別の鍵。鍵は64文字の小文字16進

歌詞の文字列は架空のダミー文を使う。

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.*'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、決めたこと(RULES.md 1)。
