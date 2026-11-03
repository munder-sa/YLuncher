最初に tasks/RULES.md を読み、従う。

# Task 131: 歌詞の取得の時機・設定・受け渡し・今の行

仕様: `docs/superpowers/specs/2026-11-03-lyrics-design.md` の §3.3・§4(再生位置の求め方)・§5.1。計画: `docs/superpowers/plans/2026-11-03-lyrics.md` の Task 131。
Task 129・130 の `media/lyrics/` の部品(`LyricsQuery`・`LyricsState`・`LyricLine`・`LrcParser`・`LrclibClient`・`UrlConnectionTransport`・`LyricsCache`・`FetchResult`)を使う。下に書いた `LrclibClient` の1点以外は変えない。

## 作る・変えるファイル(これ以外は作らない・変えない)
- 作る: `app/src/main/java/com/gt8d/launcher/media/lyrics/LyricsRepository.kt`
- 作る: `app/src/main/java/com/gt8d/launcher/ui/home/LyricIndex.kt`
- 作る: `app/src/test/java/com/gt8d/launcher/media/lyrics/LyricsRepositoryTest.kt`
- 作る: `app/src/test/java/com/gt8d/launcher/ui/home/LyricIndexTest.kt`
- 変える: `app/src/main/java/com/gt8d/launcher/config/Model.kt`
- 変える: `app/src/main/java/com/gt8d/launcher/media/NowPlaying.kt`
- 変える: `app/src/main/java/com/gt8d/launcher/media/MediaWatcher.kt`
- 変える: `app/src/main/java/com/gt8d/launcher/AppGraph.kt`
- 変える: `app/src/main/java/com/gt8d/launcher/ui/panel/PanelContent.kt`(PanelState・PanelActions に項目を足すだけ)
- 変える: `app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt`(状態と操作を流すだけ。HomeMiniPlayer・MediaCard の呼び出しは変えない)
- 変える: `app/src/main/java/com/gt8d/launcher/media/lyrics/LrclibClient.kt`(429 の `retryAfterSec` の既定 60 だけ)
- 変える: `app/src/test/java/com/gt8d/launcher/media/lyrics/LrclibClientTest.kt`(`tooManyRequestsWithoutHeaderIs60` を追加: 429・retryAfter null → Failed, 60)
- 変える: `app/src/test/java/com/gt8d/launcher/media/NowPlayingTest.kt`(追加のみ)
- 変える: `app/src/test/java/com/gt8d/launcher/config/ConfigCodecTest.kt`(追加のみ)
- 変える: `app/src/test/java/com/gt8d/launcher/config/ConfigRepositoryTest.kt`(追加のみ)

## 変更
### config/Model.kt
- `LauncherConfig` の最後に `val lyricsEnabled: Boolean = false` を足す。`CONFIG_VERSION` は変えない。

### media/NowPlaying.kt・MediaWatcher.kt
- `SessionSnapshot` に `val album: String? = null`(最後に)。`MediaWatcher.buildSnapshot` で `metadata?.getText(MediaMetadata.METADATA_KEY_ALBUM)?.toString()` を入れる。
- `NowPlaying` に `val album: String? = null`、`val metaTitle: String? = null`(最後に)。`toNowPlaying()` で `album = album?.takeIf { it.isNotBlank() }`、`metaTitle = title?.takeIf { it.isNotBlank() }`(アプリ名で埋めたときは null)。
- `val NowPlaying.lyricsQuery: LyricsQuery? get() = LyricsQuery.of(metaTitle, artist, album, durationMs)` を `NowPlaying.kt` に足す。

