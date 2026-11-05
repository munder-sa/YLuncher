最初に tasks/RULES.md を読み、従う。

# Task 196 比較後の確認用beta2へ更新・APK作成

利用者は描画軽量化計画の5までの実行を承認した。対照/実験APKは保存済み。司令塔が採用変更だけを統合した後に実行する、確認用ベータの版変更/ビルド。アプリ機能/テスト/依存/最適化設定は変更しない。

## 変更してよいファイル

- app/build.gradle.kts
- panelhost/build.gradle.kts

両方のdefaultConfigを **2.3.1-beta1 / 48 → 2.3.1-beta2 / 49** に更新する。版以外は変更しない。署名鍵やkeystore.propertiesを表示しない。

## 手順

1. HANDOFF/比較結果と実際のGit状態を読み、両版だけを更新して差分を照合する。
2. 下記releaseビルドを1回実行する。単体テスト/assembleDebug/全検証は不要（機能変更なし）。失敗なら依存/検査を変更せず報告する。
3. APKパス、版、実行コマンド/結果、決めたことを報告する。署名/実機更新/保存・比較は司令塔担当。

## 実行できるコマンド

- 状態を変えない読み取り。
- `/home/munde/tools/gradle-run.sh :app:assembleRelease :panelhost:assembleRelease`

司令塔が終了後、同署名/版を確認してbuild/perf-lyrics/final-beta2へ保存し、両方を実機へ上書きする。実験版48との交互比較は既に終え、以後この49を確認用とする。正式版、main取り込み、push、公開はこのTaskに含まない。
