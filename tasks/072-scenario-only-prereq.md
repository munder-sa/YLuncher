# Task 072: 場面テストの `--only` で前提の照合がずれる不具合を直す

最初に `tasks/RULES.md` を読み、従うこと。**adb は実行しない。**

## 背景(司令塔が実機で確かめた事実)
`bash scripts/device-scenarios.sh --with-ylauncher-home --only set_panelCard_switch` で、純正ホームの側だけ `set_strip_coldPanel@prerequisite` が PASS なのに `set_panelCard_switch` が「前提の set_strip_coldPanel が不合格」で SKIP になる。`run()` の `local ... version=${3:-$2}` が、前提の実行で渡す空の第3引数 `''` を「未指定」とみなして `version=@prerequisite` にするため、`last_suffix` が本番の `''` と一致しない。`@ylhome` 側は第3引数が空でないので起きない。`link_appPanelFront` の `--only` でも同じ。

## 変更するファイル(これで全部)
- `scripts/device-scenarios.sh`

使ってよいコマンド:
- `bash -n scripts/device-scenarios.sh`

## 仕様
1. 第3引数が**渡されたときは空でもそれを使う**(`${3-$2}`)。
2. 他に同じ `:-` の取り違えが無いか、スクリプト全体を確かめ、あれば報告する(直すのはこの1か所だけ。他は報告のみ)。

## 報告
変更点、`bash -n` の結果、**決めたこと**。
