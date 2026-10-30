# Task 057: 実機の場面テストのスクリプトを直し、中継アプリの場面を足す

最初に `tasks/RULES.md` を読み、従うこと。**adb は実行しない(司令塔が実行する)。** `bash -n` で文法だけ確かめる。

## 背景(司令塔が 054 のスクリプトを実機で流して分かったこと)
1. `link_fromYlHome` が「分割の枠が残っている」で不合格になった。分割の枠のタスク(`type=undefined mode=multi-window`)は、分割が無いときも `visible=false` のまま残る。**`visible=true` の枠だけを数える**必要がある。
2. `set_strip_coldPanel` は、前に開いたセットの番号の続きから開くため、行き先がホームになって判定できなかった。**準備で前回のセットの番号を 0 にする**: `adb shell run-as com.gt8d.launcher sh -c 'echo 0 > files/last_set_index.txt'`(デバッグ版なので run-as が使える。プロセスを止めた後に書く)。その後に左の帯をスワイプすると、セット1(YouTube | パネル)が開く。
3. 既定のホームが YLuncher のとき、`am start -n com.gt8d.launcher/.ui.home.HomeActivity` は `Error: Activity not started, unknown error code -96` で失敗する。**既定のホームが YLuncher のときは、HOME キー(`input keyevent KEYCODE_HOME`)で YLuncher のホームを出す**。
4. 分割の右に渡すパッケージは中継アプリ `com.gt8d.launcher.panel` になった(Task 055・056)。右の枠のタスクは「affinity が `com.gt8d.launcher.panel`、一番上が `com.gt8d.launcher/.ui.panel.PanelActivity`」になる。今の判定(右の一番上が PanelActivity)はそのまま使える。`openSplit … right=` のログは `com.gt8d.launcher.panel` になるので、`set_strip_coldPanel` の判定で右が `com.gt8d.launcher` **または** `com.gt8d.launcher.panel` ならパネルとして扱う。
5. YLuncher のタスクを消す対象(affinity が `com.gt8d.launcher` で始まる)には中継のタスクも含まれる(今の正規表現のままでよい)。`cold` の準備では、中継も止める(`am force-stop com.gt8d.launcher.panel`)。

## 変更するファイル(これで全部)
- `scripts/device-scenarios.sh`

使ってよいコマンド:
- `bash -n scripts/device-scenarios.sh`

## 足す場面
| 名前 | 準備 | 操作 | 合格の条件 |
|---|---|---|---|
| link_appPanelFront | link_cold_noTasks の直後(分割「ChatGPT \| パネル」で、パネルにブラウザが出ている)→ 右のパネルのブラウザを閉じる代わりに、`logcat -c` だけ行う | ChatGPT からもう一度リンク(送り元 ChatGPT)→ 6 秒 | `[Link] front=AppPanel`、`route=PanelTaskBrowser`、`wm_create_activity` に `PanelActivity` も `FullscreenBrowserActivity` も無い、右の枠の一番上が PanelActivity のまま、`com.gt8d.launcher` の affinity(`A=…:com.gt8d.launcher ` ちょうど)のタスクが新しく増えていない |
- `link_appPanelFront` は単独指定(`--only`)のときも前提の `link_cold_noTasks` を先に流す(`link_panelTaskPresent` と同じ扱い)。
- `--with-ylauncher-home` の対象にも `link_appPanelFront` を足す。

## この Task でしないこと
- adb の実行。アプリのコードの変更。git のコミット等。

## 報告
変更点、`bash -n` の結果、**決めたこと**。
