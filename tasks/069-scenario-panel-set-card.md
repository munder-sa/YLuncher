# Task 069: 場面テストに「分割のパネルのセット表示からセットを開く」を足す

最初に `tasks/RULES.md` を読み、従うこと。**adb は実行しない(司令塔が実機で流す)。** `bash -n` で文法だけ確かめる。

## 背景
067 で、狭いパネル(分割の右)の時計の下に「アプリ | セット」のセグメントが付き、「セット」表示で画面セットのカードを押すと `AppGraph.runner.open(番号)` でそのセットを開くようになった(ログ `[SetRunner] open index=N`)。分割の中から別の分割へ切り替える新しい経路なので、場面テストで確かめる。

## 変更するファイル(これで全部)
- `scripts/device-scenarios.sh`

使ってよいコマンド:
- `bash -n scripts/device-scenarios.sh`

## 仕様
1. 場面 `set_panelCard_switch` を足す。前提は `set_strip_coldPanel`(セット1 = YouTube | パネル の分割が開いている状態)が直前に合格していること(`link_appPanelFront` と同じ前提の仕組みを使う)。`names` の並びでは `set_strip_coldPanel` の直後に置き、`link_appPanelFront` の前提が崩れないよう、`link_appPanelFront` の前提の扱いを確かめて必要なら合わせる(どうしたか「決めたこと」へ)。`--with-ylauncher-home` の並びにも同様に足す。
2. 操作: `adb shell uiautomator dump /sdcard/ui.xml` → `adb exec-out cat /sdcard/ui.xml` で画面の要素を取り、`text="セット"`(または content-desc)の要素の bounds の中心をタップ → 2 秒待つ → もう一度 dump して、セット2のカード(名前 `YouTube | Apple Music` の text を持つ要素、またはその親のクリックできる要素)の中心をタップ → 10 秒待つ。座標は固定値にしない。見つからなければ理由を残して不合格。xml の解析は既存のスクリプトの書き方(sed/awk/grep)に合わせる。
3. 判定: `expect_log '\[SetRunner\] open index=2'`、`expect_log '\[SetRunner\] waitBoth ready'`、`no_safety`、`split_shown`、`left_is com.google.android.youtube`、`right_is com.apple.android.music`。openSplit の左右もログから確かめる(左 YouTube、右 com.apple.android.music)。
4. 後片付け: 場面の最後に、パネルの表示を「アプリ」に戻す(次の場面・ユーザーの実機の状態のため)。方法は、セット1を開き直す(`[SetRunner] open` を起こす既存の手段、例えば帯のスワイプ)→ 分割のパネルで dump して `text="アプリ"` をタップ、など。確実な方法を選び「決めたこと」に書く。戻せなかったら、合否とは別に警告の行(`row ... WARN`。既存に無ければ足す)を出す。
5. 合格の行の説明文を他の場面に合わせて足す。

## この Task でしないこと
- adb の実行。アプリのコードの変更。git のコミット等。

## 報告
変更点、`bash -n` の結果、**決めたこと**。
