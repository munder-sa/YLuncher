最初に tasks/RULES.md を読み、従う。

# Task 250: 通信開通(VALIDATED)時の再試行・同一曲サイズ変更時の背景色維持・通知診断リセット・再生位置ガード

v2.3.5 のアートワーク補完と再生位置計算に残っている4つの境界ケースを修正する。

## 背景
1. `MediaWatcher` の `NetworkCallback` は `onAvailable` のみを見ているため、Wi-Fi リンク確立直後（DNS・テザリング上流開通前）に単発の再試行が失敗すると、その後に `NET_CAPABILITY_VALIDATED` が付いても `onAvailable` が再発火せず `unresolved` が残る。
2. `ArtworkFallbackLoader` の 512px 画像表示後に遅れて 256x256 のインライン `ALBUM_ART` が届いた際、`ArtworkBackgroundHolder` が同一曲でも `colors.value = null` に一旦落とすため背景が一瞬グレーに点滅する。
3. `MediaListenerService` の `lastLargeIconByPackage` が解放されないため、一度記録したパッケージで後から再びジャケット欠落が起きても `[Artwork] notif` ログが抑止される。
4. `NowPlaying.positionAt` / `positionAt(SessionSnapshot, Long)` で `positionMs < 0L`（`PLAYBACK_POSITION_UNKNOWN`）や `positionUpdatedAt <= 0L` のまま `playing = true` になった場合、起動からの経過時間が加算されて曲末へ張り付く。

## 作る/変えるファイル(これ以外は触らない)
- write: `app/src/main/java/com/gt8d/launcher/media/MediaWatcher.kt`
- write: `app/src/main/java/com/gt8d/launcher/media/NowPlaying.kt`
- write: `app/src/main/java/com/gt8d/launcher/ui/home/ArtworkBackground.kt`
- write: `app/src/test/java/com/gt8d/launcher/media/MediaWatcherTest.kt`
- write: `app/src/test/java/com/gt8d/launcher/media/NowPlayingTest.kt`
- write: `app/src/test/java/com/gt8d/launcher/ui/home/ArtworkBackgroundTest.kt`

## 仕様

### 1. `NET_CAPABILITY_VALIDATED` 立ち上がり時の再試行（`MediaWatcher.kt`）
- 単体テストしやすいよう、ネットワーク識別子 `N` を型引数にとる `internal class ValidatedNetworkTracker<N>(private val onRetry: () -> Unit)` を `MediaWatcher.kt` 内に定義する:
  - `private val validated = mutableSetOf<N>()` を保持する。
  - `fun onAvailable(network: N)`: `onRetry()` を呼ぶ。
  - `fun onCapabilitiesChanged(network: N, isValidated: Boolean)`: `isValidated == true` のとき `if (validated.add(network)) onRetry()` を実行する（電波強度変化等による同一ネットワークの連続 `onCapabilitiesChanged` では再発火させない）。`isValidated == false` のときは `validated.remove(network)` する。
  - `fun onLost(network: N)`: `validated.remove(network)` する。
  - `fun clear()`: `validated.clear()` する。
- `MediaWatcher.registerNetworkCallback` 内の `ConnectivityManager.NetworkCallback` からこのトラッカー（`ValidatedNetworkTracker<Network> { artworkLoader.onNetworkAvailable() }`）を使い、`onAvailable`、`onCapabilitiesChanged(network, networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))`、`onLost` をそれぞれ委譲する（`if (networkCallback !== this) return` のガードと `Handler(Looper.getMainLooper())` 指定は維持する）。`stop()` 時はクリアされるようにする。

### 2. 同一曲のサイズ変更中に UI 背景色を維持する（`ArtworkBackground.kt`）
- `ArtworkBackgroundHolder.select(now)` で `next != image` の分岐に入る際、直前の `val prevImage = image` と `val prevColors = colors.value` を退避する:
  - `next != null && prevImage != null && next.track == prevImage.track`（同一 `TrackKey` で画像サイズだけ変わった場合）は、`colors.value = prevColors` として再抽出完了まで直前の色を保持する。
  - 曲が変わった場合（`next.track != prevImage.track`）や `next == null` の場合は従来どおり `colors.value = null` にする。
