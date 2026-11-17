最初に tasks/RULES.md を読み、従う。

# Task 247: ジャケットが届かないとき、メタデータの https 画像 URL から自分で取得して補う

## 背景(実機ログで確認済み、2026-11-17 GT8D 起動直後)
Apple Music は再生開始時に、まず `ALBUM_ART` の Bitmap なしで `ALBUM_ART_URI`(https)だけを入れたメタデータを送り、約0.3秒後に同じ曲で `ALBUM_ART` / `DISPLAY_ICON`(256x256)付きのメタデータを送る。起動直後の通信が不安定なとき、この2回目が来ずジャケットと背景色が欠ける事象がある(Media3 は失敗を URI 単位で保持するため、同じジャケットの曲の間は回復しない)。そこで、Bitmap が無く https/http の画像 URL だけがある状態が一定時間続いたら、YLuncher 自身が URL から取得して `artwork` を補う。

## 作る/変えるファイル(これ以外は触らない)
- create: `app/src/main/java/com/gt8d/launcher/media/ArtworkFallbackLoader.kt`
- create: `app/src/test/java/com/gt8d/launcher/media/ArtworkFallbackLoaderTest.kt`
- write: `app/src/main/java/com/gt8d/launcher/media/MediaSessionCache.kt`
- write: `app/src/main/java/com/gt8d/launcher/media/MediaWatcher.kt`
- write: `app/src/test/java/com/gt8d/launcher/media/MediaWatcherTest.kt`

## 仕様

### 1. 注入用インターフェース(`ArtworkFallbackLoader.kt` に置く)
```kotlin
internal fun interface ArtworkFallback {
    /** 遅延・再試行つきで uri の画像を取りに行く。成功時は onResult をメインスレッドで1回だけ呼ぶ。返り値で取り消す。 */
    fun request(uri: String, onResult: (Bitmap) -> Unit): Cancel
    fun interface Cancel { fun cancel() }
}
```

### 2. `MediaSessionCache` の変更(同期クラスのまま)
- コンストラクタに3番目の引数 `private val artworkFallback: ArtworkFallback? = null` を足す(既定 null。既存テストの `MediaSessionCache(access) { ... }` はそのまま動くこと)。
- `Entry` に `fallbackUri: String?`、`fallbackBitmap: Bitmap?`、`fallbackCancel: ArtworkFallback.Cancel?` を持たせる。
- `withMetadata` での `artwork` の決め方:
  1. `inline = ALBUM_ART ?: ART`(従来どおり)。`inline != null` なら、進行中の要求を取り消し、`fallbackUri` / `fallbackBitmap` / `fallbackCancel` を null にして `inline` を使う。
  2. `inline == null` のとき、`ALBUM_ART_URI` → `ART_URI` → `DISPLAY_ICON_URI` の順で最初の空でない値を取り、スキームが `https` か `http`(小文字比較)のものだけを対象 URI とする。それ以外のスキームや URI 無しなら、要求を取り消して fallback の状態を null にし、`artwork = null`。
  3. 対象 URI が `entry.fallbackUri` と同じなら、新しい要求は出さず `artwork = entry.fallbackBitmap`(取得中なら null)。
  4. 違う URI なら、前の要求を取り消し、`fallbackUri = uri`、`fallbackBitmap = null` にして `artworkFallback?.request(uri) { bmp -> ... }` を出す。結果のコールバックでは、`entry.active` かつ `entry.fallbackUri == uri` かつ `entry.snapshot?.artwork == null` のときだけ、`entry.fallbackBitmap = bmp`、`entry.snapshot = entry.snapshot?.copy(artwork = bmp)` にして `publish()` する。それ以外は捨てる。
- `dispose(entry)` で要求を取り消し、fallback の3つを null にする。
- `withPlaybackState` / 位置コールバックでは要求を出さない・ログを増やさない(`hundredPositionCallbacksDoNotReadAnyControllerOrLabelAgain` を保つ)。
- Task 244 の `[Artwork] meta ...` 診断ログはそのまま(メタデータの中身を表すもので、補った画像は含めない)。
- `activePackagesMissingArtwork()` は補った画像も含めた `snapshot.artwork` で判定する(今のままでよい)。

### 3. `ArtworkFallbackLoader`(本体)
- `internal class ArtworkFallbackLoader(private val scope: CoroutineScope, private val fetch: suspend (String) -> Bitmap?, private val initialDelayMs: Long = 2_000, private val retryDelaysMs: List<Long> = listOf(1_500, 4_000), private val cacheSize: Int = 3) : ArtworkFallback`
- `request(uri, onResult)`:
  1. `scope.launch` で `initialDelayMs` 待つ(普段は 0.3 秒ほどで本物の Bitmap が届き、その時点で取り消されるので通信しない)。
  2. キャッシュ(成功した `uri → Bitmap` を最大 `cacheSize` 件、最近使った順)にあればそれを返す。
  3. 無ければ `fetch(uri)` を試し、null または例外(`CancellationException` は再送出)なら `retryDelaysMs` の間隔で再試行する(初回+2回)。成功したらキャッシュに入れて `onResult`。
  4. すべて失敗したら、その要求を「未解決」として覚える。**失敗結果はキャッシュしない。**
  5. `fun onNetworkAvailable()`: 未解決の要求それぞれについて、`fetch` をすぐ1回だけ試す(遅延なし)。成功したら `onResult`、失敗したら未解決のまま残す。
  6. `Cancel.cancel()` でジョブを取り消し、未解決からも外す。取り消し後に `onResult` を呼ばない。
