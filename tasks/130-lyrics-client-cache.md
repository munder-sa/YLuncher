最初に tasks/RULES.md を読み、従う。

# Task 130: LRCLIB の取得と歌詞のキャッシュ

仕様: `docs/superpowers/specs/2026-11-03-lyrics-design.md` の §3.1・§3.2・§3.4。計画: `docs/superpowers/plans/2026-11-03-lyrics.md` の Task 130。
Task 129 の `LyricsQuery`・`LyricsState`・`LyricLine`・`LrcParser`(`app/src/main/java/com/gt8d/launcher/media/lyrics/`)を使う。それらは変えない。

## 作るファイル(これ以外は作らない・変えない)
- `app/src/main/java/com/gt8d/launcher/media/lyrics/LrclibClient.kt`
- `app/src/main/java/com/gt8d/launcher/media/lyrics/LyricsCache.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/LrclibClientTest.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/LyricsCacheTest.kt`

## 作るもの(名前・型はこのとおり)
`package com.gt8d.launcher.media.lyrics`

```kotlin
data class HttpResponse(val code: Int, val body: String, val retryAfterSec: Long?, val tooLarge: Boolean = false)
fun interface HttpTransport { fun get(url: String, headers: Map<String, String>): HttpResponse } // 接続できない・タイムアウトは IOException
class UrlConnectionTransport : HttpTransport
data class FetchResult(val state: LyricsState, val retryAfterSec: Long? = null)
class LrclibClient(private val transport: HttpTransport, private val userAgent: String) { fun fetch(q: LyricsQuery): FetchResult }
class LyricsCache(private val dir: File, private val clock: () -> Long, private val maxFiles: Int = 300) {
    fun get(q: LyricsQuery): LyricsState?
    fun put(q: LyricsQuery, state: LyricsState)
}
```

### UrlConnectionTransport
- `HttpURLConnection`。`connectTimeout = 5000`、`readTimeout = 5000`、`instanceFollowRedirects = false`、渡されたヘッダをすべて付ける。
- 本文は UTF-8 で、512 × 1024 バイトを超えたら読むのをやめ `tooLarge = true`(body は空でよい)。200 以外は `errorStream` を読む(null なら空)。
- `Retry-After` ヘッダを秒の整数として読む(読めなければ null)。
- `disconnect()` を finally で必ず呼ぶ。`IOException` はそのまま投げる。
- 単体テストは書かない(Robolectric で本物の通信はしない)。

### LrclibClient.fetch(仕様 §3.1・§3.2)
- URL: `https://lrclib.net/api/get?track_name=<title>&artist_name=<artist>[&album_name=<album>][&duration=<durationSec>]`。値は `URLEncoder.encode(v, "UTF-8")` の結果の `+` を `%20` に置き換えたもの。album・durationSec が null なら付けない。
- ヘッダ: `User-Agent` = コンストラクタの `userAgent`、`Accept: application/json`。
- 振り分けは仕様 §3.2 の表を上から順に:
  1. `IOException` → `Offline`
  2. 429 → `Failed`、`retryAfterSec` = 応答の値
  3. 404・400 → `Missing`
  4. `tooLarge`、200 以外、JSON が読めない(オブジェクトでない) → `Failed`
  5. `instrumental` が true → `Missing`
  6. `syncedLyrics` が文字列で、`LrcParser.parse` が1行以上 → `Synced(lines)`
  7. `plainLyrics` が文字列で空白でない → `Plain(text)`(前後の空白を除く)
  8. それ以外 → `Missing`
- JSON は kotlinx.serialization の `Json.parseToJsonElement`。null の項目・項目が無いときは「無い」扱い。

### LyricsCache(仕様 §3.4)
- ファイル: `File(dir, q.cacheKey + ".json")`。
- 保存する状態: `Synced`・`Plain`・`Missing`。`Off`・`Loading`・`Offline`・`Failed` は `put` しても何もしない。
- 形式(JSON): `{"kind":"synced","savedAt":<clock()>,"lines":[[<timeMs>,"<text>"],…]}`、`{"kind":"plain","savedAt":…,"text":"…"}`、`{"kind":"missing","savedAt":…}`。
- `get`: ファイルが無ければ null。読めない・形式が違う・kind が不明なら、ファイルを消して null。`missing` で `clock() - savedAt >= 7 * 24 * 60 * 60 * 1000L` ならファイルを消して null。
- `put`: `dir` が無ければ作る。書いた後、`dir` 内の `.json` が `maxFiles` を超えたら、`savedAt`(読めなければファイルの更新時刻)の古いものから消す。
- 例外は外に出さない(読み書きの失敗は null・何もしない。`DiagLog.d("Lyrics", …)` で記録してよい)。

## テスト(この名前で書く)
`LrclibClientTest`(偽の `HttpTransport` で、受け取った URL・ヘッダを記録し、決めた応答を返す):
- `buildsUrl`: `LyricsQuery("ダミー ソング&1", "サンプル", "A", 210)` → URL が `https://lrclib.net/api/get?track_name=` + `URLEncoder.encode("ダミー ソング&1","UTF-8").replace("+","%20")` + `&artist_name=` + 同様の「サンプル」 + `&album_name=A&duration=210` と一致。`%20` と `%26` を含む
- `omitsAlbumAndDurationWhenNull`: album・durationSec が null → `album_name`・`duration` が無い
- `sendsUserAgent`: ヘッダ `User-Agent` が渡した値
- `ioExceptionIsOffline`
- `tooManyRequestsCarriesRetryAfter`: 429・retryAfter 30 → `Failed`、30
- `notFoundIsMissing`(404)、`badRequestIsMissing`(400)
- `serverErrorIsFailed`(500)、`badJsonIsFailed`(200・`"oops"`)、`tooLargeIsFailed`(200・tooLarge=true)
- `instrumentalWinsOverLyrics`: `{"instrumental":true,"syncedLyrics":"[00:01.00]x","plainLyrics":"x"}` → `Missing`
- `syncedPreferred`: synced と plain の両方 → `Synced`(行が `LrcParser.parse` と同じ)
- `unparsableSyncedFallsBackToPlain`: `syncedLyrics` が `"no time"`、plain あり → `Plain`
- `bothEmptyIsMissing`: 両方 null → `Missing`

`LyricsCacheTest`(JUnit の `TemporaryFolder`、時計は変数で持つ):
- `roundTripsSyncedAndPlain`: put した Synced・Plain が、別の `LyricsCache` インスタンスの get で等しく返る
- `evictsOldestBeyondMax`: maxFiles=3、時計を進めながら4曲 put → 最初の曲が null、残り3曲は返る
- `missingExpiresAfter7Days`: 7日−1ms では `Missing`、7日ちょうどで null。別インスタンスでも同じ
- `offlineAndFailedNotStored`: put しても get は null、ファイルも無い
- `corruptCacheFileIsIgnored`: 鍵のファイルに `"{broken"` を書く → get は null、例外なし、ファイルは消える
- `writeFailureDoesNotThrow`: `dir` に通常のファイル(ディレクトリでない)を渡して put → 例外なし

歌詞の文字列は架空のダミー文を使う。

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.*'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、決めたこと(RULES.md 1)。
