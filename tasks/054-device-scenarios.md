# Task 054: 実機の場面テストのスクリプト(前提条件の組み合わせを網羅する)

最初に `tasks/RULES.md` を読み、従うこと。**このスクリプトは司令塔が実行する。codex は adb を実行できない(実行しないこと)。** 書いたら `bash -n` で文法だけ確かめる。

## 背景
冷えた起動のリンクを、ホームのタスクが無い1通りだけで確かめて「直った」としたが、ホームのタスクが残っていると失敗した(Task 053)。
分割・リンク・タスクに触る変更の後に、前提条件の組み合わせを毎回漏れなく試すためのスクリプトを作る。

## 作成するファイル(これで全部)
- `scripts/device-scenarios.sh`(bash。実行権限は司令塔が付ける)

使ってよいコマンド:
- `bash -n scripts/device-scenarios.sh`

## 司令塔が実機で確かめた事実(この前提で書く)
- adb は Windows の `adb.exe -s 192.168.137.248:5555`(WSL の adb は届かない)。`ADB` 環境変数で上書きできるようにする。
- タスクの一覧: `adb shell dumpsys activity activities` の `* Task{… #<番号> … A=<uid>:<affinity> …}` の行。分割の中のタスクは `rootTaskId=2` を持ち、分割の左右の枠は `Task{… #3 type=undefined …}` と `#4`(番号は端末で変わりうるので、`type=undefined` かつ `mode=multi-window` の枠の子として見つける)。
- YLuncher のタスクを消す: `adb shell am stack remove <タスク番号>`(affinity が `com.gt8d.launcher` で始まるタスクだけを対象にする。**分割の枠や他のアプリのタスクは消さない**)。
- 送り元つきのリンク: `adb shell am start -a android.intent.action.VIEW -d <URL> --es android.intent.extra.REFERRER_NAME android-app://<pkg>`
- アプリを全画面で開く: `adb shell monkey -p <pkg> -c android.intent.category.LAUNCHER 1`
- 帯のスワイプ: 左の帯 `input swipe 20 540 500 540 300`(次のセット)、右の帯 `input swipe 1905 540 1400 540 300`(前のセット)。画面は 1920x1080。
- YLuncher のログ: `adb logcat -v time GT8D:D '*:S'`。判定に使う行: `[Link] front=… route=…`、`[SetRunner] waitBoth ready …`、`[SetRunner] onCurtainHidden reason=Safety …`、`[SetRunner] retry …`。
- 既定のホーム: `adb shell cmd role get-role-holders android.app.role.HOME`。切り替え: `adb shell cmd role add-role-holder android.app.role.HOME <pkg> 0`。純正は `com.android.launcher3`。
- **ログ(`logcat -c` で消す前に)とスクリーンショットは、場面ごとに必ず保存する**。

## 仕様
1. 使い方: `scripts/device-scenarios.sh [--with-ylauncher-home] [--only <場面名>]`。結果は `build/device-scenarios/<日時>/` に、場面ごとのフォルダ(`logcat.txt`、`events.txt`(`logcat -b events` の wm_ 行)、`tasks.txt`(dumpsys の Task の行)、`screen.png`)と、全体の `summary.md`(表)を書く。1つでも失敗なら終了コード 1。
2. 共通の準備 `reset [cold]`: HOME キー → YLuncher のタスクをすべて消す → `cold` なら `am force-stop com.gt8d.launcher` → 1 秒待つ → `logcat -c` と `logcat -b events -c`。
3. 場面(既定のホームが純正のとき):
   | 名前 | 準備 | 操作 | 合格の条件 |
   |---|---|---|---|
   | link_cold_noTasks | reset cold | ChatGPT(`com.openai.chatgpt`)を全画面 → 4 秒 → 送り元 ChatGPT のリンク → 10 秒 | `front=Fullscreen(pkg=com.openai.chatgpt)`、`waitBoth ready` あり、`reason=Safety` なし、分割の右の枠の一番上の Activity が `com.gt8d.launcher/.ui.panel.PanelActivity`、`FullscreenBrowserActivity` が作られていない(events の wm_create_activity) |
   | link_warm_noTasks | reset(cold なし。プロセスを温めるため、先に `am start -n com.gt8d.launcher/.link.FullscreenBrowserActivity --es url about:blank` → 2 秒 → HOME → YLuncher のタスクを消す) | 同上 | 同上 |
   | link_homeTaskPresent | reset cold → `am start -n com.gt8d.launcher/.ui.home.HomeActivity` → 3 秒 → HOME キー | 同上 | 同上(右がホームではなくパネル) |
   | link_panelTaskPresent | link_cold_noTasks の後、HOME キー(パネルのタスクが残る) | ChatGPT を全画面 → 4 秒 → リンク | 同上 |
   | link_fromYlHome | reset → `am start -n …/.ui.home.HomeActivity` → 3 秒(YLuncher のホームを前に) | 送り元 `com.google.android.gm` のリンク → 6 秒 | `front=Home`、`FullscreenBrowserActivity` が作られる、分割が無い |
   | set_strip_coldPanel | reset cold → `am start -n …/.ui.home.HomeActivity` → 3 秒 → ChatGPT を全画面 → 4 秒 | 左の帯のスワイプ → 10 秒 | ログの `openSplit left=… right=…` の右が `com.gt8d.launcher` なら: `waitBoth ready`・`reason=Safety` なし・右の枠が PanelActivity。右がアプリなら: `waitBoth ready`・右の枠の一番上がそのアプリ |
4. `--with-ylauncher-home` のとき: 最初に今の既定のホームを控え、YLuncher(`com.gt8d.launcher`)に切り替えて、`link_cold_noTasks`・`link_homeTaskPresent`・`link_fromYlHome`・`set_strip_coldPanel` をもう一度(名前の末尾に `@ylhome`)。**終わったら(途中で失敗・中断しても `trap` で)既定のホームを元に戻す**。
5. 判定の関数は、何を確かめて何が違ったかを `summary.md` の表の「理由」の列に1行で書く。
6. 端末の状態を壊さない: 既定のブラウザ・既定のホームは最後に元に戻す(ブラウザは変えないが、変わっていたら警告を出す)。YLuncher 以外のタスクは消さない。

## この Task でしないこと
- adb の実行。アプリのコードの変更。git のコミット等。

## 報告
作ったファイル、`bash -n` の結果、**決めたこと**、司令塔が最初に試すべき場面と、判定の根拠にしている行・正規表現の一覧。
