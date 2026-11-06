最初に tasks/RULES.md を読む。

# Task209 先行改善の実機比較用beta1

あなたは実装担当。承認済み7候補のうち、検証済みの表示中UI・キャッシュ・履歴絞込み・セット番号IOとProfileInstallerを実機で比較するため、app/panelhost両方をversionName 2.3.2-beta1 / versionCode52へ変更する。既存署名を使いreleaseビルドする。Task205の非同期pollとTask208 GPU候補はまだ含まない。

許可ファイルはapp/build.gradle.ktsとpanelhost/build.gradle.ktsの版2項目のみ。依存1.4.1の変更はTask206で済んでいるため触らない。署名情報を出力しない。ビルド以外のテスト/ADB/Git操作なし。

許可コマンド:
`/home/munde/tools/gradle-run.sh :app:assembleRelease :panelhost:assembleRelease`

1回成功後の再実行なし。失敗が依存取得/環境なら状態を報告し停止。実装差分/ビルド結果を報告。
