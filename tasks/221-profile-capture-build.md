最初に tasks/RULES.md を読む。

# Task221 手動Baseline Profile収録専用ビルド

あなたは実装担当。実収録profile候補のユーザー承認内。司令塔が、全改善の統合後にTask222で版54/2.3.2-beta3へ揃えたソースから専用worktreeを用意する。この差分は本体へ取り込まない。

- appのreleaseのisMinifyEnabledとisShrinkResourcesだけfalseにする。non-debuggable/既存署名/applicationId/manifest/依存/版は維持する。新しいbuildTypeや条件付き本体設定を追加しない。
- 非R8かつnon-debuggableの収録用app APKを1回だけbuild。panelhostは既に同版の最適化済みAPKがあるため再buildしない。認証/設定は変更しない。
- 本体へのprofile追加、端末のcompile/profileリセット、収録/復元は司令塔の別工程。未収録のprofile行を作らない。現在のbaseline-prof.txtはそのまま、収録時に司令塔がProfileInstallerの自動取込みを一時停止する。

許可コマンド:
`/home/munde/tools/gradle-run.sh :app:assembleRelease`

変更はapp/build.gradle.ktsの2flagだけ、テスト/ADB/Git/ツール導入なし。build成功後は再実行しない。非R8の専用成果物であること、未収録・未検証を日本語で報告。
