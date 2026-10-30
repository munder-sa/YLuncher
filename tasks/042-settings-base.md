# Task 042(計画の Task 18a): 設定画面の土台と行き来・安全モードからの復旧

最初に `tasks/RULES.md` を読み、従うこと。仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` §6・§7.2・§8 に従う。
UI の決まり(計画の Global Constraints): 文言は日本語、ダーク固定、配色は `Palette`、タッチ領域は最小 72dp(`Dims.MinTouch`)、本文 20sp 以上(`Dims.BodyText`)。
**分割の右のパネル(最小幅 516dp)でも崩れない**こと。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

## ユーザーが決めたこと(2026-10-30)
- 設定画面は 4 本に分けて作る。この Task は 1 本目(土台・行き来)。§6 の 1〜7 の詳細画面は後の Task で作るので、ここでは「準備中」の画面にする。
- **分割の中(パネル)で設定を開いたら、パネルの画面の中に表示する**(分割の右のブラウザと同じ方式。Task 041)。分割の外(ホーム)では `SettingsActivity` を全画面で開く。
- **セットを開いている最中(`AppGraph.runner.opening.value != null`)は、設定の入口を効かせない**(押しても何もしない。DiagLog に記録)。
- **安全モード(SafetyGuard の退避)中は、パネル(ランチャーの入口「GT8D パネル」)に復旧用の画面を出す**。解除したら通常のホームへ戻す。

## 作成・変更するファイル(これで全部)
新規作成:
- `M/settings/SettingsNav.kt`
- `M/settings/SettingsScreen.kt`
- `M/settings/SettingsActivity.kt`
- `M/settings/SafetyRecoveryScreen.kt`
- `T/settings/SettingsNavTest.kt`
- `T/settings/SettingsScreenTest.kt`
- `T/settings/SettingsActivityTest.kt`
変更:
- `M/ui/panel/PanelScreen.kt`(引数 `onOpenSettings` を足し、`onAppListLongPress` につなぐだけ)
- `M/ui/panel/PanelActivity.kt`
- `M/ui/home/HomeActivity.kt`
- `app/src/main/AndroidManifest.xml`(`SettingsActivity` の宣言を足すだけ)
- `T/ui/panel/PanelActivityTest.kt`、`T/ui/home/HomeActivityTest.kt`、`T/ui/home/HomeActivityUiTest.kt`(テストを足す。既存のテストは変えない。`PanelScreen` の引数が増えてコンパイルが通らない箇所だけは、引数を足して直してよい)

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.settings.* --tests com.gt8d.launcher.ui.panel.PanelActivityTest --tests com.gt8d.launcher.ui.panel.PanelScreenTest --tests com.gt8d.launcher.ui.home.HomeActivityTest --tests com.gt8d.launcher.ui.home.HomeActivityUiTest`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様

### SettingsNav.kt(パッケージ `com.gt8d.launcher.settings`。純粋な Kotlin)
```kotlin
sealed interface SettingsRoute {
    data object Root : SettingsRoute
    data object Sets : SettingsRoute          // §6-1(Task 18b)
    data object Lists : SettingsRoute         // §6-2(Task 18c)
    data object Link : SettingsRoute          // §6-3(Task 18c)
    data object Gesture : SettingsRoute       // §6-4(Task 18c)
    data object Boot : SettingsRoute          // §6-5(Task 18c)
    data object Permissions : SettingsRoute   // §6-6(Task 18d)
    data object AppPairPrep : SettingsRoute   // §6-7(Task 18d・19)
}

data class SettingsNav(val stack: List<SettingsRoute> = listOf(SettingsRoute.Root)) {
    val current: SettingsRoute get() = stack.last()
    fun push(route: SettingsRoute): SettingsNav
    fun pop(): SettingsNav?   // Root だけのときは null(= 設定を閉じる)
}
```

