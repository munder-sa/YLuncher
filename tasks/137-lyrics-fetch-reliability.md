最初に tasks/RULES.md を読み、従う。

# Task 137: 歌詞の取得の失敗を減らす(503 の取り直し、検索の失敗を「歌詞なし」として保存しない)、取得の記録

仕様: `docs/superpowers/specs/2026-11-03-lyrics-design.md` の §3(この Task で r1.4 として変わる点は下に書いたとおり)。

## 背景(2026-11-03 に実測・コード調査で確認)
- LRCLIB は 503 ServerOverloaded をよく返す(初見の曲で get 15 回中 1 回、search 15 回中 4 回)。公式文書は 503 の待機を通常 1 秒としている(`Retry-After` があればそれに従う)。
- 今のコードは、get が 404 → 検索が 503 などで失敗すると `Missing` を返し、それが **7 日キャッシュされる**。get が Plain → 検索失敗のときも Plain が保存され、同期歌詞を探し直さない。get の 503 は検索をせず Failed、取り直しは 30 秒おき。
- 実機で何が起きているか分かる記録が無い。

## 変えるファイル(これ以外は変えない)
- `app/src/main/java/com/gt8d/launcher/media/lyrics/LrclibClient.kt`
- `app/src/main/java/com/gt8d/launcher/media/lyrics/LyricsCache.kt`
- `app/src/main/java/com/gt8d/launcher/media/lyrics/LyricsRepository.kt`
- `app/src/main/java/com/gt8d/launcher/media/MediaWatcher.kt`(記録を1か所足すだけ)
- `app/src/test/java/com/gt8d/launcher/media/lyrics/LrclibClientTest.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/LyricsCacheTest.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/LyricsRepositoryTest.kt`

## 変更
### 1. FetchResult に「保存してよいか」
- `data class FetchResult(val state: LyricsState, val retryAfterSec: Long? = null, val cacheable: Boolean = true)`。`LyricsRepository` は `cacheable == false` の結果をキャッシュに入れない。

### 2. LrclibClient: 1回の要求ごとの取り直し
- get・search それぞれ、応答が **500・502・503・504** のときは、同じ URL を最大 **2 回**取り直す(計 3 回)。待つ時間は `Retry-After`(秒、1〜10 に収める)があればそれ、無ければ 1 回目 1000ms・2 回目 2000ms に、0〜300ms の乱数を足す。待ち方はコンストラクタで差し替えられる口にする: `class LrclibClient(transport, userAgent, sleep: (Long) -> Unit = { Thread.sleep(it) }, jitter: () -> Long = { Random.nextLong(0, 301) })`。`IOException`(接続できない・タイムアウト)は取り直さない(今どおり Offline)。
- get の後に search を呼ぶときは、間に 300ms 待つ(`sleep(300)`)。

### 3. LrclibClient: 判定の変更
- get が 5xx(取り直しても)→ 今どおり `Failed`。**ただし検索も試す**: 検索で同期の候補があればそれ(Synced、cacheable)。無い・失敗なら `Failed`(cacheable=false)。
- get が 404・400・歌詞なし(Missing の元)→ 検索:
  - 検索が成功(200 の配列)して同期の候補があれば Synced。
  - 同期の候補が無く、**平文の候補**(instrumental でない、`plainLyrics` が空白でない、長さの条件は同期の候補と同じ)があれば、同じ並べ方の先頭の `Plain`(cacheable)。
  - 候補が全く無ければ `Missing`(cacheable)。
  - **検索が失敗**(IOException・5xx・その他の 200 以外・JSON が配列でない・大きすぎ)なら `Failed`(cacheable=false)。429 なら `Failed`(cacheable=false、retryAfterSec は今の決まり)。
- get が Plain → 検索:
  - 同期の候補があれば Synced。
  - 検索が成功して同期の候補が無ければ get の `Plain`(cacheable)。
  - 検索が失敗(429 を含む)なら get の `Plain` を **cacheable=false** で返す(表示はするが保存しない。次の機会に探し直す)。429 の retryAfterSec は付ける。
- get が instrumental → 今どおり検索せず Missing(cacheable)。

### 4. LyricsCache
- `Missing` の期限を **1 日**(`24 * 60 * 60 * 1000L`)にする。

