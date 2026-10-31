# Task 081: 実機の場面テストを左パネルに広げる(v1.5 Task 8)

最初に `tasks/RULES.md` を読み、従うこと。**adb は実行しない(司令塔が実機で流す)。** `bash -n` と `assembleDebug` で確かめる。

## 背景
v1.5(設計書 `docs/superpowers/specs/2026-10-31-v1.5-left-panel-design.md` §7.2、計画 Task 8)。Task 074〜080 で、左パネルのセット・ドックの左配置・入れ替えの保存(ログ `[SetRunner] swapSaved id=… panel=…`)・リンク(ログ `[Link] front=AppPanel(app=…, panelSide=…) route=SplitWithBrowser(app=…, browserSide=…)`、`AppBrowser(app=…, browserSide=…)`)ができている。各ログの実際の文言は、コード(`SetRunner.kt`・`PanelSwapSaver.kt`・`LinkEntryActivity.kt`・`AppGraph.kt` の `placement=`)で確かめてから判定に使う。
GT8D では `uiautomator dump` が使えないので、パネルの操作はデバッグ版の受け口 `DebugPanelReceiver`(`am broadcast -n com.gt8d.launcher/.debug.DebugPanelReceiver -a com.gt8d.launcher.debug.PANEL`)を使う。

## 変更するファイル(これで全部)
- `app/src/debug/java/com/gt8d/launcher/debug/DebugPanelReceiver.kt`
- `scripts/device-scenarios.sh`

使ってよいコマンド:
- `bash -n scripts/device-scenarios.sh`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様

### A. 受け口に足す extra(今の `view`・`open` はそのまま)
1. `--es launch <pkg>`: パネルからアプリを開くのと同じ経路(パネルの `onLaunchApp` が使う起動 = `ActivityLauncher` の隣に開く起動)で `<pkg>` を開き、成功したら `AppGraph.runner.panelLaunched(pkg)`。ログ `[DebugPanel] launch <pkg> ok|failed`。同じ経路を呼べない場合は、PanelActivity と同じ `ActivityLauncher`(`AppGraph` にあるもの)を使い、`adjacent = true` で開く。どうしたか報告する。
2. `--es addLeftPanelSet <pkg>`: 設定に id `scenario-left`・`ScreenSet("scenario-left", Slot.Panel, Slot.App(<pkg>), SplitRatio.R4_6)` を**末尾に**足す(同じ id があれば置き換える)。ログ `[DebugPanel] addLeftPanelSet index=<1始まりの番号>`。
3. `--es removeSet <id>`: その id のセットを消す。ログ `[DebugPanel] removeSet <id>`。

### B. スクリプト
1. **設定の退避と復元**: 最初に、端末の設定ファイル(`run-as com.gt8d.launcher cat files/<設定ファイル名>`。名前は `AppGraph`/`ConfigRepository` の作り方で確かめる)を `"$out/config-backup.json"` に保存し、端末にも `files/config-scenario-backup.json` として残す。開始時に端末に前回の `config-scenario-backup.json` が残っていたら、先にそれを設定ファイルに書き戻して消す(前回の途中終了の後始末)。`cleanup`(trap)で、設定ファイルを書き戻し → `am force-stop com.gt8d.launcher` → 端末の退避を消す。書き戻しに失敗したら `row cleanup FAIL '設定の復元に失敗(退避: …)'`。
2. **既存の判定を新しいログに合わせる**: `link_appPanelFront` の `AppPanel(left=…)`・`SplitWithBrowser(left=…)` などを、新しい形(`AppPanel(app=com.google.android.youtube, panelSide=Right)`、`SplitWithBrowser(app=…, browserSide=Right)`)に。他のリンクの場面も `SplitWithBrowser(app=…, browserSide=Right)` に。PASS の説明文も合わせる。
3. **新しい場面**(`names` の末尾に、この順で。`--with-ylauncher-home` の並びにも足す):
   - `set_leftPanel_cold`: 準備で `addLeftPanelSet com.google.android.youtube` → 本体・中継を force-stop → 受け口 `--ei open <その番号>`。判定: `openSplit left=com.gt8d.launcher.panel right=com.google.android.youtube`、`waitBoth ready`、Safety なし、分割の左が中継のタスク(PanelActivity)・右が YouTube、`[Panel] placement=Left`。
   - `set_leftPanel_warm`: 前提 `set_leftPanel_cold`。ホームへ → もう一度 `open` → 同じ判定。
   - `leftPanel_launchApp`: 前提 `set_leftPanel_warm`。受け口 `--es launch com.google.android.apps.maps`。判定: 右の最前面がマップ、左は PanelActivity のまま、`panelLaunched pkg=com.google.android.apps.maps`。
   - `leftPanel_dividerSwap`: 前提 `set_leftPanel_warm`(launchApp の後なら相手が変わって保存されないので、warm を開き直してから)。仕切りの位置を `dumpsys activity activities` の分割の左右の枠(`mBounds`)から求め(左の枠の right と右の枠の left の中間・画面の縦の中央)、`adb shell "input tap X Y; input tap X Y"` → 3 秒。判定: (a) `adb logcat` 全体に `onDoubleTappedDivider` がある(無ければ1回だけやり直し、それでも無ければ「ダブルタップが効かない」で FAIL)、(b) `swapSaved id=scenario-left panel=Right`、(c) `[Panel] placement=Right`、(d) 設定ファイル(run-as cat)の `scenario-left` の `left` が App(YouTube)・`right` が Panel・`ratio` が `R6_4`。続けてもう一度ダブルタップ → `swapSaved id=scenario-left panel=Left` と設定が元(`left` Panel・`R4_6`)。
   - `leftPanel_link`: 前提 `set_leftPanel_warm`。右の YouTube から VIEW(今の `link` 関数と同じやり方で YouTube を referrer に)。判定: `front=AppPanel(app=com.google.android.youtube, panelSide=Left)`、`route=SplitWithBrowser(app=com.google.android.youtube, browserSide=Left)`、分割の左が `$browser`・右が YouTube、VIEW は分割成立後。
   - `leftPanel_link_twice`: 前提 `leftPanel_link`。もう一度 YouTube からリンク。判定: `front=AppBrowser(app=com.google.android.youtube, browserSide=Left)`、左がブラウザ・右が YouTube のまま。
   - `set_leftToRightPanel`: 前提 `set_leftPanel_warm`。受け口で右パネルのセット1(既定の「YouTube | パネル」)を `open 1`。判定: `openSplit left=com.google.android.youtube right=com.gt8d.launcher.panel`、`waitBoth ready`、`placement=Right`。
   - 各場面の終わりで、テスト用セットは消さない(最後の cleanup の設定の復元で元に戻る)。
4. 前提(prerequisite)の扱いは既存の仕組み(`run()` の `prerequisite`・`--only` のときの前提の実行)に合わせる。

## この Task でしないこと
- adb の実行。main のアプリのコードの変更。git のコミット等。

## 報告
変更点、`bash -n` と `assembleDebug` の結果、確かめたログの文言、**決めたこと**。