### SettingsScreen.kt
```kotlin
class SettingsActions(
    val resetToDefaults: () -> Unit,    // ConfigRepository.reset()
    val openHomeChooser: () -> Unit,    // 既定のホームの選択画面(Settings.ACTION_HOME_SETTINGS、NEW_TASK)
    val safetyActive: () -> Boolean,    // SafetyGuard.isFallbackActive
    val clearSafety: () -> Unit,        // safetyRecover(context)(下の SafetyRecoveryScreen.kt)
    val showAppPairPrep: () -> Boolean, // AppGraph.selector.current() != SplitMode.Vendor
) {
    companion object { fun forApp(context: Context): SettingsActions }  // 製品用
}

@Composable
fun SettingsHost(
    actions: SettingsActions,
    onExit: () -> Unit,           // 設定を閉じる
    onSafetyCleared: () -> Unit,  // 安全モードを解除した後(ホストが通常のホームへ移す)
    modifier: Modifier = Modifier,
)
```
- `forApp` の `resetToDefaults`: `SettingsScreen.kt` のトップレベルに `private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` を置き、
  `settingsScope.launch { runCatching { AppGraph.config.reset() }.onFailure { DiagLog.d("Settings", "reset failed: $it") } }`。例外を外へ投げない。
- `SettingsNav` は `SettingsHost` の中で `remember` で持つ。
- 項目名は省略・切り詰めをしない(`maxLines`・`TextOverflow.Ellipsis` を付けない。長ければ折り返す)。
- 上のバー: 「戻る」(タグ `settings_back`。`pop()`、null なら `onExit()`)、題名(Root は「設定」、詳細は項目名)、「閉じる」(タグ `settings_close`。`onExit()`)。
  システムの「戻る」(`BackHandler`)は「戻る」ボタンと同じ。
- Root: §6 の順に縦の一覧(スクロール可)。項目名とタグ:
  1. 画面セット `settings_item_sets` → Sets
  2. パネルとホーム `settings_item_lists` → Lists
  3. リンクの行き先 `settings_item_link` → Link
  4. 操作 `settings_item_gesture` → Gesture
  5. 起動時 `settings_item_boot` → Boot
  6. 許可のチェック `settings_item_permissions` → Permissions
  7. 予備方式の準備 `settings_item_apppair` → AppPairPrep(**`showAppPairPrep()` が true のときだけ表示**。仕様 §6-7「E が使えないときに表示」)
  8. 純正ランチャーに戻す `settings_item_stock` → `openHomeChooser()`
  9. デフォルトに戻す `settings_item_reset` → 確認ダイアログ「設定をデフォルトに戻しますか?」(「はい」タグ `settings_reset_yes`、「いいえ」タグ `settings_reset_no`)。「はい」で `resetToDefaults()` を1回。「いいえ」では呼ばない
  10. 安全モードを解除 `settings_item_safety` → `safetyActive()` が true なら `clearSafety()` の後に `onSafetyCleared()`。false なら押せない表示(無効)で、副題「安全モードではありません」
- 1〜7 の詳細は、この Task では「この画面は準備中です」(タグ `settings_placeholder`)だけを出す。

### SafetyRecoveryScreen.kt
```kotlin
// 安全モードを解除する。起動時の自動処理(前回のセットを開く)を、解除の直後に走らせない
fun safetyRecover(context: Context) { SafetyGuard.clear(context); AppGraph.bootHandled = true }

@Composable
fun SafetyRecoveryScreen(onRecover: () -> Unit, onStock: () -> Unit, modifier: Modifier = Modifier)
```
- 文言「安全のため純正ランチャーを使っています」。ボタン「安全モードを解除してホームへ」(タグ `safety_recover`)、「純正ランチャーに戻る」(タグ `safety_stock`)。

### SettingsActivity.kt
- 全画面の Activity。`LauncherTheme { SettingsHost(SettingsActions.forApp(this), onExit = ::finish, onSafetyCleared = { ホームへ; finish() }) }`。
- 「ホームへ」= `Intent(this, HomeActivity::class.java)` に `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP`(PanelActivity の転送と同じ)。
- マニフェスト: `android:exported="false"`、`android:resizeableActivity="true"`。テーマは指定しない(アプリの既定 NoActionBar)。

