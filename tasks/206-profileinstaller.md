最初に tasks/RULES.md を読む。

# Task206 ProfileInstallerのAPI35対応

ユーザー承認済みの依存更新。gradle/libs.versions.tomlのprofileinstallerだけ1.3.1から1.4.1へ更新する。他の依存・版・プロファイル自体は変更しない。公式 https://developer.android.com/jetpack/androidx/releases/profileinstaller を司令塔が確認済み。実機INSTALL_PROFILE result3は旧依存でのUNSUPPORTED_ART_VERSION。

許可ファイルはgradle/libs.versions.tomlのみ。検証ビルドと実機profile/ART確認は司令塔が統合時に行うため、このTaskではGradle/ADB/Git/インストールは実行しない。差分と決定事項を報告する。
