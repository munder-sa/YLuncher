最初に tasks/RULES.md を読む。

# Task214 非同期pollを含めた比較beta2

あなたは実装担当。Task205の226件成功を確認し、UI更新/キャッシュ/履歴フィルタ/ProfileInstaller/セット番号IOと統合済み。app/panelhost両方をversionName 2.3.2-beta2 / versionCode53へ変更し、既存署名のreleaseをビルドする。単語描画候補は別プローブ検証中のため含めない。

許可ファイルはapp/build.gradle.ktsとpanelhost/build.gradle.ktsの版2項目のみ。依存/実装/テストは変更しない。署名情報を出力しない。ADB/Git操作禁止。

許可コマンド:
`/home/munde/tools/gradle-run.sh :app:assembleRelease :panelhost:assembleRelease`

1回成功後再実行なし。司令塔がその後、広い変更の統合確認として既存codex-verifyを1回実行し実機へ適用する。結果と決めたことを報告。