### PanelScreen.kt
- 引数 `onOpenSettings: () -> Unit = {}` を **`onLaunchComponent` と `onLaunchApp` の間**に足し(既存の呼び出しは最後の引数 `onLaunchApp` を末尾ラムダで渡しているため、最後に置かない)、`onAppListLongPress = onOpenSettings` にする。ほかは変えない。

### PanelActivity.kt
1. **設定の表示**: 状態 `settingsOpen: Boolean`(`mutableStateOf`)。テスト用に `internal val settingsShown: Boolean get() = settingsOpen`。
   - `PanelScreen` の `onOpenSettings`: `AppGraph.runner.opening.value != null` なら何もしない(DiagLog)。分割の中(`isInMultiWindowMode`)なら `settingsOpen = true`。
     分割の外なら `SettingsActivity` を起動する。
   - 表示の重なり: ブラウザ(Task 041 の `browserUrl`)があればブラウザ。無くて `settingsOpen` なら、壁紙の上に `SettingsHost(..., onExit = { settingsOpen = false }, onSafetyCleared = { ホームへ; finish() })`。どちらも無ければ今の `PanelScreen`。
   - `onSaveInstanceState` で `settingsOpen` を保存し、`onCreate` で戻す(画面の中の位置は Root に戻ってよい)。
   - 設定を表示中にブラウザが開き、閉じられたら、設定の Root に戻る(設定の中の位置は保たなくてよい)。
   - 設定を表示中に分割の外へ出たら、今の転送の規則のまま(印が無ければ 1.5 秒後にホームへ転送して閉じる)。設定のためだけに転送を止めない。
   - 設定を表示中も、外側の Box の二本指スワイプはそのまま効く(ブラウザ表示中と同じ)。
2. **安全モード**: `onCreate` で `SafetyGuard.isFallbackActive(this)` を読み、フィールド `safetyMode` に持つ。true なら:
   - 画面は `SafetyRecoveryScreen` だけ(壁紙・PanelScreen・設定・ブラウザを出さない)。
     `onRecover = { safetyRecover(this); ホームへ; finish() }`、`onStock = { runCatching { startActivity(stockLauncherIntent()) }; finish() }`。
   - `onResume`・`onMultiWindowModeChanged` では、`AppGraph.panelInSplit` 等のフィールドの更新だけ今のまま行い、
     **転送(`forwarder.onShown`/`onMultiWindowChanged`)、`EdgeStripService.ensureRunning`、`runner.panelResumedInSplit` をしない**。
   - `consumePendingUrl()` の先頭で `safetyMode` なら何もしない(`onCreate` の `runner.opening` の監視から呼ばれる経路も止まる。保留の URL は取り出さない)。
   - 既に動いている `EdgeStripService` を止める処理は足さない(サービス自身が安全モードを見て止まるため。サービスは変えない)。
   - 「戻る」は今のまま(何もしない)。

### HomeActivity.kt
1. `PanelScreen` の `onOpenSettings`: `AppGraph.runner.opening.value != null` なら何もしない(DiagLog)。それ以外は `SettingsActivity` を起動する。
2. HomeActivity は `singleTask` なので、安全モード中に作られた画面(「安全のため純正ランチャーを使っています」)が残ったまま解除されることがある。
   `onCreate` で安全モードだったかをフィールドに持ち、`onResume` で「安全モード中に作られた」かつ「今は安全モードでない」なら `recreate()` して、その回の `onResume` の残りの処理はしない。