- `remove(client)` の動きは変えない(全画面が離れたあとも `image` / `retained` / `completed` は残す。既存テスト `sameArtworkDoesNotReextract` が、全画面離脱後に同じ曲で戻っても再抽出しないことを確かめているため)。
- `colorsFor(now: NowPlaying?)` と `imageColorsFor(now: NowPlaying?)` の役割を次のとおり明確に分ける:
  - `fun colorsFor(now: NowPlaying?): ArtworkColors? = if (completed && now.imageKey() == image) colors.value else null`（当該サイズの抽出が完了しているときのみ返す。既存の `sizeChangeRestartsExtraction` の契約を維持する）
  - `fun imageColorsFor(now: NowPlaying?): ArtworkColors? = if (now?.artwork != null && now.imageKey()?.track == image?.track) colors.value else null`（`rememberArtworkBackground` 用。同一曲のサイズ違い再抽出中も引き継いだ `colors.value` を返す）

### 3. メディア通知 `LargeIcon` 診断ログの抑止リセット（`MediaWatcher.kt`）
- 単体テストしやすいよう、`MediaWatcher.kt` 内に `internal class NotificationArtworkDiag(private val onLog: (pkg: String, hasLargeIcon: Boolean) -> Unit)` を定義する:
  - `private val lastLargeIconByPackage = mutableMapOf<String, Boolean>()` を持つ。
  - `fun onPosted(pkg: String, missingPackages: Set<String>, hasMediaSession: Boolean, hasLargeIcon: Boolean)`:
    1. まず `lastLargeIconByPackage.keys.retainAll(missingPackages)` を実行し、既にジャケットが揃ったパッケージの過去状態を消す。
    2. `if (pkg !in missingPackages || !hasMediaSession) return`
    3. `if (lastLargeIconByPackage[pkg] == hasLargeIcon) return`
    4. `lastLargeIconByPackage[pkg] = hasLargeIcon` し、`onLog(pkg, hasLargeIcon)` を呼ぶ。
  - `fun onRemoved(pkg: String?)`: `if (pkg != null) lastLargeIconByPackage.remove(pkg)`
  - `fun clear()`: `lastLargeIconByPackage.clear()`
- `MediaListenerService` はこの `NotificationArtworkDiag` を使い、すべての `onNotificationPosted` で `missing = MediaWatcher.packagesMissingArtwork()` を取って `diag.onPosted` を呼ぶ(回復済みパッケージの記憶を落とすため)。ただし `EXTRA_MEDIA_SESSION` の有無と `getLargeIcon() != null` を調べるのは `sbn.packageName in missing` のときだけで、それ以外は両方 `false` を渡す。そのうえで `onNotificationPosted`（従来どおり通知のタイトル・本文・他の `extras` の値は一切読まず、`sbn.packageName in missing` と `EXTRA_MEDIA_SESSION` を確認したときだけ `getLargeIcon() != null` を渡す。ただし `missing` 取得と `retainAll` により回復済みパッケージの記憶が落ちるようにする）、`onNotificationRemoved(sbn)`（`diag.onRemoved(sbn?.packageName)`）、`onListenerDisconnected()`（`diag.clear()`）を接続する。

