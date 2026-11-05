最初に tasks/RULES.md を読み、従う。

# Task 196 描画比較用betaのAPK作成（候補A: Task193）

利用者は描画軽量化計画の5までの実行を承認した。対照Aは保存済み。Task193の57件成功後、候補Aをビルドする。アプリ/テスト/版/依存/最適化設定は変更しない。

## 変更してよいファイル

なし。両方の **2.3.1-beta1 / 48** を維持する。署名鍵やkeystore.propertiesを表示しない。

## 手順

1. Task193の変更と版2か所を読み取り確認する。変更しない。
2. 下記releaseビルドを1回実行する。単体テスト/assembleDebug/全検証は不要（機能変更なし）。失敗なら依存/検査を変更せず報告する。
3. APKパス、版、実行コマンド/結果、決めたことを報告する。署名/実機更新/保存・比較は司令塔担当。

## 実行できるコマンド

- 状態を変えない読み取り。
- `/home/munde/tools/gradle-run.sh :app:assembleRelease :panelhost:assembleRelease`

司令塔が終了後、同署名/版を確認してbuild/perf-lyrics/controlの下へ対照Aを保存する。後の機能候補は同じ比較用版/署名でビルドし、候補名とSHAで識別する。対照と候補の交互更新にrelease APKのダウングレードを用いない。
