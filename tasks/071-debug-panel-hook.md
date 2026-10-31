# Task 071: デバッグ版にパネルの操作の受け口を足し、場面テストをそれで動かす

最初に `tasks/RULES.md` を読み、従うこと。**adb は実行しない(司令塔が実機で流す)。**

## 背景(司令塔が実機で確かめた事実)
069 で足した場面 `set_panelCard_switch` は `uiautomator dump` で画面の要素を取ってタップする作りだが、GT8D では `uiautomator dump` が常に `ERROR: could not get idle state.` で失敗する(純正ホームでも、YLuncher を force-stop しても同じ。端末全体の事情)。座標の決め打ちは避けたい。
そこで、**デバッグ版だけ**に、adb から送る合図でパネルの表示の切り替えとセットを開く受け口を足す。セットを開く処理は、パネルのカードを押したときと**同じ経路**(開いている最中は無視して `AppGraph.runner.open(番号)`)を呼ぶ。カードを押す操作そのものは PanelContentTest/PanelScreenTest で確かめ済み。

## 変更・作成するファイル(これで全部)
- create `app/src/debug/java/com/gt8d/launcher/debug/DebugPanelReceiver.kt`
- `app/src/debug/AndroidManifest.xml`
- `scripts/device-scenarios.sh`

使ってよいコマンド:
- `bash -n scripts/device-scenarios.sh`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様
1. `DebugPanelReceiver`(`BroadcastReceiver`)。マニフェスト(debug)に `exported="true"`、action `com.gt8d.launcher.debug.PANEL` で登録(既存の `SpikeReceiver` と同じ書き方)。
   - extra `view`(`apps` / `sets`): `AppGraph.config.update { it.copy(panelView = ...) }` を `goAsync()` + コルーチンで行い、終わったら `DiagLog.d("DebugPanel", "view=<Apps|Sets> saved")`。表示中の PanelScreen は config の変化で切り替わる(既存の仕組み)。
   - extra `open`(整数): `AppGraph.runner.opening.value != null` なら `DiagLog.d("DebugPanel", "open ignored while opening")`、そうでなければ `DiagLog.d("DebugPanel", "open index=N")` を出して `AppGraph.runner.open(N)`(PanelScreen の `onOpenSet` と同じ判定・呼び出し)。
   - `AppGraph.init(context.applicationContext as Application)` が要るなら、既存の受け口(`BootReceiver` など)に合わせて呼ぶ。
   - 不明な extra は `DiagLog.d("DebugPanel", "unknown")` だけ。
2. `scripts/device-scenarios.sh` の `set_panelCard_switch`:
   - 操作を `adb shell am broadcast -n com.gt8d.launcher/.debug.DebugPanelReceiver -a com.gt8d.launcher.debug.PANEL --es view sets` → 3 秒待つ → 画面を `screencap` で `"$active/sets-view.png"` に保存(証跡)→ `adb shell am broadcast -n ... --ei open 2` → 10 秒待つ、に置き換える。
   - 判定に `expect_log '\[DebugPanel\] view=Sets saved'` と `expect_log '\[DebugPanel\] open index=2'` を足す(既存の判定はそのまま)。
   - 後片付け(`restore_panel_apps`)も、`--es view apps` の合図で「アプリ」表示に戻し、`[DebugPanel] view=Apps saved` のログで確かめる形にする(セット1を開き直す部分は今のまま)。
   - 使わなくなった `uiautomator` の関数(`panel_element`・`dump_panel`・`tap_panel_label`)は消す。
   - ログの取り方は既存(`capture` など)に合わせる。`DiagLog` のタグが logcat でどう出るか(既存の `[SetRunner]` の判定と同じ形か)をコードで確かめて合わせる。
3. `assembleDebug` が通ること。リリース版には受け口が入らないこと(main のマニフェストは変えない)。

## この Task でしないこと
- main のコード・マニフェストの変更。adb の実行。git のコミット等。

## 報告
変更点、`bash -n` と `assembleDebug` の結果、**決めたこと**。
