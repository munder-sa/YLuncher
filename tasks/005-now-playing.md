# Task 5: 再生情報

計画 `docs/superpowers/plans/2026-10-28-gt8d-launcher.md` の Task 5 に従う。Task 0・2・3 の成果物は、下で許可した範囲以外は変更しない。

## 作るファイル(これで全部。許可外の書込みは拒否され、そこで停止する)

- `app/src/main/java/com/gt8d/launcher/media/NowPlaying.kt` … 純粋なロジックとデータクラス
- `app/src/main/java/com/gt8d/launcher/media/MediaWatcher.kt` … Android 連携
- `app/src/test/java/com/gt8d/launcher/media/NowPlayingTest.kt`

既存の更新が許可されているのは `app/src/main/AndroidManifest.xml` だけ。`MediaListenerService` を登録する(下記)。

## 仕様 1: NowPlaying.kt(JVM テストで固める部分)

パッケージ `com.gt8d.launcher.media`。`android.graphics.Bitmap` を型として使うほかは、Android に依存しない。

```kotlin
data class SessionSnapshot(
    val pkg: String, val appLabel: String, val title: String?, val artist: String?,
    val artwork: Bitmap?, val durationMs: Long, val positionMs: Long,
    val positionUpdatedAt: Long, val speed: Float, val playing: Boolean,
    val hasFavoriteAction: Boolean,
)
data class NowPlaying(
    val pkg: String, val title: String, val artist: String?, val artwork: Bitmap?,
    val durationMs: Long?, val playing: Boolean, val hasFavorite: Boolean,
)
fun SessionSnapshot.toNowPlaying(): NowPlaying
fun positionAt(s: SessionSnapshot, nowMs: Long): Long
fun pickSession(sessions: List<SessionSnapshot>, lastActivePkg: String?): SessionSnapshot?
```

- `toNowPlaying`: `title` が null または空白なら `appLabel` を `title` にする。`durationMs` が 0 以下(-1 を含む)なら `NowPlaying.durationMs = null`(進行バーを隠す印)。`artist` は `null`・空白のとき null。`hasFavorite = hasFavoriteAction`
- `positionAt`: 再生中(`playing`)は `positionMs + (nowMs - positionUpdatedAt) * speed` で補間し、`0..durationMs` に丸める。`durationMs <= 0` のときは上限なしで 0 以上に丸めるだけ。停止中は `positionMs` を同じ範囲に丸める
- `pickSession`: 空リストは null。再生中のセッションがあればそれ(複数あれば `lastActivePkg` と一致するものを優先、無ければ先頭)。再生中が無ければ、`lastActivePkg` と一致するもの。どれも無ければ null

## 仕様 2: MediaWatcher.kt(Android 連携。JVM テストなし)

```kotlin
object MediaWatcher {
    val nowPlaying: StateFlow<NowPlaying?>
    fun start(context: Context)
    fun stop()
    fun play(); fun pause(); fun next(); fun previous(); fun seekTo(ms: Long); fun favorite()
}
class MediaListenerService : NotificationListenerService()
```

- `start` は `MediaSessionManager.addOnActiveSessionsChangedListener(listener, ComponentName(context, MediaListenerService::class.java))` で監視を始め、現在のセッション一覧も一度取り込む。各 `MediaController` に callback を付け、メタデータ・再生状態の変化で `SessionSnapshot` を作り直す。`pickSession` で1件選び、`toNowPlaying()` を `nowPlaying` に流す。`lastActivePkg` は直近に再生中だったパッケージを保持する。選ばれるセッションが無ければ `nowPlaying` は null
- `appLabel` は `PackageManager` から取る。取れなければ `pkg`。ジャケットは `METADATA_KEY_ALBUM_ART`、無ければ `METADATA_KEY_ART`、無ければ null
- `play`/`pause`/`next`/`previous`/`seekTo` は、選択中セッションの `transportControls` を呼ぶ。セッションが無いときは何もしない(例外を出さない)
- ☆(お気に入り): `private val FAVORITE_ACTION_IDS: Set<String> = emptySet()` を定義する(**空のまま**。実機で customActions の名前が確認できたら埋める)。`hasFavoriteAction` は「セッションの customActions のうち、`action` が `FAVORITE_ACTION_IDS` に含まれるものがあるか」。`favorite()` は一致する customAction を `transportControls.sendCustomAction` で送る。一致が無ければ何もしない
- `MediaListenerService` は `onListenerConnected` で `MediaWatcher.start(applicationContext)`、`onListenerDisconnected` で `MediaWatcher.stop()`。通知の内容は読まない・保存しない(中身は空のオーバーライドでよい)
- 例外(`SecurityException` 等、通知アクセス未許可)は握りつぶさず、`nowPlaying` を null にして `android.util.Log.w("GT8D", ...)` に出す。アプリを落とさない

`AndroidManifest.xml` に追加する(既存の `<application>` の中):

```xml
<service
    android:name=".media.MediaListenerService"
    android:exported="true"
    android:label="GT8D Launcher"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>
```

## 手順(TDD)

1. `NowPlayingTest.kt` を書く。`artwork` は `null` でテストする。必須のテスト名:
   - `pick_prefersPlaying`: 停止中の Apple Music と再生中の YouTube → YouTube
   - `pick_fallsBackToLastActive`: 全部停止 → `lastActivePkg` のもの
   - `pick_none_returnsNull`: 空リスト → null。再生中が無く `lastActivePkg` も一致しない場合も null
   - `toNowPlaying_unknownDuration_hidesProgress`: `durationMs` が -1 と 0 のとき `NowPlaying.durationMs == null`。`title = null` のとき `appLabel` が `title` になる
   - `positionAt_interpolatesAndClamps`: playing、speed 1、更新から 5000ms、position 10000、duration 12000 → 12000
2. `/home/munde/tools/gradle-run.sh test` を実行し、新しいテストが失敗する(コンパイルエラーを含む)ことを確認する
3. `NowPlaying.kt` を実装し、テストを通す
4. `MediaWatcher.kt` と Manifest の登録を実装する
5. `/home/munde/tools/gradle-run.sh test assembleDebug` で全体が通ることを確認する(最大3回直す。通らなければ、失敗箇所とエラーの先頭30行を報告して止まる)

## 守ること

- テストの期待値を、実装に合わせて弱めない
- 走行状態による分岐を入れない
- 最後に、`test assembleDebug` の結果(実行件数と失敗件数)と、作った・変更したファイル名を報告する。確認していないこと(特に MediaWatcher の実機での動作)を「確認した」と書かない
