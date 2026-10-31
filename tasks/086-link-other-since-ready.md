# Task 086: リンクの「組の後にほかのアプリが来たか」を、組が成立した時刻から見る

最初に `tasks/RULES.md` を読み、従うこと。

## 背景(司令塔が実機で確かめた事実)
085 で純正の分割の画面(`com.sd.factoryconfig`)を「ほかのアプリ」から除いたが、純正ホームのとき、分割を作る途中で純正ランチャー `com.android.launcher3` も一瞬前面に出る(`build/device-scenarios/` の最新の `leftPanel_link@prerequisite@run1`: 18:45:27.265 に QuickstepLauncher が resume、`waitBoth ready` は 18:45:27.821)。そのため2回目のリンクがまだ `front=Fullscreen` になる(純正ホームのみ。YLuncher ホームでは自分のパッケージなので除かれて合格)。
途中の一瞬の表示はすべて「組が成立する前」に起きるので、**組が成立した時刻から後**に前面に来たアプリだけを見ればよい。

## 変更するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt`
- `app/src/main/java/com/gt8d/launcher/link/LinkEntryActivity.kt`
- `app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt`
- `app/src/test/java/com/gt8d/launcher/link/LinkEntryActivityTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.sets.*' --tests 'com.gt8d.launcher.link.*'`

## 仕様
1. `SetRunner` に `var lastReadyAtMs: Long? = null`(public、読み取り用)。`waitBoth` が成立した(`waitBoth ready`。セット・リンク・起動時のどれでも)時刻を入れる。`lastOpenedAtMs` を null にしている所と、新しく開き始めた所では null にする。
2. `LinkEntryActivity`: 「ほかのアプリ」の判定の起点を `AppGraph.runner.lastReadyAtMs ?: AppGraph.runner.lastOpenedAtMs` にする。085 の `VendorBridge.VENDOR_PKG` の除外は残す。
3. 他は変えない。

## テスト(足す。既存は弱めない)
- `SetRunnerTest.lastReadyAt_setOnReady_clearedOnNextOpen`: ready で値が入り、次のセットを開き始めると null、ready で新しい値。
- `LinkEntryActivityTest.transientStockLauncherBeforeReady_isIgnored`: 組の開始の後・成立の前に `com.android.launcher3` が前面に来ても、成立の後に来ていなければ `AppBrowser`(`openForLink(browser, "y", …)`)。
- `…_otherAppAfterReady_stillCounts`: 成立の後に別のアプリ(`maps`)が来たら今までどおり。

## この Task でしないこと
- 他のファイルの変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、**決めたこと**。