### media/lyrics/LyricsRepository.kt(仕様 §3.3)
```kotlin
class LyricsRepository(
    scope: CoroutineScope,
    nowPlaying: StateFlow<NowPlaying?>,
    enabled: Flow<Boolean>,
    private val cache: LyricsCache,
    private val fetch: suspend (LyricsQuery) -> FetchResult,
    private val clock: () -> Long,
    private val io: CoroutineDispatcher,
) { val state: StateFlow<LyricsState> }
```
- 曲の鍵 = `Pair(now.pkg, now.lyricsQuery)`(lyricsQuery が null なら鍵も null)。`nowPlaying` を鍵に写して `distinctUntilChanged()`、`enabled` も `distinctUntilChanged()`。両者を `combine` して `collectLatest` で処理する(新しい値が来たら前の処理は取り消される)。
- オフ → `Off`。オンで鍵が null → `Off`(通信しない)。
- オンで鍵がある:
  1. `withContext(io) { cache.get(q) }` があれば、それを出して終わり(待たない)。
  2. 無ければ `Loading` を出し、`delay(600)`。
  3. 全体の待機(下記)が明けるまで `delay`。
  4. `fetch(q)` の結果を出す。Synced・Plain・Missing なら `withContext(io) { cache.put(q, state) }`。
  5. 結果が Offline・Failed なら、取り直し: 直前の結果から 30 秒待ち(全体の待機があればその明けと遅いほう)、3〜4 をくり返す。取り直しは最大 3 回(初回と合わせて計 4 回)。取り直しを待つ間は、直前の Offline/Failed を出したまま。
- **全体の待機**: `FetchResult.retryAfterSec` が null でない結果を受けたら、`nextAllowedAt = clock() + retryAfterSec * 1000`。値は Repository のフィールドに持ち、曲替え・オン/オフでは消さない(どの曲の fetch も、これが明けるまで呼ばない)。
- `LrclibClient` の 429 は、応答の `Retry-After` が無い・読めないとき `retryAfterSec = 60` を返すように直す(仕様 §3.3)。0 以下の値も 60 にする。
- 取り消し: `collectLatest` の取り消しで、待機中・通信中の処理を止める。`CancellationException` は握りつぶさない。取り消された後に返った結果は出さない(`collectLatest` の中で出すので自然にそうなる。念のため世代番号を照合してもよい)。
- `fetch` が例外を投げたら(取り消し以外)、Failed として扱う。

### ui/home/LyricIndex.kt(仕様 §4)
```kotlin
@Composable
fun rememberLyricIndex(now: NowPlaying, lines: List<LyricLine>, clock: () -> Long = { SystemClock.elapsedRealtime() }): State<Int>
```
- `LaunchedEffect(now, lines)` で `LrcParser.currentIndex(lines, now.positionAt(clock()))` を入れる。`now.playing` の間は `delay(200)` ごとに求め直す。停止中は1回だけ。

### AppGraph.kt
- `private val lyricsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`
- `val lyrics: LyricsRepository by lazy { … }`:
  - `nowPlaying = MediaWatcher.nowPlaying`、`enabled = latestConfig(既存の _latestConfig の StateFlow).map { it?.lyricsEnabled == true }`
  - `cache = LyricsCache(File(app.filesDir, "lyrics"), System::currentTimeMillis)`
  - `fetch = { q -> withContext(Dispatchers.IO) { client.fetch(q) } }`、`client = LrclibClient(UrlConnectionTransport(), userAgent)`
  - `userAgent = "YLuncher/$versionName (https://github.com/munder-sa/YLuncher)"`、`versionName` は `app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"`(例外なら "?")
  - `clock = SystemClock::elapsedRealtime`、`io = Dispatchers.IO`
  - BuildConfig は使わない。

### ui/panel/PanelContent.kt
- `PanelState` の最後に `val lyrics: LyricsState = LyricsState.Off`、`val lyricsEnabled: Boolean = false`。
- `PanelActions` の最後に `val onToggleLyrics: () -> Unit = {}`。
- それ以外は変えない。

### ui/panel/PanelScreen.kt
- `val lyrics by AppGraph.lyrics.state.collectAsState()` を PanelState の `lyrics` に、`config.lyricsEnabled` を `lyricsEnabled` に入れる。
- `PanelActions` の `onToggleLyrics` で、既存の保存と同じ形(`scope.launch { try { AppGraph.config.update { it.copy(lyricsEnabled = !it.lyricsEnabled) } } catch (e: CancellationException) { throw e } catch (e: Exception) { DiagLog.d("Panel", "lyrics toggle save failed: $e") } }`)。
- `HomeWideContent`・`PanelContent` の部品の呼び出し(HomeMiniPlayer・MediaCard)は変えない(Task 132・133 で渡す)。

