# Task 059: リンクを外部のブラウザで「左のアプリ | ブラウザ」の分割に開く(内蔵ブラウザをやめる)

最初に `tasks/RULES.md` を読み、従うこと。仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` **§4.7(2026-10-31 作り直し)**・§6-3 に従う。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

前提: Task 058(ホームの作り直し)が取り込み済み。まず `M/link/`・`M/sets/SetRunner.kt`・`M/ui/panel/PanelActivity.kt`・`M/settings/LinkScreen.kt` と、そのテストを読む。

## 背景(司令塔が実機で確かめた事実・ユーザーの判断)
- E で「ChatGPT | com.brave.browser」の分割を作り、その後に `VIEW`(`-p com.brave.browser`)を送ると、Brave が温まっていても冷えていても、**分割の右の Brave に新しいタブで開いた**(全画面に飛び出さない)。
- ユーザーの判断: 内蔵ブラウザ(WebView)をやめ、リンクは外部のブラウザで開く。開くブラウザは設定で選び、既定は Chrome。

## 作成・変更・空にするファイル(これで全部)
新規:
- `M/link/BrowserChooser.kt`、`T/link/BrowserChooserTest.kt`
変更:
- `M/config/Model.kt`(`LinkConfig` に `browserPkg` を足すだけ)
- `M/link/LinkRouter.kt`、`M/link/LinkEntryActivity.kt`、`M/sets/SetRunner.kt`(リンク用の開き方だけ)
- `M/ui/panel/PanelActivity.kt`(内蔵ブラウザの表示・保留の URL の取り出しを消すだけ)
- `M/AppGraph.kt`(`pendingUrl` を消すだけ)
- `M/settings/LinkScreen.kt`
- `app/src/main/AndroidManifest.xml`(`FullscreenBrowserActivity` の宣言を消す。`<queries>` に http の VIEW を扱うアプリを見るための宣言が既にあるか確かめ、無ければ `<intent><action VIEW/><data scheme="https"/></intent>` を足す)
- テスト: `T/link/LinkRouterTest.kt`、`T/link/LinkEntryActivityTest.kt`、`T/sets/SetRunnerTest.kt`、`T/ui/panel/PanelActivityTest.kt`、`T/settings/DailyScreensTest.kt`、`T/ui/NoActionBarTest.kt`、`T/ui/TaskAffinityTest.kt`、`T/ui/layout/OtherScreensLayoutTest.kt`、`T/config/ConfigCodecTest.kt`、`T/config/DefaultsTest.kt`(下の「既存テストの扱い」で許した変更だけ)
空にする(削除のコマンドは使えないため、中身を `package com.gt8d.launcher.link` の1行だけにする。司令塔が後で消す):
- `M/link/BrowserPane.kt`、`M/link/FullscreenBrowserActivity.kt`、`M/link/SiteLabel.kt`、`M/link/PendingUrl.kt`(あれば)
- `T/link/BrowserPaneTest.kt`、`T/link/SiteLabelTest.kt`、`T/link/PendingUrlTest.kt`(あれば。テストのファイルは中身を `package com.gt8d.launcher.link` の1行にする)

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.link.* --tests com.gt8d.launcher.sets.* --tests com.gt8d.launcher.settings.* --tests com.gt8d.launcher.config.* --tests com.gt8d.launcher.ui.*`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様
1. **設定**(Model.kt): `LinkConfig` に `val browserPkg: String = "com.android.chrome"` を足す(既存の保存データは既定値で読める。`version` は上げない)。`LinkMode.InAppSplit` は「ブラウザで分割」、`LinkMode.Chrome` は「全画面で開く」の意味に変える(**名前は保存データとの互換のため変えない**。コメントで意味を書く)。`chromeDomains` は残すが使わない(コメント)。
2. **BrowserChooser.kt**(`object BrowserChooser`):
   - `fun installedBrowsers(pm: PackageManager): List<String>`: `Intent(ACTION_VIEW, Uri.parse("https://example.com"))` を扱えるアプリのパッケージ(`queryIntentActivities`。`MATCH_ALL`)から、`OwnPackages.isOwn` を除き、重複を除いて並べる。
   - `fun choose(preferred: String, installed: List<String>): String?`(純粋): `preferred` が入っていればそれ、無ければ `com.android.chrome` → `com.brave.browser` → `installed` の先頭、の順。無ければ null。
