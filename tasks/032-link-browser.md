# Task 17b: リンクの受け口と内蔵ブラウザ(Activity・Manifest・分割の呼び出し)

**最初に `tasks/RULES.md` を読み、従うこと。** 計画 `docs/superpowers/plans/2026-10-28-gt8d-launcher.md` の **Task 17**、仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §4.7・§5.2(BrowserActivity)に従う。振り分けの規則は Task 17a で作った `com.gt8d.launcher.link.LinkRouter`(`FrontClues`・`FrontState`・`LinkRoute`)をそのまま使い、変えない。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

## 作成・変更するファイル(これで全部。これ以外のファイルは作らない・変えない)

新規作成:
- `M/link/PendingUrl.kt`、`M/link/BrowserActivity.kt`、`M/link/FullscreenBrowserActivity.kt`、`M/link/LinkEntryActivity.kt`
- `T/link/PendingUrlTest.kt`

変更:
- `M/sets/SetRunner.kt`、`T/sets/SetRunnerTest.kt`(テストを足す。既存のテストは変えない)
- `M/foreground/ForegroundWatcher.kt`、`T/foreground/ForegroundWatcherTest.kt`(メソッドとテストを足す。既存のものは変えない)
- `M/AppGraph.kt`、`M/ui/home/HomeActivity.kt`、`M/ui/panel/PanelActivity.kt`、`app/src/main/AndroidManifest.xml`

