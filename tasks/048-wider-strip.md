# Task 048: スワイプ帯の既定の幅を広げる(8px → 48px)

最初に `tasks/RULES.md` を読み、従うこと。仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` §8「操作」(2026-10-31 に書き換え済み)に従う。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

## 背景
ユーザーが実機で「スワイプできる範囲がかなり小さい」と言った。帯の幅の既定 8px(約 5dp)を 48px(約 32dp)にし、設定の選択肢も広げる。

## 変更するファイル(これで全部)
- `M/config/Model.kt`(`GestureConfig.stripWidthPx` の既定値を 48 にするだけ)
- `M/settings/DailySettings.kt`(`stripWidthChoices` を `listOf(24, 48, 72, 96)` にするだけ)
- `T/config/DefaultsTest.kt`(既定値の期待を `stripWidthPx = 48` にする1行だけ。司令塔が許可した仕様の変更)
- `T/settings/DailyScreensTest.kt`(選択肢を並べている所を新しい選択肢 `24, 48, 72, 96` に合わせるだけ。テストの意図と名前は変えない。司令塔が許可した仕様の変更)

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.config.* --tests com.gt8d.launcher.settings.* --tests com.gt8d.launcher.edge.*`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様
- 既に保存されている設定(8px など)は書き換えない(移行はしない)。保存値が選択肢に無いときは、どれも選択中にしない今の動きのまま。
- ほかの値・処理は変えない。

## 手順
1. テストを直す(期待値を新しい仕様に)。2. 対象テストを実行し、修正前に失敗することを確かめる。3. 実装する。4. 対象テストが全部通ったら `assembleDebug`。

## この Task でしないこと
- 帯の判定(`EdgeGesture`)、帯の窓の作り、パネル・ホームの配置の変更。全テストの実行、実機の操作、git のコミット等。

## 報告
実行したコマンドと結果(件数・失敗件数、修正前の失敗の内容、assembleDebug)、変更したファイル、**決めたこと**。
