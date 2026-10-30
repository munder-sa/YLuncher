# Task 041: 分割の右のブラウザを、パネルの画面の中に表示する

最初に `tasks/RULES.md` を読み、従うこと。仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` §4.7・§5.2(2026-10-30 に書き換え済み)に従う。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

## 背景(司令塔が GT8D で確認した事実)
アプリのプロセスが無い状態で、全画面のアプリからリンクを開くと、E はパネルをいったん全画面で起動し、再試行でもう一度 E を呼ぶ。
その遷移が終わらないうちに `PanelActivity.consumePendingUrl()` が別の Activity(`BrowserActivity`)を開くため、パネルのタスクが分割から外れ、
全画面のブラウザになる。ユーザーの判断で、**分割の右のブラウザは別の Activity にせず、PanelActivity の画面の中に表示する**ことにした。

## 作成・変更・削除するファイル(これで全部)
新規作成:
- `M/link/BrowserPane.kt`
- `T/link/BrowserPaneTest.kt`
変更:
- `M/link/FullscreenBrowserActivity.kt`(今の `BrowserActivity` の中身を移し、`BrowserPane` を使う形にする)
- `M/link/LinkEntryActivity.kt`(`BrowserActivity.EXTRA_URL` の参照先を変えるだけ)
- `M/ui/panel/PanelActivity.kt`
- `app/src/main/AndroidManifest.xml`(`.link.BrowserActivity` の `<activity>` を消すだけ)
- `T/ui/panel/PanelActivityTest.kt`(下の「テスト」に書いた変更だけ)
- `T/ui/NoActionBarTest.kt`(下の「テスト」に書いた変更だけ)
- `T/link/LinkEntryActivityTest.kt`(`BrowserActivity.EXTRA_URL` 等の参照先の書き換えだけ。参照が無ければ変えない)
空にする:
- `M/link/BrowserActivity.kt` は削除せず、中身を `package com.gt8d.launcher.link` の1行だけにする(削除のコマンドは使えないため。司令塔が後で消す)

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.link.BrowserPaneTest --tests com.gt8d.launcher.ui.panel.PanelActivityTest --tests com.gt8d.launcher.ui.NoActionBarTest --tests com.gt8d.launcher.link.LinkEntryActivityTest`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様

### BrowserPane.kt(パッケージ `com.gt8d.launcher.link`)
今の `BrowserActivity` の画面(上のバー「戻る/タイトル/Chromeで開き直す/閉じる」と WebView)を Composable にする。見た目・WebView の設定・
`shouldOverrideUrlLoading`(http/https 以外は Intent で外へ)・タイトルの扱いは今の `BrowserActivity` と同じにする。
```kotlin
const val EXTRA_URL = "url"   // トップレベルに置く(今の BrowserActivity.EXTRA_URL の代わり。値は同じ "url")

@Composable
fun BrowserPane(
    url: String,              // 表示する URL。値が変わったら同じ WebView で loadUrl し直す
    onClose: () -> Unit,      // 「閉じる」、戻りきった「戻る」、「Chromeで開き直す」の後に呼ぶ
    modifier: Modifier = Modifier,
)
```
- WebView は `BrowserPane` が作り、`BrowserPane` が画面から外れるとき(`DisposableEffect` の onDispose)に `destroy()` する。
- 「戻る」ボタンとシステムの「戻る」(`BackHandler`):WebView が戻れれば戻る。戻れなければ `onClose()`。
- 「Chromeで開き直す」:今の URL(`webView.url`、無ければ表示中の URL)を Chrome(`LinkRouter.CHROME_PKG`)の VIEW で `NEW_TASK` 付きで開き、`onClose()`。
- テスト用のタグ: 上のバーのボタンに `browser_back`、`browser_chrome`、`browser_close`、WebView に `browser_web`。

### FullscreenBrowserActivity.kt
- `class FullscreenBrowserActivity : ComponentActivity()`。`onCreate` で `intent` の `EXTRA_URL` を読み、無ければ `finish()`。
  `LauncherTheme { BrowserPane(url, onClose = ::finish, Modifier.fillMaxSize()) }` を表示する。
- `onNewIntent`:URL があれば表示する URL を差し替える(同じ WebView で開き直す)。無ければ `finish()`(今と同じ)。
- マニフェストの `.link.FullscreenBrowserActivity` の宣言は変えない。

### PanelActivity.kt
- 表示中のブラウザの URL を持つ状態 `browserUrl: String?`(Compose の `mutableStateOf`)。テストから読めるよう `internal val shownBrowserUrl: String? get() = browserUrl` を置く。
- `consumePendingUrl()`:今の条件(開いている最中でない・分割の中)はそのまま。URL を取り出したら、**Activity を起動せず** `browserUrl = url` にする
  (既にブラウザを表示中なら、その URL を差し替える)。