使ってよいコマンド(これだけ。**全テスト `test` は実行しない**。全テストはこちらで流す):
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.link.PendingUrlTest --tests com.gt8d.launcher.sets.SetRunnerTest --tests com.gt8d.launcher.foreground.ForegroundWatcherTest`
- `/home/munde/tools/gradle-run.sh assembleDebug`
- Gradle はバックグラウンドに回さず、終わるまで待つ形で実行する

## 仕様

### PendingUrl.kt(パッケージ `com.gt8d.launcher.link`。純粋な Kotlin)
```kotlin
class PendingUrl(private val clock: Clock) {   // com.gt8d.launcher.time.Clock
    companion object { const val VALID_MS = 15_000L }
    fun set(url: String)
    fun take(): String?    // 預けてから VALID_MS 以内(経過 ≤ 15000ms)ならその URL を返して消す。過ぎていたら消して null。無ければ null
}
```
- スレッドから同時に呼ばれてもよいように `@Synchronized` にする
- `set` をもう一度呼ぶと、URL も預けた時刻も新しいものに置き換わる

### SetRunner.kt の変更
- `var lastOpenedAtMs: Long? = null`(`private set`)を足す。`lastOpenedPair` に値を入れる所で同時に開始時刻(`startMs`)を入れ、`lastOpenedPair = null` にする所では同時に null にする
- `fun openForLink(left: String, onNotSplit: () -> Unit): Boolean` を足す:
  - 開いている最中(`opening` が null でない)なら何もせず false(`onNotSplit` は呼ばない)
  - それ以外は、その場のセット `ScreenSet(id = "link", left = Slot.App(left), right = Slot.Panel, ratio = SplitRatio.R5_5)` を、**幕つき**で既存の `openInternal` と同じ手順で開き、true を返す
  - ただし **`currentIndex` と `lastIndex.lastSetIndex` は変えない**(設定にあるセットではないため)
  - 幕の行き先は `CurtainTarget(setName = "リンクを開く", left = SlotPreview.App(left, labelFor(left)), right = SlotPreview.Panel, ratio = SplitRatio.R5_5, position = currentIndex, count = セット数 + 1)`
  - **分割にならなかったとき、`onNotSplit` を1回だけ呼ぶ**(呼び出し側が、預けた URL を全画面ブラウザで開くため)。分割にならなかったときとは、`openSet` の中の次の経路: 左が未インストール、左右のどちらかが分割できない、分割の方式が無い(`launcher == null`)、`LaunchResult.Failed`。加えて、幕が `HideReason.Safety` で消え、そのとき `waitKind == WaitKind.Both` だった(=分割の待ちがタイムアウトした)とき。分割が成立した(`waitBoth` の ready)とき・それ以外の理由で幕が消えたときは呼ばずに捨てる。保持は `private var linkFallback: (() -> Unit)?` とし、呼ぶ前に null にする(2回呼ばない)。`LaunchResult.Failed` の経路では、`d.curtain.fail()` より**前に**取り出して null にしてから呼ぶ(`fail()` は同期的に `onHidden` を呼ぶため)。`onCurtainHidden` では、`waitKind` を None に戻す前の値で判定する。`open`・`openForBoot` から開いたときは `linkFallback` は null のまま
  - 実装の形: `openInternal` の「セットが決まったあと」の部分を `private fun openSet(set: ScreenSet, target: CurtainTarget, useCurtain: Boolean)` に切り出し、`openInternal`(`currentIndex`・`lastSetIndex` を更新してから `openSet`)と `openForLink`(更新せずに `openSet`)から呼ぶ。既存の動きは変えない(既存の SetRunnerTest がすべて通ること)

### ForegroundWatcher.kt の変更
- `fun foregroundPkgsSince(sinceMs: Long): Set<String>` を足す: `events.available()` が false なら空集合。そうでなければ `events.resumedSince(sinceMs)` のうち `timeMs >= sinceMs` のもののパッケージの集合

### AppGraph.kt の変更
- `@Volatile var homeResumed: Boolean = false`、`@Volatile var panelInSplit: Boolean = false` を足す
- `val pendingUrl: PendingUrl by lazy { PendingUrl(ElapsedClock) }` を足す

### HomeActivity.kt の変更
- `onResume` の最初(退避中の判定より前)で `AppGraph.homeResumed = true`、`onPause` を足して `AppGraph.homeResumed = false`

### PanelActivity.kt の変更
- `onResume` で `AppGraph.panelInSplit = isInMultiWindowMode`、`onPause` で `AppGraph.panelInSplit = false`、`onMultiWindowModeChanged` でも `AppGraph.panelInSplit = isInMultiWindowMode`
- `private fun consumePendingUrl()` を足し、**`onResume` の `AppGraph.runner.panelResumedInSplit()` の直後**と、**`onMultiWindowModeChanged` で分割に入ったとき(`panelResumedInSplit()` の直後)**の両方から呼ぶ(分割への移行で `onResume` が来ない順序でも受け取るため)。中身: 分割の中(`isInMultiWindowMode`)なら `AppGraph.pendingUrl.take()` を見て、URL があれば `BrowserActivity` を開く(`take()` が消すので、2か所から呼ばれても1回しか開かない): `Intent(this, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, url).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)`(**`NEW_TASK` は付けない**。パネルのタスクの上に重ねるため)
- `onNewIntent(intent)` を上書きし、`setIntent(intent)` する(このあと `onResume` が来るので、URL の受け取りは `onResume` に任せる)

### BrowserActivity.kt(パッケージ `com.gt8d.launcher.link`)
```kotlin
open class BrowserActivity : ComponentActivity() {
    companion object { const val EXTRA_URL = "url" }
}
```
- 画面(Compose): 上に横一列のバー(高さ 72dp 以上、背景 `Palette.Panel`)。左から「戻る」、ページのタイトル(無ければ URL。1行、はみ出しは省略)、「Chromeで開き直す」、「閉じる」。ボタンは押せる領域 72dp 以上。その下に `AndroidView` で `WebView`
- WebView: `javaScriptEnabled = true`、`domStorageEnabled = true`。`WebViewClient.shouldOverrideUrlLoading`: `http`・`https` は WebView の中で開く(false を返す)。それ以外のスキームは `runCatching { startActivity(Intent.parseUri(url, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }` して true を返す
- 「戻る」とシステムの戻る(`BackHandler`): WebView が戻れるなら `goBack()`、戻れなければ `finish()`
- 「閉じる」: `finish()`
- 「Chromeで開き直す」: 今のページの URL を `Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(LinkRouter.CHROME_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)` で開き(`runCatching`)、`finish()`
- `onCreate` で `intent.getStringExtra(EXTRA_URL)` を読み込む。`onNewIntent` で新しい URL が来たら、その URL を読み込む。URL が無ければ `finish()`
- WebView は Activity の `onDestroy` で `destroy()` する

### FullscreenBrowserActivity.kt
- `class FullscreenBrowserActivity : BrowserActivity()`(中身は空でよい。Manifest で別のタスクにする)

### LinkEntryActivity.kt(リンクの受け口。画面を出さない中継)
`onCreate` で次をして、最後に必ず `finish()` する(`try { … } catch { … } finally { finish() }` の形)。例外は受けて `DiagLog` に記録し、`AppGraph.pendingUrl.take()` で預けた URL を取り消してから(預けていなくてもよい)、全画面ブラウザで開く。全画面ブラウザを開くこと自体も `runCatching` で包む(失敗したら記録だけ):
1. `val url = intent?.dataString`。null なら `finish()`
2. `val ref = LinkRouter.referrerPackage(referrer?.toString())`(`Activity.getReferrer()`)
3. 前面の手がかりを集める:
   - `homeResumed = AppGraph.homeResumed`、`panelInSplit = AppGraph.panelInSplit`
   - `lastPair = AppGraph.runner.lastOpenedPair`
   - `otherForegroundAfterLastPair`: `lastPair` と `AppGraph.runner.lastOpenedAtMs` があり、`AppGraph.watcher.foregroundPkgsSince(その時刻)` の中に、左右のどちらでもなく、自分のパッケージでもないものが1つでもあるとき true。それ以外は false(仕様 §4.7 の「その後に別のアプリの前面化が無く」。最新の1件だけでは途中の別アプリを見落とすため、期間中のすべてを見る。自分のパッケージを除くのは、この受け口自身の前面化が必ず入るため)
4. `front = LinkRouter.detectFront(clues, packageName)`、`route = LinkRouter.route(url, AppGraph.latestConfig.value?.link ?: LinkConfig(), front, AppGraph.selector.current() == SplitMode.Vendor)`。`DiagLog.d("Link", "front=$front route=$route ref=$ref")`
5. 行き先ごとに:
   - `Chrome`: `Intent(ACTION_VIEW, Uri.parse(url)).setPackage(LinkRouter.CHROME_PKG)` に `NEW_TASK`。開けなければ(例外)全画面ブラウザ
   - `FullscreenBrowser`: `Intent(this, FullscreenBrowserActivity::class.java).putExtra(EXTRA_URL, url)` に `NEW_TASK`
   - `PanelTaskBrowser`: `AppGraph.pendingUrl.set(url)` のあと `Intent(this, PanelActivity::class.java)` に `NEW_TASK | CLEAR_TOP | SINGLE_TOP`
   - `VendorSplitThenBrowser(left)`: `AppGraph.pendingUrl.set(url)` のあと `AppGraph.runner.openForLink(left, onNotSplit)`。false(開いている最中)なら、預けた URL を `take()` で取り消して全画面ブラウザ。`onNotSplit` は `openForLink` の中で同期的に呼ばれることも、この Activity が終わった後に呼ばれることもあるので、どちらでも動くように `applicationContext` を使う: `AppGraph.pendingUrl.take()` が null でなければ、その URL で全画面ブラウザ(`NEW_TASK`)を開く(`runCatching`)。null なら(パネルが受け取り済み・期限切れ)何もしない
   - 全画面ブラウザの開き方はどこでも同じ: `Intent(context, FullscreenBrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)`。1つの private 関数にまとめてよい

### AndroidManifest.xml の変更
- `<uses-permission android:name="android.permission.INTERNET" />` を足す
- `LinkEntryActivity`: `exported="true"`、`theme="@android:style/Theme.Translucent.NoTitleBar"`、`taskAffinity="com.gt8d.launcher.link"`、`excludeFromRecents="true"`、`noHistory="true"`。intent-filter: action `VIEW`、category `DEFAULT` と `BROWSABLE`、data `scheme="http"` と `scheme="https"`(ホストは指定しない。既定のブラウザに選べるようにするため)
- `BrowserActivity`: `exported="false"`、`resizeableActivity="true"`(`taskAffinity` は指定しない=パネルと同じ)
- `FullscreenBrowserActivity`: `exported="false"`、`resizeableActivity="true"`、`taskAffinity="com.gt8d.launcher.browser"`、`launchMode="singleTask"`
- 既存の項目は変えない

## 手順(TDD)

1. テストを書く:
   - `T/link/PendingUrlTest.kt`(純粋な JUnit。時刻を変えられる `Clock` を使う)
     - `pending_takeWithinValid_returnsAndClears`: t=0 で set → t=14999 で take が URL → もう一度 take で null
     - `pending_expired_returnsNull`: t=0 で set → t=15001 で take が null
     - `pending_empty_returnsNull`: 何も預けずに take で null
     - `pending_setTwice_keepsLatest`: "a" を set → "b" を set → take で "b"
     - `pending_exactlyValid_returns`: t=0 で set → t=15000 で take が URL
     - `pending_resetRefreshesExpiry`: t=0 で "a" を set → t=10000 で "b" を set → t=20000 で take が "b"
   - `T/sets/SetRunnerTest.kt` に足す(既存のテストの準備の書き方に合わせる):
     - 準備の共通事項: `org.videolan.vlc` を `TestEnv` のインストール済みに加える(テストの中で。偽物のクラスは変えない)。右がパネルの分割の成立には、左の `ResumeEvent` に加え `runner.panelResumedInSplit()` を呼び、時計を進めてポーリングを回す(既存の成功のテストと同じ流れ)。`onNotSplit` は呼ばれた回数を数えるラムダにする
     - `openForLink_opensVendorWithPanelRight_keepsLastIndex`: 開く前の `currentIndex`・`lastSetIndex` を覚える → `openForLink("org.videolan.vlc") {}` が true → E(vendor の偽物)が `("org.videolan.vlc", 自分のパッケージ, SplitRatio.R5_5)` で呼ばれ、幕が出て、`currentIndex`・`lastSetIndex` は変わらない、`lastOpenedPair` は `"org.videolan.vlc" to 自分のパッケージ`、`lastOpenedAtMs` が開いた時刻(偽の時計の値)と等しい
     - `openForLink_whileOpening_ignored`: 先に `open(1)` で開いている最中にする → `openForLink("org.videolan.vlc") { 呼ばれた回数を数える }` が false、E は1回しか呼ばれない、`onNotSplit` は呼ばれない
     - `openForLink_launchFailed_callsNotSplitOnce`: E(偽物)が `LaunchResult.Failed` を返す → `onNotSplit` がちょうど1回呼ばれる
     - `openForLink_timeout_callsNotSplitOnce`: E が Started を返すが両方そろわないまま、既存のタイムアウトのテストと同じ手順で幕が `HideReason.Safety` で消える → `onNotSplit` がちょうど1回呼ばれる
     - `openForLink_ready_doesNotCallNotSplit`: 分割が成立する(既存の成功のテストと同じ準備)→ 幕が消えた後も `onNotSplit` は呼ばれない
     - `open_afterLinkSuccess_noStaleFallback`: `openForLink` が成功した後に、通常の `open(1)` で E を `Failed` にする → 前の `onNotSplit` は呼ばれない
     - `lastOpenedAtMs_clearedByHome`: `open(1)` の後、`lastOpenedAtMs` が開いた時刻と等しい → 分割を成立させて幕が消える(開いている最中を終わらせる)→ `home()` で null(開いている最中の `home()` は予約になり、記録を消さないため)
   - `T/foreground/ForegroundWatcherTest.kt` に足す(既存のテストの書き方に合わせる):
     - `foregroundPkgsSince_collectsAllAfterSince`: since より前の "x"、後の "a"・"c"・"a" → `setOf("a", "c")`
     - `foregroundPkgsSince_unavailable_empty`: 使えないとき空集合
2. `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.link.PendingUrlTest --tests com.gt8d.launcher.sets.SetRunnerTest --tests com.gt8d.launcher.foreground.ForegroundWatcherTest` を実行し、失敗(またはコンパイルできない)を確認する
3. 実装する
4. 同じテストが全部通るまで直す(最大3回)。通ったら `/home/munde/tools/gradle-run.sh assembleDebug` が通ることを確認する。通らなければ、失敗したテスト名・エラーの先頭30行を報告して止まる

## この Task で自動テストしないもの(司令塔が実機で確認する)
- Activity どうしの連携(LinkEntryActivity の振り分け、PanelActivity の受け取り、BrowserActivity の戻る・閉じる・Chrome で開き直す)。実装者は、仕様どおりに書けていることを報告に書くだけでよい

## 報告

実行したコマンドと結果(テストの実行件数・失敗件数、assembleDebug の結果)、作成・変更したファイル名、**決めたこと**。