3. **LinkRouter**: ルートを次にする(`LinkRoute` を作り直す):
   ```kotlin
   sealed interface LinkRoute {
       data object FullscreenBrowser : LinkRoute                // ブラウザを全画面で
       data class SplitWithBrowser(val left: String) : LinkRoute // E で「left | ブラウザ」→ VIEW
   }
   ```
   - `cfg.mode == LinkMode.Chrome`(全画面で開く)→ `FullscreenBrowser`。
   - `chromeDomains` の特別扱いはやめる(外部ブラウザならログインできるため)。
   - 前面が Home・Unknown → `FullscreenBrowser`。Fullscreen(X) → `SplitWithBrowser(X)`。AppApp(left, _) → `SplitWithBrowser(left)`。**AppPanel → `SplitWithBrowser(left)`**。左は `lastPair.first`(最後に開いたセットの左)を使う。`FrontState.AppPanel` に左を持たせる(`data class AppPanel(val left: String?)`)。左が分からなければ `FullscreenBrowser`。
   - E が使えない(`vendorUsable == false`)→ `FullscreenBrowser`。
   - 前面の判定(`detectFront`)の規則は変えない。
4. **LinkEntryActivity**: 振り分けの後:
   - ブラウザ = `BrowserChooser.choose(cfg.browserPkg, installedBrowsers(pm))`。null なら DiagLog `"no browser"` で何もしない。
   - `FullscreenBrowser`: `Intent(ACTION_VIEW, url).setPackage(ブラウザ).addFlags(NEW_TASK)` を起動。
   - `SplitWithBrowser(left)`: `AppGraph.runner.openForLink(left, ブラウザ, onReady = { ブラウザに VIEW(NEW_TASK)を送る }, onNotSplit = { 全画面で開く })`。`false` が返ったら全画面で開く。
   - 今の保留の URL(`pendingUrl`)・パネルへの受け渡しは消す。
5. **SetRunner.openForLink**: 形を `fun openForLink(left: String, right: String, onReady: () -> Unit, onNotSplit: () -> Unit): Boolean` にする。
   - `ScreenSet("link", Slot.App(left), Slot.App(right), SplitRatio.R5_5)` を開く(右はブラウザのアプリとして扱う。幕の配置図も右はそのアプリ)。
   - **幕が外れたとき**、理由が `Ready`(両方の前面化を見た)または `Timed`(使用状況のアクセスが無く時間で外した)なら `onReady` を**1回だけ**呼ぶ。`Failed`・`Safety`(4 秒で外れた)・取り消し(別の操作で上書き)なら呼ばず、`Failed`・`Safety` は `onNotSplit` を1回呼ぶ(今の `linkFallback` と同じ扱い)。上書きされたら、どちらも呼ばない。
   - 右がブラウザの「アプリ | アプリ」として、前面化の待ち・呼び直しの規則(046)は今のまま使う。
6. **PanelActivity**: 内蔵ブラウザの表示(`browserUrl`・`BrowserPane`)と `consumePendingUrl`・関連の保存を消す。設定の表示など、ほかは変えない。
7. **設定(LinkScreen)**: 「リンクの行き先」に、
   - 開き方: 「ブラウザで分割」(`link_mode_split`、既定)/「全画面で開く」(`link_mode_full`)。今のタグ `link_mode_inapp`・`link_mode_chrome` から新しいタグに変える。
   - 開くブラウザ: `BrowserChooser.installedBrowsers` の一覧から選ぶ(アイコン+名前の行。選択中に印。タグ `link_browser_<pkg>`)。今の `browserPkg` が入っていなければ「(入っていません)」を添えて、実際に使われるブラウザ(`choose` の結果)を表示する。
   - 「Chrome に回すドメイン」の欄は出さない。
   - 052 の見た目の部品(`SettingsChoice`・`SettingsRow` など)を使う。
