# Task 080: リンクを左パネルに対応させる(v1.5 Task 7)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
v1.5(設計書 `docs/superpowers/specs/2026-10-31-v1.5-left-panel-design.md` §6、計画 Task 7。この指示書が優先)。今は、前面が「アプリ | パネル」(右パネル)のときリンクを押すと「アプリ | ブラウザ」で開く(`LinkRouter.detectFront` → `FrontState.AppPanel(left)` → `LinkRoute.SplitWithBrowser(left)` → `LinkEntryActivity` が `AppGraph.runner.openForLink(route.left, browser, …)`)。v1.5 では:
- 左パネル(「パネル | アプリ」)のときは「**ブラウザ | アプリ**」(ブラウザはパネルがあった側)。
- パネルの側は `AppGraph.panelPlacement`(Task 075。Left/Right/Full/null)で判断し、null・Full なら `lastOpenedPair` のうち中継(`OwnPackages.PANEL_HOST`)がある側で判断する。
- 相手のアプリは「組のうち中継でない方」(Task 076 の `panelLaunched` でパネルから替えたアプリに更新済み)。
- **組にブラウザが入っている分割**(リンクで作った「ブラウザ | アプリ」「アプリ | ブラウザ」)から、もう一度リンクを押したら、ブラウザの側はそのまま、反対側のアプリを残して同じ並びで開き直す(今は全画面のブラウザになってしまう)。
- 全画面のアプリから・両方アプリ(ブラウザを含まない)の分割から・ホームからは今のまま。

## 変更するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/link/LinkRouter.kt`
- `app/src/main/java/com/gt8d/launcher/link/LinkEntryActivity.kt`
- `app/src/test/java/com/gt8d/launcher/link/LinkRouterTest.kt`
- `app/src/test/java/com/gt8d/launcher/link/LinkEntryActivityTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.link.*'`

## 仕様
1. `FrontClues` に `val panelSide: Side? = null`(`AppGraph.panelPlacement` の Left/Right → Side、それ以外 null)を足す。ブラウザのパッケージは `route` に既に渡っている `LinkConfig.browserPkg` を使う。
2. `FrontState`:
   - `AppPanel(val app: String?, val panelSide: Side)`(今の `AppPanel(left)` を置き換え)
   - `AppBrowser(val app: String, val browserSide: Side)` を足す
   - `AppApp`・`Fullscreen`・`Home`・`Unknown` は今のまま
3. `detectFront(clues, ownPkg)` に、ブラウザのパッケージを受け取る引数(例 `browserPkg: String`)を足す:
   - `panelInSplit` のとき: `panelSide = clues.panelSide ?: (lastPair のうち中継がある側) ?: Side.Right`、`app = lastPair のうち中継でない方`(lastPair が無ければ null)→ `AppPanel(app, panelSide)`。
   - それ以外で、今 `AppApp` になる条件を満たし、かつ組の片方が `browserPkg` なら → `AppBrowser(もう片方, ブラウザの側)`。
   - それ以外は今のまま。
4. `LinkRoute.SplitWithBrowser(val app: String, val browserSide: Side)`(今の `SplitWithBrowser(left)` を置き換え)。`route(...)`:
   - `AppPanel(app, panelSide)` → `SplitWithBrowser(app, panelSide)`(app が null かブラウザと同じなら `FullscreenBrowser`)
   - `AppBrowser(app, side)` → `SplitWithBrowser(app, side)`
   - `AppApp(left, _)`・`Fullscreen(pkg)` → `SplitWithBrowser(left または pkg, Side.Right)`(今と同じ結果)
   - それ以外は今のまま。
5. `LinkEntryActivity`: `browserSide == Side.Left` なら `openForLink(browser, app, …)`、Right なら `openForLink(app, browser, …)`。`FrontClues` を作るとき `panelSide` を `AppGraph.panelPlacement.value` から入れる。ログ `DiagLog.d("Link", "front=$front route=$route ref=…")` は今のまま(data class の文字列で `AppPanel(app=…, panelSide=…)`・`SplitWithBrowser(app=…, browserSide=…)` になる)。
6. `OwnPackages` の判定(自分を含む組は AppApp にしない)は今のまま。

## テスト(足す。既存は弱めない。`AppPanel(left)`・`SplitWithBrowser(left)` を使う既存の期待値は新しい形に合わせ、結果(残すアプリ・右にブラウザ)が同じであることを保つ。どれを変えたか報告する)
- `LinkRouterTest`:
  - `leftPanel_routesBrowserLeft`: `panelInSplit = true`、`panelSide = Left`、`lastPair = PANEL_HOST to "y"` → `AppPanel("y", Left)` → `SplitWithBrowser("y", Left)`。
  - `leftPanel_doesNotTakePanelHostAsApp`。
  - `panelSideUnknown_fallsBackToPair`: `panelSide = null`、`lastPair = PANEL_HOST to "y"` → Left、`lastPair = "y" to PANEL_HOST` → Right。
  - `afterPanelLaunch_usesNewPartner`: `lastPair = PANEL_HOST to "maps"` → app "maps"。
  - `browserPair_secondLink_keepsOrder`: `lastPair = browser to "y"`、`referrer = "y"`、他の前面なし → `AppBrowser("y", Left)` → `SplitWithBrowser("y", Left)`。`lastPair = "y" to browser` → Right。
  - 既存の右パネル・全画面・両方アプリ・ホームは同じ結果(`browserSide = Right`)。
- `LinkEntryActivityTest`: `leftPanel_opensBrowserLeft`(`openForLink(browser, "y", …)` が呼ばれる)、既存の右パネルは `openForLink("y", browser, …)` のまま。

## この Task でしないこと
- SetRunner・パネル・設定画面の変更。場面テストのログの判定の更新(Task 081)。git のコミット等。

## 報告
変更点、テストの結果(件数)、合わせた既存の期待値、**決めたこと**。

## 補足(点検の反映)
1. 依存: パネルからアプリを替えたときの相手の更新(`panelLaunched` の呼び出し)は Task 079 が入れる。080 の単体テストは `lastPair` を直接与えて確かめればよい(結合の確認は実機の場面テストで行う)。
2. **実際に開くブラウザで判断する**: `LinkEntryActivity` で `BrowserChooser.choose(...)` を先に行い、その `browser`(設定の値ではなく、実際に選ばれたもの)を `detectFront` と `route` に渡す。テスト: 設定のブラウザが入っておらず代わりのブラウザが選ばれる場合、その代わりのブラウザを含む組から2回目のリンクで同じ並び。
3. `LinkEntryActivityTest` の左ブラウザのテストでは、偽の installed にブラウザも含め、`openForLink` の左右の順と、ready の後の VIEW の送信まで確かめる。