- 画面:`browserUrl` が null でなければ、今の Box の中、壁紙の上に、`PanelScreen` の代わりに `BrowserPane(browserUrl, onClose = { browserUrl = null }, Modifier.fillMaxSize())` を表示する。
  `twoFingerSwipe` の付いた外側の Box は今のまま。
- 「戻る」:ブラウザ表示中は `BrowserPane` の `BackHandler` が受ける。ブラウザが閉じている時は今のまま(何もしない)。
- 画面の再生成に備え、`onSaveInstanceState` で `browserUrl` を保存し、`onCreate` で戻す(キー名は自由。WebView の履歴は保たなくてよい)。
- そのほか(転送、分割の状態、暖かい記録、前面判定のフィールド)は変えない。

### LinkEntryActivity.kt
- `BrowserActivity.EXTRA_URL` を、トップレベルの `EXTRA_URL` に置き換えるだけ。振り分けの処理は変えない。

## テスト
1. `T/link/BrowserPaneTest.kt`(Robolectric、sdk 35、Compose のテスト規則。既存の `PanelScreenTest` などの書き方に合わせる):
   - `close_callsOnClose`:`browser_close` を押す → `onClose` が1回呼ばれる。
   - `back_withoutHistory_callsOnClose`:`browser_back` を押す → `onClose` が1回呼ばれる。
   - `systemBack_withoutHistory_callsOnClose`:`OnBackPressedDispatcher` の戻る → `onClose` が1回呼ばれる。
   - `chrome_opensChromeAndCloses`:`browser_chrome` を押す → 次に起動された Intent が VIEW・パッケージ `com.android.chrome`・データが表示中の URL。`onClose` が1回呼ばれる。
   - `dispose_destroysWebView`:`BrowserPane` を表示した後に画面から外す → 例外なく終わる(WebView の destroy が呼ばれたことを Robolectric の `ShadowWebView` で確かめられるなら確かめる。確かめられなければ「確かめられなかった」と報告に書く)。
2. `T/ui/panel/PanelActivityTest.kt`:
   - `assertBrowserOnce()` を、仕様の変更に合わせて次の内容に書き換える(関数名は変えない): **Activity が起動されない**(`nextStartedActivity` が null)、
     `activity.shownBrowserUrl == "https://example.com"`、`AppGraph.pendingUrl.take()` が null。
     `pendingUrl_whileOpening_isNotConsumedUntilOpeningEnds` と `pendingUrl_notOpening_consumedOnResume` の本体は変えない。
   - テストを足す:
     - `browser_close_returnsToPanel`:分割の中で URL を表示 → `browser_close` を押す → `shownBrowserUrl == null`、パネルの部品(例: `dock_applist`)が見える、`isFinishing == false`。
     - `browser_systemBack_returnsToPanel_thenBackIgnored`:分割の中で URL を表示 → 戻る → `shownBrowserUrl == null`。もう一度戻る → `isFinishing == false`。
     - `pendingUrl_whileBrowserShown_replacesUrl`:URL A を表示中に、`pendingUrl` に B を入れて `consumePendingUrl` が走る経路(例: もう一度 `onResume`)→ `shownBrowserUrl == B`、Activity は起動されない。
     - `browserUrl_survivesRecreate`:URL を表示中に `controller.recreate()` → 新しい Activity の `shownBrowserUrl` が同じ URL。
   - `import com.gt8d.launcher.link.BrowserActivity` は消す。
3. `T/ui/NoActionBarTest.kt`:`browserActivity_hasNoActionBar` を `fullscreenBrowserActivity_hasNoActionBar` に改名し、対象を `FullscreenBrowserActivity`、Intent の extra を `EXTRA_URL` にする(BrowserActivity を消すため)。

## 手順(TDD)
1. 上のテストを書く。
2. 対象テストを実行し、修正前に失敗することを確かめる(コンパイルエラーでの失敗でよい。どのテストがどう失敗したかを報告に書く)。
3. 実装する。
4. 対象テストが全部通るまで直す(最大3回)。通ったら `assembleDebug`。

## この Task でしないこと
- LinkRouter・LinkEntryActivity の振り分け、SetRunner、PanelForwarder の変更。ブラウザの見た目の変更。
- 全テストの実行、実機の操作、git のコミット等。

## 報告
実行したコマンドと結果(件数・失敗件数、修正前の失敗の内容、assembleDebug)、作成・変更したファイル、**決めたこと**。