### 5. LyricsRepository の取り直しの間隔
- Offline・Failed の後の待ちを、1 回目 10 秒、2 回目 30 秒、3 回目 60 秒にする(回数は今どおり初回+3回)。全体の待機(429)との遅いほう、は今どおり。

### 6. 記録(DiagLog、タグ "Lyrics")。歌詞の本文は出さない
- `LrclibClient`: 要求ごとに `"<get|search> try=<n> code=<code|IOException名> ms=<経過> retryAfter=<値>"`、検索の後に `"search candidates synced=<数> plain=<数> picked=<synced|plain|none> diff=<秒>"`、最後に `"result <状態名> cacheable=<bool>"`。経過の時計は `System.nanoTime()` でよい。
- `LyricsRepository`: 曲が変わったとき `"track title=<曲名> artist=<アーティスト> album=<アルバム> dur=<秒>"`、キャッシュ `"cache hit <状態名>"` / `"cache miss"`、取り直しの予定 `"retry in <ms> attempt=<n>"`、回数切れ `"retry exhausted"`。
- `MediaWatcher.buildSnapshot`: その controller の metadata の**キーの一覧**を、曲(pkg+title)が前回と変わったときだけ1回 `DiagLog.d("Lyrics", "metadata keys pkg=<pkg> <keySet をソートして , 区切り>")`。値は出さない。
- DiagLog はテストでは `DiagLog.dump()` で確かめられる(`DiagLog.clearForTest()` あり)。

## テスト(追加。既存は弱めない。待ち方の口は偽物にして、待った時間を記録する)
`LrclibClientTest`:
- `retries503ThenSucceeds`: get が 503・503・200(synced)→ Synced、get を 3 回呼ぶ、待ちが [1000+j, 2000+j](j は偽の乱数)
- `retryAfterUsedFor503`: 503 に `Retry-After: 3` → 待ち 3000
- `getServerErrorTriesSearch`: get が 503×3(3 回で諦める)、search に synced → Synced
- `getServerErrorAndSearchFails`: get 503×3・search 503×3 → Failed、cacheable=false
- `searchFailureAfterNotFoundIsFailedNotCached`: get 404・search IOException → Failed、cacheable=false
- `searchFailureAfterPlainKeepsPlainNotCached`: get plain・search 503×3 → Plain、cacheable=false
- `plainCandidateUsedWhenNoSynced`: get 404・search に長さの合う plain のみ → Plain、cacheable=true
- `noCandidatesIsMissingCached`: get 404・search `[]` → Missing、cacheable=true
- `waitsBetweenGetAndSearch`: get 404 → search の前に 300 を待つ
- `ioExceptionNotRetried`: get IOException → transport は 1 回だけ、Offline
- `logsRequests`: DiagLog に `get try=1 code=404` と `result` の行がある
`LyricsCacheTest`:
- `missingExpiresAfter1Day`(既存の 7 日のテストは、期限が仕様で変わったので 1 日に合わせて直す。直したことを報告)
`LyricsRepositoryTest`:
- `notCacheableResultIsNotStored`: fetch が Plain(cacheable=false)→ 表示は Plain、別の曲を経て戻ると fetch がもう一度呼ばれる
- `retryIntervals10s30s60s`(既存の 30・60・90 秒のテストは、間隔が仕様で変わったので合わせて直す。報告)
- `logsTrackAndCache`: DiagLog に `track title=` と `cache miss` の行がある

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.*'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、直した既存テストとその理由、決めたこと(RULES.md 1)。

## 追記(codex の質問への回答)
- この Task の仕様と食い違う既存テスト(例: `searchNotUsedWhenServerError` の「503 では検索しない」、検索の失敗で `cacheable=true`/Missing・Plain を期待するもの、Missing の 7 日、取り直しの 30・60・90 秒)は、**新しい仕様に合わせて期待値・呼び出し回数を直してよい**。検証している項目は減らさない(回数・保存の可否・状態を新しい値で確かめる)。直したテストは名前と変更内容をすべて報告する。
- DiagLog は `app/src/main/java/com/gt8d/launcher/diag/DiagLog.kt`(`com.gt8d.launcher.diag.DiagLog`)。
