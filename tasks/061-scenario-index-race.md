# Task 061: 場面テスト set_strip_coldPanel の準備の競合を直す

最初に `tasks/RULES.md` を読み、従うこと。**adb は実行しない(司令塔が実行する)。** `bash -n` で文法だけ確かめる。

## 背景(司令塔が実機で確かめた事実)
- 既定のホームが YLuncher のとき、`am force-stop com.gt8d.launcher` の直後にシステムが本体のプロセスをすぐ起こし直す。そのプロセスは起動時に `files/last_set_index.txt` を読むので、**その後に run-as で 0 を書いても、生きているプロセスの番号は古いまま**になる。実際に `set_strip_coldPanel@ylhome` で `[SetRunner] next base=1 -> 2` となり、セット2(YouTube | Apple Music)が開いた。
- 今の判定は「openSplit の左が YouTube」と「右のパッケージと右の最前面が一致」なので、この誤りを合格にしてしまい、続く `link_appPanelFront@ylhome` が前提違いで不合格になった。

## 変更するファイル(これで全部)
- `scripts/device-scenarios.sh`

使ってよいコマンド:
- `bash -n scripts/device-scenarios.sh`

## 仕様
1. `set_strip_coldPanel` の準備: run-as で 0 を書いた**後に**、もう一度 `am force-stop com.gt8d.launcher` と `am force-stop com.gt8d.launcher.panel` を行う(起こし直されたプロセスが 0 を読むように)。その後は今のまま(YLuncher のホームを出す → ChatGPT → スワイプ)。
2. `set_strip_coldPanel` の判定に足す:
   - `expect_log '\[SetRunner\] next base=0 -> 1'`
   - `openSplit` の右が `com.gt8d.launcher.panel` であること(違えば理由「openSplit の右が com.gt8d.launcher.panel でない: <実際>」)。今の `right_is "$right"` と Safety の確認は残す。
3. 合格の行の説明文を合わせて直す。

## この Task でしないこと
- adb の実行。アプリのコードの変更。git のコミット等。

## 報告
変更点、`bash -n` の結果、**決めたこと**。
