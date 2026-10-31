# Task 088: アプリ一覧に「設定」ボタン、「許可が必要」から足りない許可へ(v1.6 A1・A2)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
v1.6 設計書 `docs/superpowers/specs/2026-10-31-v1.6-ux-design.md` の §3 A1・A2 と §2(共通の決まり)、計画 `docs/superpowers/plans/2026-10-31-v1.6-ux.md` の Task 088 を読むこと(この指示書が優先)。
- 今、設定はドックの「アプリ一覧」の長押しでしか開けない。
- 「許可が必要」の帯(`PanelContent.kt` の `PermissionBanner`、ホームのワイド表示にも同じ帯がある)は、通知アクセス・重ねて表示・使用状況アクセスのどれかが足りないと出るが、押すと Android のアプリ情報(`PanelScreen.kt` の `onOpenPermissions`)に飛ぶ。設定には「許可のチェック」(`SettingsRoute.Permissions`)がある。

## 変更するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/ui/apps/AppListScreen.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelContent.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelEnvironment.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelActivity.kt`
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeActivity.kt`
- `app/src/main/java/com/gt8d/launcher/settings/SettingsActivity.kt`
- `app/src/main/java/com/gt8d/launcher/settings/ListsScreen.kt`
- `app/src/test/java/com/gt8d/launcher/ui/apps/AppListScreenTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/PanelContentTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/PanelScreenTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/PanelEnvironmentTest.kt`
- `app/src/test/java/com/gt8d/launcher/settings/SettingsActivityTest.kt`(無ければ作らず、他のテストで確かめて報告)

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.ui.apps.*' --tests 'com.gt8d.launcher.ui.panel.*' --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.settings.*' --tests 'com.gt8d.launcher.ui.layout.*'`

## 仕様
1. **A1**: `AppListScreen` に引数 `onOpenSettings: (() -> Unit)? = null` を足す。null でなければ、上部の見出しの行の右端に「設定」ボタン(`Icons.Default.Settings` + 文字「設定」、72dp 以上、testTag `applist_settings`)。押すと `onOpenSettings()`。`PanelScreen` は今の `onOpenSettings`(ドックの長押しと同じ処理)を渡す(アプリ一覧を閉じてから開く)。
2. **A2**:
   - `PanelEnvironment` に `val missingPermissions: List<MissingPermission>`(`enum class MissingPermission { Notification, Overlay, UsageStats }`、この順)。`needsPermission` は `missingPermissions.isNotEmpty()` と同じ意味のまま残す。
   - 帯の文言: 1件なら Notification →「再生情報の表示に許可が必要です」、Overlay →「画面の端の操作に許可が必要です」、UsageStats →「アプリの切り替えの検知に許可が必要です」。2件以上なら「許可が N 件足りません」。0件なら帯を出さない(今と同じ)。文字は `Dims.BodyText`、1行(はみ出すときは省略)。ホームのワイド表示の帯も同じ文言。
   - 帯を押すと、設定の「許可のチェック」を開く: `PanelScreen` に `onOpenPermissionSettings: () -> Unit = {}` を足し、PanelActivity・HomeActivity は SettingsActivity を `putExtra("open_route", "permissions")` で開く(分割中の PanelActivity は、今の `settingsOpen` の仕組みで `SettingsRoute.Permissions` を開く)。`SettingsActivity` は `"permissions"` を `SettingsRoute.Permissions` に読み替える。Android のアプリ情報への移動はやめる(許可のチェックの画面から各設定へ行ける)。
3. 設定の「パネルとホーム」(`ListsScreen.kt`)の「右端バー」の文言を「ドック」に。

## テスト(足す。既存は弱めない。文言の変更に由来する期待値だけ合わせ、報告する)
- `AppListScreenTest`: `onOpenSettings` を渡すと `applist_settings` が 72dp 以上で表示され、押すと1回呼ばれる。渡さなければ無い。
- `PanelContentTest`: missing = [Notification] で「再生情報の表示に許可が必要です」、[Overlay, UsageStats] で「許可が 2 件足りません」、空なら帯が無い。帯を押すと `onOpenPermissions`(→ PanelScreen が `onOpenPermissionSettings`)。
- `PanelEnvironmentTest`: 3つの許可の有無から `missingPermissions` が正しい順で出る(既存の偽の仕組みに合わせる)。
- 設定が `"permissions"` で「許可のチェック」を開く(既存の SettingsActivity のテストに合わせて。無ければ報告)。

## この Task でしないこと
- 帯・ドック・カード・幕の変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、合わせた既存の期待値、**決めたこと**。
