# Task 060: 実機の場面テストを「リンクは外部ブラウザで分割」に合わせる

最初に `tasks/RULES.md` を読み、従うこと。**adb は実行しない(司令塔が実行する)。** `bash -n` で文法だけ確かめる。

## 背景
Task 059 で、リンクは内蔵ブラウザ(`FullscreenBrowserActivity`・パネルの中のブラウザ)をやめ、**「左のアプリ | 外部ブラウザ」の分割**に開くようになった(仕様 §4.7・§6-3)。流れ:
- リンクの受け口 `LinkEntryActivity` が前面を見分け、ログ `[Link] front=<前面> route=<行き先> ref=<送り元>` を出す(タグ `GT8D`)。行き先は `route=SplitWithBrowser(left=<左のパッケージ>)` か `route=FullscreenBrowser`。前面がパネルの分割のときは `front=AppPanel(left=<左>)`。
- 分割のときは SetRunner が E で「左 | ブラウザ」を開き(`[SetRunner] waitBoth ready …`)、幕が外れた後、ブラウザに `VIEW`(`setPackage(<ブラウザ>)`、`FLAG_ACTIVITY_NEW_TASK`)を送る。全画面のときはすぐ `VIEW` を送る。
- ブラウザは設定の `browserPkg`(既定 `com.android.chrome`)。端末の Chrome は入っている。
- 端末の既定のブラウザは YLuncher のまま(`link()` の VIEW は受け口に届く)。
- Android の `ActivityTaskManager` は、起動のたびに `logcat` に `START u0 {act=android.intent.action.VIEW … pkg=com.android.chrome …}` のような行を出す(`capture` の `logcat-full.txt` に入る)。

## 変更するファイル(これで全部)
- `scripts/device-scenarios.sh`

使ってよいコマンド:
- `bash -n scripts/device-scenarios.sh`

## 仕様
1. 定数 `browser=com.android.chrome` を足す(アプリの既定の `browserPkg` と同じ。環境変数で変えられるようにはしない)。
2. 補助関数を足す:
   - `left_is <pkg>`: `slots.txt` の LEFT の最前面が `<pkg>/` で始まる。違えば理由「左の最前面が <pkg> でない: <実際>」。
   - `view_sent`: `logcat-full.txt` に `START u0 \{[^}]*act=android\.intent\.action\.VIEW[^}]*pkg=<browser の . をエスケープ>([[:space:]}]|$)` に当たる行がある。無ければ理由「ブラウザへの VIEW がない」。
   - `view_after_ready`(分割の場面だけ): `logcat-full.txt`(GT8D の行も入っている)で、上の VIEW の行が**どれも** `\[SetRunner\] waitBoth ready` の最初の行より後ろにある。前にあれば理由「VIEW が分割の成立より前」。
   - `split_shown`: `slots.txt` に LEFT と RIGHT の両方がある。無ければ理由「分割の枠が2つない」。
3. `browser_created` と、`FullscreenBrowserActivity`・`PanelTaskBrowser`・`about:blank` の内蔵ブラウザを使う準備は全部消す。
4. `reset cold` は、本体・中継に加えて **ブラウザも** `am force-stop "$browser"` する(ブラウザが冷えた状態も網羅するため)。
5. 場面(`names` の順はこの表の順。`--with-ylauncher-home` の対象は、この順で `link_cold_noTasks link_homeTaskPresent link_fromYlHome set_strip_coldPanel link_appPanelFront`):

| 名前 | 準備 | 操作 | 合格の条件 |
|---|---|---|---|
| link_cold_noTasks | `reset cold` | ChatGPT を開き、ChatGPT からリンク → 10 秒 | `front=Fullscreen(pkg=com.openai.chatgpt)`、`route=SplitWithBrowser(left=com.openai.chatgpt)`、`waitBoth ready`、Safety なし、`split_shown`、`left_is com.openai.chatgpt`、`right_is $browser`、`view_sent` |
| link_browserTaskPresent | `link_cold_noTasks` の直後(ブラウザのタスクが残っている)。HOME キー → `logcat -c`・`logcat -b events -c` | 同上 | 同上 |
| link_warm_noTasks | `reset`(ブラウザは止めない)→ `am start -a android.intent.action.VIEW -d https://example.com/ -p "$browser"` → 2 秒 → HOME → YLuncher のタスクを消す →(今の warmup の保存と logcat の消去) | 同上 | 同上 |
| link_homeTaskPresent | 今のまま(`reset cold`・YLuncher のホームを出す・HOME) | 同上 | 同上 |
| link_fromYlHome | 今のまま | Gmail からリンク → 6 秒 | `front=Home`、`route=FullscreenBrowser`、`view_sent`、可視の分割の枠なし、`activities.txt` の最初の `topResumedActivity=ActivityRecord{…}` の行から、`parse_slots` と同じ方法でコンポーネント(`パッケージ/クラス`)を取り出し、それが `$browser/` で始まる |
| set_strip_coldPanel | 今のまま | 今のまま | 今の条件に加え、`openSplit` のログの左が `com.google.android.youtube`(後の link_appPanelFront の前提を確かめるため) |
| link_appPanelFront | **`set_strip_coldPanel` の直後**(分割「YouTube \| パネル」)。準備は今の link_appPanelFront と同じく、`activities-before`・`events-before` を保存して `logcat -c` | YouTube からリンク(送り元 `com.google.android.youtube`)→ 10 秒 | `front=AppPanel(left=com.google.android.youtube)`、`route=SplitWithBrowser(left=com.google.android.youtube)`、`waitBoth ready`、Safety なし、`split_shown`、`left_is com.google.android.youtube`、`right_is $browser`、`view_sent`、`com.gt8d.launcher` の affinity のタスクが増えていない(今の判定を残す) |

   - `--only link_browserTaskPresent` のときは前提の `link_cold_noTasks` を、`--only link_appPanelFront` のときは前提の `set_strip_coldPanel` を先に流す(`@prerequisite` の付け方は今のまま。`@ylhome` 側も同じ)。
   - **前提の場面が不合格のとき**、それに続く場面(link_browserTaskPresent・link_appPanelFront)は実行せず、結果を `SKIP`、理由「前提の <名前> が不合格」とする(`failed=1` にする)。全場面の実行でも `--only` でも、同じ版(`''` / `@ylhome`)の直前の前提の結果を見る。
   - link_browserTaskPresent の `front` は、HOME の観測の仕方で `Fullscreen(pkg=com.openai.chatgpt)` にも `AppApp(left=com.openai.chatgpt, …)` にもなりうる。どちらも合格にする(`route` の条件は変えない)。
   - 合格の行の説明文は、上の条件に合わせて書き直す。
   - `right_is` の「パネルなら PanelActivity」の分岐は `set_strip_coldPanel` のために残す。
6. 既定のホーム・既定のブラウザの保存と復元、`capture`、`parse_slots`、`cleanup` は変えない。

## 前提(スクリプトは確かめない。司令塔が流す前に整える)
- 分割の方式は E(Vendor)、使用状況アクセスは許可済み、リンクの設定は「分割」、セット1は「YouTube | パネル」。

## この Task でしないこと
- adb の実行。アプリのコードの変更。git のコミット等。

## 報告
変更点、`bash -n` の結果、**決めたこと**。
