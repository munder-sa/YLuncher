# Task 045(計画の Task 18d): 設定「許可のチェック」と分割の方式の状態・「予備方式の準備」の入口

最初に `tasks/RULES.md` を読み、従うこと。仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` §6-6・§6-7・§7.1 に従う。
UI の決まり(計画の Global Constraints): 文言は日本語、ダーク固定、配色は `Palette`、タッチ領域は最小 72dp(`Dims.MinTouch`)、本文 20sp 以上(`Dims.BodyText`)。
**分割の右のパネル(最小幅 516dp)でも崩れない**こと。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

前提: Task 042〜044 で `settings/` ができている。まず `SettingsNav.kt`・`SettingsScreen.kt` と、044 の画面の書き方(偽物の渡し方)を読み、合わせる。

## 作成・変更するファイル(これで全部)
新規作成:
- `M/settings/Permissions.kt`(許可の項目と状態の読み取り、遷移先の Intent)
- `M/settings/PermissionsScreen.kt`(§6-6 の画面と、分割の方式の状態の欄)
- `M/settings/AppPairPrepScreen.kt`(§6-7。この Task では案内だけ)
- `T/settings/PermissionsTest.kt`
- `T/settings/PermissionsScreenTest.kt`
変更:
- `M/settings/SettingsScreen.kt`(Permissions・AppPairPrep のルートで上の画面を出す分岐だけ)

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.settings.*`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様

### Permissions.kt(パッケージ `com.gt8d.launcher.settings`)
```kotlin
enum class PermissionItem(val label: String) {
    Home("既定のホーム"),
    Notification("通知へのアクセス"),
    Overlay("他のアプリの上に表示"),
    Browser("既定のブラウザ"),
    Bluetooth("Bluetooth 接続"),
    UsageAccess("使用状況へのアクセス"),
    Accessibility("アクセシビリティ"),
}

// 表示する項目。Accessibility は方式が D(SplitMode.AppPair)のときだけ(計画の permissions_listsItems)
fun permissionItems(mode: SplitMode): List<PermissionItem>

interface PermissionChecker { fun granted(item: PermissionItem): Boolean }
class AndroidPermissionChecker(context: Context) : PermissionChecker
interface PermissionOpener { fun open(item: PermissionItem) }
class AndroidPermissionOpener(activity: Activity) : PermissionOpener
```
状態の読み取り(`AndroidPermissionChecker`):
- Home: `RoleManager.isRoleHeld(RoleManager.ROLE_HOME)`
- Notification: `NotificationManager.isNotificationListenerAccessGranted(ComponentName(context, MediaListenerService::class.java))`
- Overlay: `Settings.canDrawOverlays(context)`
- Browser: `RoleManager.isRoleHeld(RoleManager.ROLE_BROWSER)`
- Bluetooth: `checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PERMISSION_GRANTED`
- UsageAccess: 既存の `UsageStatsForegroundEvents(context).available()`
- Accessibility: D のサービスはまだ無い(Task 19)ので、常に false
- どれも例外は false として扱う。

遷移先(`AndroidPermissionOpener`。計画の Task 18 Step 3。`NEW_TASK` は付けない。Activity から開く。開けなければ DiagLog に記録して何もしない):
- Home: `Settings.ACTION_HOME_SETTINGS`
- Notification: `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`
- Overlay: `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`(データ `package:<自分>`)
- Browser: `RoleManager.createRequestRoleIntent(ROLE_BROWSER)` を `startActivity` で開く(結果は受け取らない)
- Bluetooth: `activity.requestPermissions(arrayOf(BLUETOOTH_CONNECT), 1)`
- UsageAccess: `Settings.ACTION_USAGE_ACCESS_SETTINGS`
- Accessibility: `Settings.ACTION_ACCESSIBILITY_SETTINGS`
- `AndroidPermissionOpener` に渡す Activity は、`SettingsHost` を表示している Activity(`SettingsActivity` または `PanelActivity`)。`LocalContext.current` から取り出してよい(Activity でなければ DiagLog に記録して何もしない)。

### PermissionsScreen.kt(§6-6)
- 項目ごとに1行: 名前、状態(「許可済み」/「未許可」)、未許可なら「設定を開く」(タグ `perm_open_<item>`。例 `perm_open_Overlay`)。行のタグ `perm_row_<item>`。
- 画面が再び前面に来たら(`Lifecycle.Event.ON_RESUME`)状態を読み直す(設定の画面から戻ったときに反映するため)。
- 下に「分割の方式」の欄:
  - Vendor:「メーカー方式で動作中」、LeftOnly:「分割が使えなくなりました」、AppPair:「予備方式で動作中」(タグ `split_status`)
  - Vendor 以外のとき「メーカー方式に戻す」(タグ `split_restore`)→ `SplitModeSelector.restoreVendor()` を呼び、表示を更新する。
  - 画面は `mode: () -> SplitMode`、`restoreVendor: () -> Unit` を引数で受ける(製品では `AppGraph.selector`)。
- 画面は `checker: PermissionChecker`、`opener: PermissionOpener` を引数で受ける(テストで偽物にする)。

### AppPairPrepScreen.kt(§6-7)
- この Task では案内だけ: 「予備方式の準備は、今後の更新で使えるようになります」(タグ `apppair_note`)。①〜③の手順の見出しだけを並べてよい。ペアの走査はしない。

## テスト(先に書き、修正前に失敗することを確かめる)
1. `T/settings/PermissionsTest.kt`(純粋な JUnit でよい):
   - 計画の名前 `permissions_listsItems`(Vendor・LeftOnly では Accessibility を含まない6項目がこの順、AppPair では7項目)
2. `T/settings/PermissionsScreenTest.kt`(Robolectric sdk 35 + Compose。`w516dp-h1000dp` で崩れないことを1つ以上):
   - 計画の名前 `splitStatus_showsMode_andRestoreButton`(3つの方式の文言。Vendor では「メーカー方式に戻す」が無い。LeftOnly で押すと `restoreVendor` が1回呼ばれ、表示が「メーカー方式で動作中」になる)
   - `granted_showsGranted_noOpenButton`、`notGranted_open_callsOpener`、`resume_rereadsState`(偽物の状態を変えてから ON_RESUME → 表示が変わる)
   - `appPairPrep_showsNote`
3. `AndroidPermissionChecker` の読み取りは、Robolectric で作れる範囲(Overlay・Bluetooth・UsageAccess)だけテストを足す: `checker_overlay`、`checker_bluetooth`、`checker_usageAccess`。
   RoleManager・通知リスナーの状態が Robolectric で作れない場合は、推測で作らずテストを足さず、報告に「確かめられなかった」と書く。

## 手順(TDD)
1. 上のテストを書く。2. 対象テストを実行し、修正前に失敗することを確かめる(どのテストがどう失敗したかを報告に書く)。
3. 実装する。4. 対象テストが全部通るまで直す(最大3回)。通ったら `assembleDebug`。

## この Task でしないこと
- D(アプリペア)のサービス・走査・自動切り替え(Task 19)。`SplitModeSelector` の中身の変更。マニフェストの変更。
- 全テストの実行、実機の操作、git のコミット等。

## 報告
実行したコマンドと結果(件数・失敗件数、修正前の失敗の内容、assembleDebug)、作成・変更したファイル、**決めたこと**。