### 4. `positionAt` の負値・未初期化時刻ガード（`NowPlaying.kt`）
- `NowPlaying.positionAt(nowMs: Long): Long` と `positionAt(s: SessionSnapshot, nowMs: Long): Long` の両方で次を満たすようにする:
  - `positionMs < 0L`（`PlaybackState.PLAYBACK_POSITION_UNKNOWN == -1L` 等）のときは再生状態にかかわらず `0L` を返す。
  - `positionMs >= 0L` のとき、`playing && positionUpdatedAt > 0L && nowMs > positionUpdatedAt` を満たす場合のみ `positionMs + ((nowMs - positionUpdatedAt) * speed).toLong()` で補間し、それ以外（一時停止中、`positionUpdatedAt <= 0L`、または `nowMs <= positionUpdatedAt`）は `positionMs` をそのまま `raw` とする。
  - `durationMs` によるクランプ（`durationMs > 0L` なら `raw.coerceIn(0L, durationMs)`、それ以外は `raw.coerceAtLeast(0L)`）は従来どおり維持する。

### 変えないこと
- 既存の単体テスト（`MediaWatcherTest`、`NowPlayingTest`、`ArtworkBackgroundTest`）の名前とアサーション（一切弱めない）
- 通知のプライバシー制約（タイトル・本文・任意 `extras` 値を読まない・保存しない）
- `ArtworkFallbackLoader.kt` と `MediaSessionCache.kt` の動作

## テスト

1. `MediaWatcherTest` に足す:
   - `validatedNetworkTrackerRetriesOnAvailableAndValidatedRisingEdgeOnly`:
     - `onAvailable("net1")` で1回発火、続く `onCapabilitiesChanged("net1", isValidated = false)` では発火せず、`onCapabilitiesChanged("net1", isValidated = true)` で2回目が発火、同じ `"net1"` のまま再度 `isValidated = true` が来ても発火せず、`isValidated = false` → `true` または `onLost("net1")` 後の `isValidated = true` では再び発火すること。
   - `notificationArtworkDiagResetsAfterArtworkRecoveryRemovalAndClear`:
     - `missing = setOf(apple)` で `onPosted(apple, missing, true, true)` を2回呼ぶと1回だけログされ、その後アートワーク回復（`missing = emptySet()` での `onPosted`）や `onRemoved(apple)` や `clear()` を挟んで再び `missing = setOf(apple)` になると同じ `hasLargeIcon = true` でも再ログされること。また `hasMediaSession = false` や対象外パッケージではログされないこと。

2. `ArtworkBackgroundTest` に足す:
   - `sameTrackSizeChangeKeepsImageColorsWhileReextracting`:
     - 初回 `16x16` Bitmap で `red` を抽出完了後、同じ曲（同一 `TrackKey`）のまま `8x16` Bitmap（抽出結果 `blue`）に更新した直後（`runCurrent()` 前）に、`holder.colors.value == red` かつ `holder.imageColorsFor(next) == red` が維持されつつ `holder.colorsFor(next) == null` であり、`runCurrent()` 後に `holder.imageColorsFor(next) == blue` かつ `holder.colorsFor(next) == blue` に切り替わること。

3. `NowPlayingTest` に足す:
   - `positionAt_guardsUnknownPositionZeroTimestampAndFutureTimestamp`:
     - `SessionSnapshot` と `NowPlaying` の両方で、① `playing = true, positionMs = -1L, positionUpdatedAt = 1000L` のとき `positionAt(5000L) == 0L`、② `playing = true, positionMs = 2000L, positionUpdatedAt = 0L, durationMs = 10000L` のとき `positionAt(500_000L) == 2000L`（曲末 `10000L` に張り付かない）、③ `playing = true, positionMs = 2000L, positionUpdatedAt = 9000L` で `positionAt(5000L) == 2000L`、④ 通常の曲頭 `playing = true, positionMs = 0L, positionUpdatedAt = 1000L, speed = 1f` では `positionAt(2500L) == 1500L` になること。

## 使ってよいコマンド(一字一句このまま)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.MediaWatcherTest' --tests 'com.gt8d.launcher.media.NowPlayingTest' --tests 'com.gt8d.launcher.ui.home.ArtworkBackgroundTest'`

## 報告
- 変えたファイルと足したテスト名
- 実行したコマンドと結果(件数・失敗数)
- RULES.md の 1「決めたこと」
