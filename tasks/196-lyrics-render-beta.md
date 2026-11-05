最初に tasks/RULES.md を読み、従う。

# Task 196 描画比較用betaの版更新・APK作成（対照A）

利用者は描画軽量化計画の実行を承認した。これは機能変更前の対照APK作成。アプリ/テスト/依存/最適化設定は変更しない。

## 変更してよいファイル

- app/build.gradle.kts
- panelhost/build.gradle.kts

両方のdefaultConfigのversionNameを2.3.0から **2.3.1-beta1**、versionCodeを47から **48** に変更する。それ以外を変更しない。署名鍵やkeystore.propertiesを表示しない。

## 手順

1. 版2か所だけ変更して差分照合する。
2. 下記releaseビルドを1回実行する。単体テスト/assembleDebug/全検証は不要（機能変更なし）。失敗なら依存/検査を変更せず報告する。
3. APKパス、版、実行コマンド/結果、決めたことを報告する。署名/実機更新/保存・比較は司令塔担当。

## 実行できるコマンド

- 状態を変えない読み取り。
- `/home/munde/tools/gradle-run.sh :app:assembleRelease :panelhost:assembleRelease`

司令塔が終了後、同署名/版を確認してbuild/perf-lyrics/controlの下へ対照Aを保存する。後の機能候補は同じ比較用版/署名でビルドし、候補名とSHAで識別する。対照と候補の交互更新にrelease APKのダウングレードを用いない。
