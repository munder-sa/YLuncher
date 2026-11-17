最初に tasks/RULES.md を読み、従う。

# Task 244: アートワーク欠落切り分け用の DiagLog 診断プローブを追加する

GT8D 起動直後に Apple Music を再生すると、ミニプレイヤーのジャケットと背景色が取れない事象がある。実機で原因を切り分けるため、`MediaSessionCache` と `MediaWatcher` に、プライバシーに配慮した最小限の診断ログを足す。**挙動の変更やフォールバックの実装はこのタスクでは行わない。**

## 作る/変えるファイル(これ以外は触らない)
- write: `app/src/main/java/com/gt8d/launcher/media/MediaSessionCache.kt`
- write: `app/src/main/java/com/gt8d/launcher/media/MediaWatcher.kt`
- write: `app/src/test/java/com/gt8d/launcher/media/MediaWatcherTest.kt`

## 仕様

### 1. `MediaSessionCache.kt` の診断ログ
- `Entry` に `var lastArtworkDiag: String? = null` を足し、`dispose(entry)` で `null` に戻す。
- `withMetadata(entry, snapshot, metadata)` の中で次の診断文字列 `diag` を組み立てる。`entry.lastArtworkDiag != diag` のときだけ `entry.lastArtworkDiag = diag` にして、`DiagLog.d("Artwork", diag)` を1行出す(`lastTitle` では抑止しない。同じ曲名のまま後から Bitmap や URI が付いた場合も記録するため)。
- 書式: `meta pkg=${entry.pkg} hasTitle=${!title.isNullOrBlank()} bmp=${bmpDesc} dispIcon=${dispDesc} uri=${uriDesc} keys=${keys}`
  - `bmpDesc`: `METADATA_KEY_ALBUM_ART` の Bitmap があれば `ALBUM_ART:${w}x${h}`、無くて `METADATA_KEY_ART` があれば `ART:${w}x${h}`、どちらも無ければ `null`
  - `dispDesc`: `METADATA_KEY_DISPLAY_ICON` の Bitmap があれば `${w}x${h}`、無ければ `null`
  - `uriDesc`: `METADATA_KEY_ALBUM_ART_URI` → `METADATA_KEY_ART_URI` → `METADATA_KEY_DISPLAY_ICON_URI` の順に `getString` し、最初の空でない値について `${which}:${scheme}`(`which` は `ALBUM_ART_URI` / `ART_URI` / `DISPLAY_ICON_URI`、`scheme` は `Uri.parse(raw).scheme?.lowercase() ?: "noscheme"`)。どれも無ければ `none`。**URI のホスト・パス・クエリはログに出さない。**
  - `keys`: `metadata?.keySet()?.sorted()?.joinToString(",").orEmpty()`
- 既存の `DiagLog.d("Lyrics", ...)` と、`snapshot.copy(...)` の返り値(`artwork` は従来どおり `ALBUM_ART ?: ART`)は変えない。
- `withPlaybackState` 自体には `DiagLog` を足さない。通常の位置更新(スナップショットがある状態での `onPlaybackStateChanged`)ではログが増えないこと。
- 例外: スナップショットが欠けていて `update()` が `readSnapshot` で取り直す経路では、`withMetadata` を通るので、既存の `[Lyrics]` ログと新しい `[Artwork]` ログが出てよい(既存の動作を保つ。この経路のログを抑止する変更はしない)。

### 2. `MediaWatcher.kt`: メディア通知の LargeIcon の有無だけを記録する
- `MediaSessionCache` に読み取り専用の `fun activePackagesMissingArtwork(): Set<String>` を足す。`entries` のうち `snapshot != null && snapshot.artwork == null` のものの `pkg` の集合を返す。
- `MediaWatcher` に `internal fun packagesMissingArtwork(): Set<String>` を足し、`cache.activePackagesMissingArtwork()` を返す(watcher が止まっていれば空集合)。
- `MediaListenerService.onNotificationPosted(sbn)` で、次をすべて満たすときだけ `DiagLog.d("Artwork", "notif pkg=${pkg} hasMediaSession=true largeIcon=${hasLargeIcon}")` を出す。
  1. `sbn != null` かつ `sbn.packageName in MediaWatcher.packagesMissingArtwork()`
  2. `sbn.notification.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION)` が true
  3. 見るのは `sbn.notification.getLargeIcon() != null` の真偽だけ。通知のタイトル・本文・その他の extras の値は読まない・保存しない。
- 直前と同じ `(pkg, hasLargeIcon)` の繰り返しは出さない(サービス内の小さな `Map<String, Boolean>` で抑止してよい)。
- 既存のコメント「通知の内容は読まない・保存しない」は、上記の例外を明記した文に直す。`onNotificationRemoved` は変えない。

### 変えないこと
- `NowPlaying` のフィールド、選択ロジック、`artwork` の代入結果
- 既存テストのアサーション(特に `hundredPositionCallbacksDoNotReadAnyControllerOrLabelAgain` の「位置コールバックでログが増えない」)

## テスト(`MediaWatcherTest` に1件足す)
- `artworkDiagLogsDelayedSameTitleMetadataWithoutLoggingPositionCallbacks`
  1. 曲名だけ・`ALBUM_ART` 無し・`ALBUM_ART_URI = "https://example.com/a.jpg?token=secret"` のメタデータで始め、`DiagLog.dump()` に `[Artwork] meta pkg=` を含み `bmp=null` と `uri=ALBUM_ART_URI:https` を含む行が1行あること。どの `[Artwork]` 行にも `example.com` と `secret` が含まれないこと。
  2. 同じ曲名のまま `ALBUM_ART` に Bitmap を付けて `onMetadataChanged` を呼ぶと、`bmp=ALBUM_ART:` を含む `[Artwork] meta` 行が増えること。
  3. その後、同じメタデータで `onMetadataChanged` を2回、`onPlaybackStateChanged` を複数回呼んでも、`[Artwork]` 行の数が増えないこと。
- テストの前に `DiagLog.clearForTest()` を呼ぶ。通知側のプローブ(2)の単体テストは不要。

## 使ってよいコマンド(一字一句このまま)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.MediaWatcherTest'`

## 報告
- 変えたファイルと足したテスト名
- 実行したコマンドと結果(件数・失敗数)
- RULES.md の 1「決めたこと」