- `scope` はメインスレッド(本番は `Dispatchers.Main.immediate`)を前提にし、`onResult` と内部状態の更新はその上で行う。
- 本番用の取得関数 `internal suspend fun fetchArtworkBitmap(client: OkHttpClient, uri: String, maxBytes: Long = 8L * 1024 * 1024, maxSide: Int = 512): Bitmap?` を同じファイルに置く:
  - `withContext(Dispatchers.IO)` で GET。HTTP 失敗・`Content-Length` が `maxBytes` 超・本文が読んでいる途中で `maxBytes` を超えたら null。
  - 本文のバイト列を `BitmapFactory` で、まず `inJustDecodeBounds` で寸法を取り、最大辺が `maxSide` 以下になる2の累乗の `inSampleSize` でデコードする。デコード失敗は null。
  - URL・本文・ヘッダはログに出さない。
- 本番用 `OkHttpClient`: `connectTimeout(5s)`、`readTimeout(5s)`、`followRedirects(true)`、`followSslRedirects(true)`、`cookieJar(CookieJar.NO_COOKIES)`。`AppleLyricsOkHttpTransport` のクライアントは流用しない。

### 4. `MediaWatcher` の配線
- `MediaWatcher` に `ArtworkFallbackLoader(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), fetch = { fetchArtworkBitmap(client, it) })` を持たせ、`cache` に渡す。
- `start(ctx)` で `ConnectivityManager.registerNetworkCallback`(`NetworkRequest` に `NET_CAPABILITY_INTERNET`)を登録し、`onAvailable` でメインスレッドから `loader.onNetworkAvailable()` を呼ぶ(コールバックはメインスレッドの Handler 付きで登録するか、`post` する)。`stop()` で解除する。登録・解除の例外は `Log.w` して続行。
- `MediaListenerService` の通知プローブ(Task 244)は変えない。

### 変えないこと
- 既存テストのアサーション全件(特に `hundredPositionCallbacksDoNotReadAnyControllerOrLabelAgain`)
- `NowPlaying` / `SessionSnapshot` のフィールド、セッション選択ロジック
- `ArtworkBackground.kt`(背景色の抽出はそのまま補った画像にも効く)

## テスト

### `MediaWatcherTest` に足す(偽の `ArtworkFallback` を使い、要求の URI と onResult を手で呼べるようにする)
- `uriFallbackFillsArtworkUntilInlineBitmapArrives`: Bitmap なし・`ALBUM_ART_URI = https://…` で要求が1回出て `now.artwork == null`。onResult を呼ぶと `now.artwork` がその Bitmap になる。続けて位置コールバックを数回呼んでも要求は増えない。同じ曲で `ALBUM_ART` 付きメタデータが来ると、`now.artwork` がその Bitmap に置き換わり、要求が取り消される。
- `uriFallbackIgnoresStaleResultsAndNonHttpUris`: 要求中に別 URI の曲に変わると前の要求が取り消され、前の onResult を呼んでも `now.artwork` は null のまま。`content://` の URI では要求を出さない。セッションが破棄された後の onResult は無視される。
- 同じ URI のメタデータが2回来ても要求は1回だけであることも、上のどちらかで確かめる。

### `ArtworkFallbackLoaderTest`(新規。`runTest` + `StandardTestDispatcher`、Robolectric は Bitmap を使う場合のみ `@GraphicsMode(NATIVE)` / `@Config(sdk = [35])`)
- `waitsBeforeFetchingAndCancelSkipsFetch`: `initialDelayMs` より前に取り消すと `fetch` が呼ばれず、onResult も呼ばれない。
- `failureRetriesWithBackoffThenOnNetworkAvailable`: `fetch` が3回 null を返すと、呼び出し時刻が 2000 / 3500 / 7500ms になり、onResult は呼ばれない。その後 `fetch` が成功するようにして `onNetworkAvailable()` を呼ぶと、すぐ onResult が呼ばれる(失敗をキャッシュしていない)。
- `successfulUriIsCachedForNextRequest`: 同じ URI の2回目の要求では `fetch` が呼ばれず、遅延後に同じ Bitmap が返る。
- `httpFetchDownsamplesAndRejectsOversizedBody`: `mockwebserver3` で 1024x1024 の PNG を返すと `fetchArtworkBitmap` の結果の最大辺が 512 以下。`maxBytes` を小さくした呼び出しや 404 では null。

## 使ってよいコマンド(一字一句このまま)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.MediaWatcherTest' --tests 'com.gt8d.launcher.media.ArtworkFallbackLoaderTest'`

## 報告
- 変えたファイルと足したテスト名
- 実行したコマンドと結果(件数・失敗数)
- RULES.md の 1「決めたこと」