## テスト(先に書き、修正前に失敗することを確かめる)
1. `T/settings/SettingsNavTest.kt`(純粋な JUnit): `start_isRoot`、`push_thenPop_returnsToRoot`、`popAtRoot_isNull`。
2. `T/settings/SettingsScreenTest.kt`(Robolectric sdk 35 + Compose。`SettingsActions` は記録用の偽物を渡す。画面の幅は `w516dp-h1000dp` と `w1280dp-h1000dp` の両方で確かめるテストを1つ以上):
   - `root_listsItemsInSpecOrder`(1〜6・8〜10 がこの順で出る。`showAppPairPrep=false` のとき 7 は無い)、`root_showsAppPairPrep_whenNotVendor`
   - `reset_requiresConfirm`(計画の名前。押しただけでは呼ばれず、「はい」で1回。別のテストで「いいえ」なら0回: `reset_no_doesNotReset`)
   - `stock_opensHomeChooser`
   - `safety_inactive_isDisabled`、`safety_active_clearsThenCallsCleared`(順序も確かめる)
   - `detail_showsPlaceholder_andBackReturnsToRoot`、`back_atRoot_exits`、`systemBack_atRoot_exits`、`close_inDetail_exits`
   - `narrowPanel_itemsFit`(`w516dp` で全項目の項目名が切れずに出る。`assertIsDisplayed` をスクロールしながら)
3. `T/settings/SettingsActivityTest.kt`(Robolectric): `close_finishes`、`safetyCleared_startsHome_andFinishes`(安全モードの状態は `SafetyGuard` のファイルで作る。既存の `SafetyGuardTest`・`HomeActivityTest` の作り方に合わせる)。
4. `T/ui/panel/PanelActivityTest.kt` に足す:
   - `longPressAppList_inSplit_showsSettingsInPanel`(Activity は起動されない)、`longPressAppList_outsideSplit_startsSettingsActivity`、
     `longPressAppList_whileOpening_doesNothing`、`settings_close_returnsToPanel`、`settings_survivesRecreate`、
     `safetyMode_showsRecovery_andDoesNotForward`(1.5 秒以上進めても HomeActivity へ転送されず、`isFinishing == false`)、
     `safetyMode_recover_clearsAndStartsHome`、`safetyMode_stock_startsStockLauncher`。
     `safetyMode_doesNotConsumePendingUrl`(安全モードで分割の中、`pendingUrl` あり、opening が null に変わる経路を通しても `pendingUrl.take()` が残っている・`shownBrowserUrl == null`)、
     `settings_browserOpenedThenClosed_returnsToSettingsRoot`、`settings_leaveSplit_forwardsToHomeAfterWait`(既存の転送のテストの作り方に合わせる)。
   - 各テストで安全モードのファイル・AppGraph のフィールドを元に戻す。
5. ホームのテスト: `longPressAppList_startsSettingsActivity`、`longPressAppList_whileOpening_doesNothing`、`createdInSafety_thenCleared_recreatesOnResume`。
   長押しの2件は Compose の規則がある `T/ui/home/HomeActivityUiTest.kt` に置いてよい(その書き方に合わせる)。`createdInSafety_thenCleared_recreatesOnResume` は
   「安全モードの表示が消え、通常のホーム(例: `dock_applist`)が出る」「`safetyRecover` の後なので `AppGraph.bootHandled == true` で、起動の自動処理(`AppGraph.boot.start`)が走らない」まで確かめる。
6. `T/settings/SettingsActivityTest.kt` に `forApp_reset_restoresDefaults_keepsLastSetIndex`(製品用の `SettingsActions.forApp` で reset → 設定が `Defaults.build(...)` と同じになり、`AppGraph.config.lastSetIndex` は変わらない。非同期なので完了を待つ)。

## 実機で確認すること(司令塔が行う。codex はしない)
- 分割の右のパネルでの設定の見た目、ホーム選択画面への遷移、安全モードの解除からホームへの戻り。

## 手順(TDD)
1. 上のテストを書く。2. 対象テストを実行し、修正前に失敗することを確かめる(どのテストがどう失敗したかを報告に書く)。
3. 実装する。4. 対象テストが全部通るまで直す(最大3回)。通ったら `assembleDebug`。

## この Task でしないこと
- §6 の 1〜7 の詳細画面、「分割」ボタン(新しいセットの作成)、パネルの「+」の接続(後の Task)。
- LinkRouter・SetRunner・SafetyGuard の判定の変更。ブラウザの変更。
- 全テストの実行、実機の操作、git のコミット等。

## 報告
実行したコマンドと結果(件数・失敗件数、修正前の失敗の内容、assembleDebug)、作成・変更したファイル、**決めたこと**。
