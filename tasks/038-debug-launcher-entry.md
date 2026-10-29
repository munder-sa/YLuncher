# Task 038: デバッグ版の実験用画面を、ランチャーの入口から外す

最初に `tasks/RULES.md` を読み、従うこと。

## 背景(司令塔が実機で確認した事実)
メーカーの分割入口 E にはパッケージ名しか渡せず、E はそのパッケージの「MAIN/LAUNCHER の画面」を開く。
デバッグ版では `app/src/debug/AndroidManifest.xml` の `.spike.SpikeActivity`・`.spike.SpikePanelActivity` に
MAIN/LAUNCHER の intent-filter が残っており、これが本体の `PanelActivity`(MAIN/LAUNCHER、仕様 §5.2「自作アプリのランチャー用の入口」)より先に選ばれる。
その結果、デバッグ版では「アプリ|パネル」の分割の右に、パネルではなく SpikeActivity が出る(2026-10-30 に GT8D で確認)。

## 作成・変更するファイル(これで全部)
変更:
- `app/src/debug/AndroidManifest.xml`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様
- `.spike.SpikeActivity` と `.spike.SpikePanelActivity` から、`android.intent.category.LAUNCHER` を含む intent-filter(MAIN/LAUNCHER)だけを取り除く。
- `SpikeActivity` の HOME の intent-filter も取り除く(実験は終わっており、既定のホームの候補に出ないようにする)。
- `android:exported="true"` はそのまま残す(adb の `am start -n` で引き続き開けるように)。ほかの属性・ほかの要素・Spike のコードは変えない。
- 各 activity の直前に、XML コメントで1行「E は MAIN/LAUNCHER の画面を開くので、実験用の画面にはランチャーの入口を付けない(本体の PanelActivity が選ばれるように)」と書く。

## 手順
1. 変更する。
2. `assembleDebug` を実行し、成功を確かめる。
3. 生成されたマージ済みの Manifest(`app/build/intermediates/merged_manifests/debug/` 以下などにある AndroidManifest.xml。場所は `rg --files app/build/intermediates | rg merged_manifest` で探してよい)を読み、MAIN/LAUNCHER を持つ activity が `.ui.panel.PanelActivity` だけであることを確かめて、報告に書く。

## この Task でしないこと
- 本体の Manifest、Spike のコード、テストの変更。全テストの実行。

## 報告
実行したコマンドと結果、変更内容、マージ済み Manifest の確認結果、**決めたこと**。