## テスト(この名前で書く)
`LyricsRepositoryTest`(`runTest` と `StandardTestDispatcher`。`clock = { testScheduler.currentTime }`、`io` はテストのディスパッチャ。`nowPlaying` は `MutableStateFlow`、`enabled` は `MutableStateFlow`。`fetch` は呼ばれた query を記録し、テストごとに決めた結果を返す偽物。遅らせたいときは `CompletableDeferred` を待つ。`cache` は `TemporaryFolder` の本物の `LyricsCache`):
- `offDoesNotFetch`: オフで曲を変えても fetch 0 回、state = Off
- `noQueryDoesNotFetch`: metaTitle null、または artist null の NowPlaying で fetch 0 回、state = Off
- `debounce600ms`: オンで曲A → 599ms 進めて fetch 0 回・state = Loading、1ms 進めて 1 回
- `rapidChangesFetchOnlyLast`: 100ms ごとに5曲 → 最後の曲だけ1回
- `staleResponseIsDropped`: 曲A の fetch を CompletableDeferred で止めたまま曲B へ → A に Synced を渡しても state は A の歌詞にならない。B の結果が出る
- `cancelWhileInFlight`: 曲A の fetch 中にオフ → state = Off、A の結果を後で渡しても Off のまま
- `loadingThenSynced`
- `cacheHitSkipsFetch`: 先に cache.put した曲は、待たずに(0ms で)その状態、fetch 0 回
- `resultIsCached`: Synced を取ったあと、別の曲を経て戻ると fetch は増えない
- `positionOrPlayStateChangeDoesNotRefetch`: 同じ曲で positionMs・playing・artwork だけ変えた NowPlaying を流しても fetch は1回のまま
- `globalBackoffAcrossTracks`: 曲A で Failed(retryAfterSec=30) → 10 秒後に曲B → 曲B の fetch は A の失敗から 30 秒経つまで呼ばれず、30 秒で呼ばれる
- `backoffSurvivesToggle`: 曲A で Failed(retryAfterSec=30) → オフ→オン(5 秒後) → 30 秒まで fetch が増えない
- `retriesThreeTimesAfterFirst`: Offline を返し続ける → fetch は 600ms・30.6s・60.6s・90.6s の計4回、さらに 200 秒進めても4回。3回目に Synced を返す版では state = Synced
- `retryCountResetsOnTrackChangeAndToggle`: 曲A で4回失敗した後、オフ→オンで再び最大4回呼ぶ。曲B でも同様

`LyricIndexTest`(Robolectric の Compose テスト。`composeTestRule.mainClock.autoAdvance = false`。時計は `var t` を読むラムダ):
- `advancesEvery200msWhilePlaying`: 行 [1000, 2000, 3000]、`NowPlaying(playing=true, positionMs=0, positionUpdatedAt=0, speed=1f)`、t=500 で -1。t=2100 にして `mainClock.advanceTimeBy(200)` → 1
- `pausedDoesNotAdvance`: playing=false・positionMs=500 で t を進めて 200ms 進めても -1
- `seekJumps`: playing=true の NowPlaying を positionMs=9000・positionUpdatedAt=t にした新しい値に差し替える → 次のフレーム以降で 2

`NowPlayingTest`(追加): `metaTitleNullWhenFilledWithAppLabel`(title null → metaTitle null、title はアプリ名)、`albumPassedThrough`、`lyricsQueryNullWithoutArtist`
`ConfigCodecTest`(追加): `decodesOldConfigWithoutLyricsEnabled`(既存のテストにある JSON の作り方で、`lyricsEnabled` の無い JSON → false)、`lyricsEnabledRoundTrips`(true を encode → decode で true)
`ConfigRepositoryTest`(追加): `resetTurnsLyricsOff`(true に update → reset → false)

既存のテストは弱めない。

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.*' --tests 'com.gt8d.launcher.config.*' --tests 'com.gt8d.launcher.ui.home.LyricIndexTest'`
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.panel.*'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、決めたこと(RULES.md 1)。
