# Task 085: リンクの前面の判定で、純正の分割の画面を「ほかのアプリ」と数えない

最初に `tasks/RULES.md` を読み、従うこと。

## 背景(司令塔が実機で確かめた事実)
場面 `leftPanel_link_twice`(`build/device-scenarios/20261031-182003-480826/leftPanel_link_twice`)で、「ブラウザ | YouTube」をリンクで作った直後に YouTube からもう一度リンクを押すと、`front=AppBrowser(…)` ではなく `front=Fullscreen(pkg=com.google.android.youtube)` になる。
`LinkEntryActivity` の「最後の組を開いた後に、ほかのアプリが前面に来たか」(`otherForegroundAfterLastPair`。`AppGraph.watcher.foregroundPkgsSince(startedAt)` で組の2つと自分のパッケージ以外があるか)が、組を作る途中で一瞬前面に出る純正の分割の画面 `com.sd.factoryconfig/.SplitActivity` を「ほかのアプリ」と数えている(events: 18:25:01.713 `wm_on_resume_called … com.sd.factoryconfig.SplitActivity`)。そのため E で作った分割は、直後でも「組が前面にない」と判断される。右パネルの v1.0 でも、両方アプリの分割からの2回目のリンクで同じことが起きる。

## 変更するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/link/LinkEntryActivity.kt`
- `app/src/test/java/com/gt8d/launcher/link/LinkEntryActivityTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.link.*'`

## 仕様
1. 「ほかのアプリ」の判定から、純正の分割のパッケージ `VendorBridge.VENDOR_PKG`(`com.sd.factoryconfig`)を除く(組の2つ・自分のパッケージと同じ扱い)。定数は既存の `VendorBridge.VENDOR_PKG` を使う。
2. 他の判定は変えない。

## テスト(足す。既存は弱めない)
- `LinkEntryActivityTest.vendorSplitActivity_isNotOtherForeground`: 最後の組 `browser to "y"`、開いた後の前面に `com.sd.factoryconfig` と組の2つだけ → `FrontClues.otherForegroundAfterLastPair == false`(または、結果として `AppBrowser("y", Left)` で `openForLink(browser, "y", …)`)。
- `…_realOtherApp_stillCounts`: 前面に別のアプリ(例 `"maps"`)があれば今までどおり true。

## この Task でしないこと
- 他のファイルの変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、**決めたこと**。