8. **マニフェスト**: `FullscreenBrowserActivity` の宣言を消す。`LinkEntryActivity`(VIEW の受け口)はそのまま。

## 既存テストの扱い(司令塔が許可する変更。これ以外は変えない。変えた行は報告に全部並べる)
- 内蔵ブラウザ・`FullscreenBrowserActivity`・`pendingUrl`・`SiteLabel`・`BrowserPane`・`chromeDomains` の振り分けを確かめるテストは、仕様が無くなったため、**消すか、新しい振り分け(外部ブラウザの全画面/分割)を確かめる形に置き換える**。消したテストは名前を全部並べ、置き換えたテストは旧名→新名を並べる。
  - 例: `LinkEntryActivityTest` の「次に起動された Activity が FullscreenBrowserActivity」→「次に起動された Intent が VIEW で、パッケージが選んだブラウザ」。前面が「アプリ | パネル」で `pendingUrl` を確かめるテスト→「`openForLink(left, ブラウザ, …)` が呼ばれる」。
  - `PanelActivityTest` の `browser_*`・`pendingUrl_*`・`browserUrl_survivesRecreate`・`settings_browserOpenedThenClosed_returnsToSettingsRoot`・`safetyMode_doesNotConsumePendingUrl` は消す(パネルの中にブラウザは出なくなった)。
  - `OtherScreensLayoutTest` のブラウザの上のバーの確認は消す。`NoActionBarTest` の `fullscreenBrowserActivity_hasNoActionBar` と、`TaskAffinityTest` の `fullscreenBrowser_keepsOwnAffinity` は消す。
  - `DailyScreensTest` の `link_*` は新しいタグ・新しい項目(ドメインの欄が無い、ブラウザの選択)に合わせて書き換える。
  - `SetRunnerTest` の `openForLink` を使うテストは、新しい形(右がブラウザ、`onReady`)に合わせる。期待する中身(失敗時に1回だけ全画面になる等)は変えない。
  - `ConfigCodecTest`・`DefaultsTest` は `browserPkg` の既定値の確認を足すだけ。

## テスト(先に書き、修正前に失敗することを確かめる)
- `BrowserChooserTest`: `choose_preferredInstalled`、`choose_fallbackChromeThenBrave`、`choose_none_null`、`installedBrowsers_excludesOwn`(Robolectric で http の VIEW を扱うアプリを登録して確かめる)。
- `LinkRouterTest`: `home_fullscreen`、`fullscreenApp_splitWithSender`、`appPanel_splitWithLastLeft`、`appPanel_unknownLeft_fullscreen`、`appApp_splitWithLeft`、`modeFull_alwaysFullscreen`、`vendorUnusable_fullscreen`、`googleLogin_noLongerSpecial`(accounts.google.com も分割)。
- `SetRunnerTest`: `link_ready_callsOnReadyOnce`、`link_timed_callsOnReadyOnce`(使用状況なし)、`link_safety_callsNotSplitOnce`、`link_overridden_callsNeither`、`link_rightIsBrowserInVendorCall`。
- `LinkEntryActivityTest`: `fullscreen_sendsViewToChosenBrowser`、`split_callsOpenForLinkWithBrowser`、`noBrowser_doesNothing`。
- `DailyScreensTest`: `link_chooseBrowser_saves`、`link_missingBrowser_showsActual`、`link_noDomainSection`。

## 手順(TDD)
1. テストを書く。2. 対象テストを実行し、修正前に失敗することを確かめる(どのテストがどう失敗したかを報告に書く)。
3. 実装する。4. 対象テストが全部通るまで直す(最大3回)。通ったら `assembleDebug`。
- Robolectric で Activity の中の Compose の状態を書き換えて確かめるテストでは、確かめる前に `Snapshot.sendApplyNotifications()` を呼ぶ。

## この Task でしないこと
- ホーム・パネルの配置、前面の判定の規則、中継アプリ、帯の変更。全テストの実行、実機の操作、git のコミット等。

## 報告
実行したコマンドと結果(件数・失敗件数、修正前の失敗の内容、assembleDebug)、作成・変更・空にしたファイル、消した・置き換えたテスト(旧名→新名)、**決めたこと**。
