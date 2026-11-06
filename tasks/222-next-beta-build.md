最初に tasks/RULES.md を読む。

# Task222 次段階比較用2.3.2-beta3ビルド

あなたは実装担当。ユーザー承認済みの効率化を実機で比較するためのbuild-only委譲。司令塔がこのworktreeを対照/候補のどちらかに固定している。アプリの機能・依存・署名は変更しない。

- app/panelhostのversionNameを **2.3.2-beta3**、versionCodeを **54** に揃える。変更は各defaultConfigの版2項目だけ。
- 既存release設定のまま両APKを1回buildする。ビルド成功後の再実行・テスト・ADB・Git・ネットワークでの新規ツール導入は禁止。署名値/鍵は出力しない。
- 対照と候補は同じ版で交互上書きできるようにし、司令塔がソースcommitとAPKハッシュで区別する。同じversionNameだけで同じ内容と報告しない。

許可コマンド:
`/home/munde/tools/gradle-run.sh :app:assembleRelease :panelhost:assembleRelease`

失敗が環境/依存なら原因を報告し停止。日本語で版差分・ビルド結果・決めたことを報告。実機導入は司令塔が行う。
