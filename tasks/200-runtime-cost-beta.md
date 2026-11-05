最初に tasks/RULES.md を読み、従う。

# Task200 通知・履歴改善の確認用beta3

実装担当。司令塔がレビューし有効と判断した変更の確認APKを作る。ユーザーは改善の試行・適用を承認済み。機能やテストを変えず、以下の版2ファイルだけを更新する。

- app/build.gradle.kts: defaultConfigを2.3.1-beta2 / 49から **2.3.1-beta3 / 50**。
- panelhost/build.gradle.kts: 同じく **2.3.1-beta3 / 50**。

署名/依存/最適化条件は変更しない。鍵やkeystore.propertiesを表示しない。対象の成功済みテストを再実行しない。実行できる変更コマンドは次の1つだけ。

`/home/munde/tools/gradle-run.sh :app:assembleRelease :panelhost:assembleRelease`

releaseビルド1回。失敗時は原因を報告し、勝手に依存/検査を変えない。変更、APKパス、結果、決めたことを報告。署名/実機更新と比較は司令塔。正式化/main取り込み/push/公開はしない。
