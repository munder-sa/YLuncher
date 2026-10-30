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
- `BrowserChooserTest`: `choose_preferredInstalled`、`choose_fallbackChromeThenBrave`、`choose_none_null`、`installedBrowsers_excludesOwn_sortedByLabel`(Robolectric で https の VIEW を扱うアプリを登録して確かめる)。
- `LinkRouterTest`: `home_fullscreen`、`fullscreenApp_splitWithSender`、`appPanel_splitWithLastLeft`、`appPanel_unknownLeft_fullscreen`、`appApp_splitWithLeft`、`modeFull_alwaysFullscreen`、`vendorUnusable_fullscreen`、`googleLogin_noLongerSpecial`(accounts.google.com も分割)、`leftIsBrowser_fullscreen`。
- `SetRunnerTest`: `link_ready_callsOnReadyOnce`、`link_timed_callsOnReadyOnce`(使用状況なし)、`link_safety_callsNotSplitOnce`、`link_overridden_callsNeither`(リンクを開いている最中に `next()` → 幕が外れても onReady・onNotSplit とも呼ばれない)、`link_secondLink_cancelsFirstCallbacks`、`link_singleLaunchFallback_callsNotSplitOnly`(左が未インストール)、`link_monitoringUnavailableFromStart_timed_callsOnReady`、`link_rightIsBrowserInVendorCall`。
- `LinkEntryActivityTest`: `fullscreen_sendsViewToChosenBrowser`、`split_callsOpenForLinkWithBrowser`、`noBrowser_doesNothing`。
- `DailyScreensTest`: `link_chooseBrowser_saves`、`link_missingBrowser_showsActual`、`link_noDomainSection`。


## 点検を受けて決めたこと(上の仕様より優先する)
1. **onReady は「分割を待つ流れ(waitBoth)」が Ready または Timed で終わったときだけ**、1回呼ぶ。左右の未インストール・分割方式なし・リサイズ不可などで**片側だけの起動に退避した場合は、退避した時点で onNotSplit を1回だけ呼び**、その後の幕の解除では何も呼ばない(今の `linkFallback` と同じ時点)。
2. 使用状況のアクセスが**最初から無い**(開始時に監視できない)なら、時間で幕を外したとき(Timed)に onReady を呼ぶ(できるだけ分割に開く)。**途中で監視できなくなった**(権限を失った・問い合わせの失敗)場合は、今どおり onNotSplit(既存のテスト `linkQueryFailureDuringWait_callsFallbackOnce` の期待のまま)。
3. **上書き**: リンクの開き方の途中に、別の操作(`open`・`next`・`prev`・`home`・`openForBoot`・新しい `openForLink`)を**受け付けた時点で**、それまでのリンクのコールバックを無効にする(onReady も onNotSplit も呼ばない)。今と同じ番号を再び求められた場合も無効にする。
4. **リンクが続けて来た**: 開いている最中に新しいリンクが来たら、`openForLink` は今どおり false を返し、新しいリンクは全画面で開く。このとき、**先のリンクのコールバックも無効にする**(古い URL が後から開かれないように)。
5. 「全画面で開く」は、ブラウザに通常の `VIEW + NEW_TASK` を送ることを指す(分割を解くことまでは求めない)。
6. 左のアプリが選んだブラウザそのもの(left == ブラウザ)なら、分割にせず `FullscreenBrowser`(VIEW を送るだけ)にする。
7. `openForLink` は、右(ブラウザ)の「インストールの確認」を `installed()` で行わない(ブラウザは `BrowserChooser` で PackageManager から選んでいるため)。左の確認は今のまま。
8. 遅れて呼ばれる onReady・onNotSplit の中の VIEW の送信は、共通の安全な関数(`runCatching` で包み、失敗は DiagLog)で行う。Activity が閉じた後に呼ばれるので、`applicationContext` を使う。
9. `installedBrowsers` は `https://example.com` で探し、マニフェストの `<queries>` に https の VIEW(既にある http に加えて)を足す。テストの登録も https(と http)にする。並び順はアプリ名の順。候補が0なら設定に「ブラウザが入っていません」と出す。
10. 設定の保存値(`browserPkg`)は、入っていなくても書き換えない。選択の印は保存値に付け、実際に使われるブラウザが違うときは別の行で「実際に使うブラウザ: …」と出す。
11. `LinkConfig` の `browserPkg` は**最後の引数**として足す(位置引数の既存の呼び出しを壊さない)。`ConfigCodecTest` に、Brave など既定以外の値を保存して読み戻すテストを足してよい。
12. 既存テストの変更として、上の「既存テストの扱い」に加え、**型の変更(`AppPanel` のデータクラス化、`LinkRoute` の作り直し、`openForLink` の形)に合わせた書き換え、不要になった import、共通の準備・後始末(例: `PanelActivityTest` の cleanup の `pendingUrl`)の修正**を許可する。通常のセットの Ready の期待や、冷えた起動で設定を待つ・破棄する確認は変えない。
13. テストの作り方: `LinkEntryActivityTest` は、`SetRunnerDeps` に偽の vendor・scheduler・curtain を差し込み、`openForLink` に渡った左右と、幕が外れた後(Activity が閉じた後)に VIEW が1回だけ送られること、外れる前は送られないこと、Failed・Safety では全画面の VIEW が1回だけ送られることを確かめる。実機での分割の成立・新しいタブ・全画面かどうかは、司令塔が実機の場面テストで確かめる(テストでは確かめなくてよい)。
14. このタスクは Task 058 を取り込んだ後の状態から始める。058 で入った `currentIndexFlow` とそのテスト、`DailyScreensTest` のパネルの区分へ移した確認を消さない。リンク用の一時のセットでは `currentIndex` を変えない今の期待も保つ。新しい型・関数を参照するテストのコンパイルの失敗は、「修正前に失敗した」ことに数えてよい。

## 手順(TDD)
1. テストを書く。2. 対象テストを実行し、修正前に失敗することを確かめる(どのテストがどう失敗したかを報告に書く)。
3. 実装する。4. 対象テストが全部通るまで直す(最大3回)。通ったら `assembleDebug`。
- Robolectric で Activity の中の Compose の状態を書き換えて確かめるテストでは、確かめる前に `Snapshot.sendApplyNotifications()` を呼ぶ。

## この Task でしないこと
- ホーム・パネルの配置、前面の判定の規則、中継アプリ、帯の変更。全テストの実行、実機の操作、git のコミット等。

## 報告
実行したコマンドと結果(件数・失敗件数、修正前の失敗の内容、assembleDebug)、作成・変更・空にしたファイル、消した・置き換えたテスト(旧名→新名)、**決めたこと**。
