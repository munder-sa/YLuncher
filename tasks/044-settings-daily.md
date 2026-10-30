# Task 044(計画の Task 18c): 設定「パネルとホーム」「リンクの行き先」「操作」「起動時」

最初に `tasks/RULES.md` を読み、従うこと。仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` §4.4・§4.6・§4.7・§6-2〜5・§8 に従う。
UI の決まり(計画の Global Constraints): 文言は日本語、ダーク固定、配色は `Palette`、タッチ領域は最小 72dp(`Dims.MinTouch`)、本文 20sp 以上(`Dims.BodyText`)。
**分割の右のパネル(最小幅 516dp)でも崩れない**こと。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

前提: Task 042・043 で `settings/` の土台(`SettingsNav`・`SettingsHost`・`SettingsActivity`)と「画面セット」ができている。まずそれらを読み、同じ書き方に合わせる
(上のバー、ルートの分岐、テストの偽物の渡し方、アプリの選択一覧)。043 のアプリの選択一覧を使い回せるなら使い回す(使い回すために 043 のファイルを変える必要があるなら、変えずに同じ形のものをこの Task のファイルに作り、「決めたこと」に書く)。

## ユーザーと司令塔が決めたこと(2026-10-30)
- この画面の設定は、**変更したらすぐ保存し、すぐ反映する**(「保存」ボタンを置かない。§6-1 の画面セットだけが「保存」方式)。
- **帯の幅と長押しの時間は、少数の選択肢から選ぶ**:
  - 帯の幅: 8px・16px・24px・32px(既定 8px)
  - 長押しの時間: 500ms・750ms・1000ms・1500ms(既定 1000ms)。表示は「0.5秒」「0.75秒」「1秒」「1.5秒」
  - 保存されている値が選択肢に無いとき(手で書き換えた等)は、どれも選択中にしない(値は変えない)。
- **「車との接続を待ってから前回のセットを開く」は、この Task では切り替えを無効の表示にし**、「車での確認の後に使えるようになります」の案内を出す(値は変えない。§4.8-6 の検知方法が未定のため)。

## 作成・変更するファイル(これで全部)
新規作成:
- `M/settings/DailySettings.kt`(純粋な Kotlin。一覧の編集の規則と選択肢)
- `M/settings/ListsScreen.kt`(§6-2)
- `M/settings/LinkScreen.kt`(§6-3)
- `M/settings/GestureScreen.kt`(§6-4)
- `M/settings/BootSettingsScreen.kt`(§6-5)
- `T/settings/DailySettingsTest.kt`
- `T/settings/DailyScreensTest.kt`
- `T/edge/EdgeStripWidthTest.kt`
変更:
- `M/settings/SettingsScreen.kt`(Lists・Link・Gesture・Boot のルートで上の画面を出す分岐だけ)
- `M/settings/SettingsNav.kt`(必要ならルートを足すだけ。既存のルートは変えない)
- `M/edge/EdgeStripService.kt`(帯の幅の即時反映だけ。下の「帯の幅の反映」)
- `M/AppGraph.kt`(`EdgeStripService.gestureConfig = cfg.gesture` の1行を `EdgeStripService.applyGesture(cfg.gesture)` にするだけ)
- `M/ui/panel/PanelScreen.kt`(パネルの「+」(`onAdd`)を、設定の「パネルとホーム」を開く経路につなぐだけ。下の「パネルの『+』」)
- `M/ui/panel/PanelActivity.kt`、`M/ui/home/HomeActivity.kt`(「+」の接続だけ)
- `M/settings/SettingsActivity.kt`(extra `open_route=lists` で Lists から始めるだけ)
- `T/ui/panel/PanelActivityTest.kt`(テストを足すだけ。既存のテストは変えない)

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.settings.* --tests com.gt8d.launcher.edge.EdgeStripWidthTest --tests com.gt8d.launcher.edge.EdgeStripServiceTest --tests com.gt8d.launcher.ui.panel.PanelActivityTest --tests com.gt8d.launcher.ui.panel.PanelScreenTest`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様

### DailySettings.kt(パッケージ `com.gt8d.launcher.settings`。Android に依存しない)
```kotlin
object DailySettings {
    val stripWidthChoices = listOf(8, 16, 24, 32)
    val longPressChoices = listOf(500, 750, 1000, 1500)
    fun longPressLabel(ms: Int): String               // 500→"0.5秒"、750→"0.75秒"、1000→"1秒"、1500→"1.5秒"

    fun add(list: List<String>, pkg: String, max: Int?): List<String>   // 既にあれば変えない。max に達していれば変えない
    fun remove(list: List<String>, pkg: String): List<String>
    fun moveUp(list: List<String>, pkg: String): List<String>
    fun moveDown(list: List<String>, pkg: String): List<String>
    fun normalizeDomain(input: String): String?        // 前後の空白を除き小文字に。"https://"・"http://"・末尾の "/" 以降を除く。空や空白を含むものは null
}
```
- 上限は `config/Model.kt` の `MAX_PANEL_APPS`(5)・`MAX_DOCK_APPS`(4)。ホームのアイコン一覧は上限なし(`max = null`)。

### ListsScreen.kt(§6-2「パネルとホーム」)
4つの区分を縦に並べる: 「ホームのアイコン」(`homeApps`)、「パネルのアイコン」(`panelApps`、最大5)、「右端バーのよく使うアプリ」(`dockApps`、最大4)、「停止中に表示するアプリ」(`idleApp`、1つ)。
- 一覧の3つ: 各アプリの行に ▲・▼・削除(確認なし)。末尾に「追加」(アプリの選択一覧。既に入っているものは選べない表示)。上限に達したら「追加」を無効にし、「最大 N 個まで」と出す。
- 停止中に表示するアプリ: 今のアプリ名と「変更」(アプリの選択一覧から1つ)。
- タグ: 区分ごとに接頭辞 `lists_home_`・`lists_panel_`・`lists_dock_`・`lists_idle_`。行は `<接頭辞>row_<pkg>`、`<接頭辞>up_<pkg>`、`<接頭辞>down_<pkg>`、`<接頭辞>remove_<pkg>`、追加は `<接頭辞>add`、停止中の変更は `lists_idle_change`。

### LinkScreen.kt(§6-3「リンクの行き先」)
- 「内蔵ブラウザで分割」(`link_mode_inapp`)/「Chrome で開く」(`link_mode_chrome`)の選択。
- 「Chrome に回すドメイン」の一覧: 各行に削除(`link_domain_remove_<domain>`)。入力欄(`link_domain_input`)と「追加」(`link_domain_add`)。
  `normalizeDomain` が null なら追加せず「正しいドメインを入力してください」。既にあるなら追加しない。
- 入力欄は分割の右のパネルでもキーボードで隠れても使えればよい(特別な処理は不要)。

### GestureScreen.kt(§6-4「操作」)
- 「スワイプ帯の幅」: `stripWidthChoices` を「8px」等で並べる(`gesture_width_<n>`)。
- 「光る演出」: 切り替え(`gesture_glow`)。
- 「長押しの時間」: `longPressChoices` を `longPressLabel` で並べる(`gesture_longpress_<ms>`)。

### BootSettingsScreen.kt(§6-5「起動時」)
- 「車との接続を待ってから前回のセットを開く」の切り替え(`boot_wait_car`)を**無効の表示**で、今の値を見せる。案内「車での確認の後に使えるようになります」(`boot_wait_car_note`)。

### 保存
- どの画面も、変更のたびに `AppGraph.config.update { ... }` で保存する。テストで偽物に差し替えられるよう、画面は `config: LauncherConfig` と `onUpdate: ((LauncherConfig) -> LauncherConfig) -> Unit` を引数に取る形にする
  (`SettingsHost` の中で、`AppGraph.latestConfig` を読み、`onUpdate` は Task 042 の `settingsScope` で `AppGraph.config.update` を呼ぶ。設定がまだ読めていないときは「読み込み中」を出す)。

### 帯の幅の反映(EdgeStripService.kt)
今は帯の幅を、帯を窓に付けるときだけ読むため、設定を変えても次にサービスが作り直されるまで変わらない。次のようにする:
- companion object に `fun applyGesture(cfg: GestureConfig)` を足す: `gestureConfig = cfg` を行い、**幅が変わったときだけ**、動いているサービスの帯を付け直す(今の `readdStrips()` を main スレッドで呼ぶ)。
- 動いているサービスは、companion object の `@Volatile private var running: EdgeStripService?` に `onCreate`(安全モードで止まる場合を除く)で入れ、`onDestroy` で消す。
- 光る演出・長押しの時間は、今も触るたびに `gestureConfig` を読んでいるので、そのままでよい。

### パネルの「+」
- `PanelScreen` の `onAdd` を、設定の「パネルとホーム」を開く経路につなぐ: `PanelScreen` に引数 `onEditLists: () -> Unit = {}` を(`onNewSet` の直後に)足し、`onAdd = onEditLists`。
- `PanelActivity`・`HomeActivity` では、Task 042 の設定の入口と同じ規則(開いている最中は何もしない、分割の中はパネルの中、外は `SettingsActivity`)で、`SettingsRoute.Lists` から開く。
  `SettingsActivity` の extra は `open_route=lists`(043 の `open_new_set` と同じ書き方)。

## テスト(先に書き、修正前に失敗することを確かめる)
1. `T/settings/DailySettingsTest.kt`(純粋な JUnit): 計画の名前 `lists_dockLimitedTo4`、`lists_panelLimitedTo5`(`add` で上限を超えない)、
   `add_duplicate_noChange`、`moveUp_first_noChange`、`remove_removes`、`longPressLabel_all`、
   `normalizeDomain_stripsSchemeAndPath`(`" HTTPS://Accounts.Google.com/foo "` → `"accounts.google.com"`)、`normalizeDomain_rejectsBlankAndSpaces`。
2. `T/settings/DailyScreensTest.kt`(Robolectric sdk 35 + Compose。`config` と `onUpdate` は偽物。`w516dp-h1000dp` で崩れないことを1つ以上):
   `lists_addToDock_disabledAt4`、`lists_removeAndReorder_updatesImmediately`、`lists_idleChange_setsIdleApp`、
   `link_switchToChrome_saves`、`link_addDomain_normalizes`、`link_addInvalid_showsError`、
   `gesture_chooseWidth_saves`、`gesture_unknownWidth_noneSelected`、`gesture_toggleGlow_saves`、`gesture_chooseLongPress_saves`、
   `boot_waitCar_isDisabled_withNote`。
3. `T/edge/EdgeStripWidthTest.kt`(Robolectric。既存の `EdgeStripServiceTest` の偽の WindowManager の作り方に合わせる):
   `applyGesture_widthChanged_reattachesWithNewWidth`、`applyGesture_sameWidth_doesNotReattach`、`applyGesture_noService_onlyStores`。
   後始末で `gestureConfig` を既定に戻す。
4. `T/ui/panel/PanelActivityTest.kt` に: `plus_inSplit_opensListsInPanel`。Activity の中で書いた Compose の状態を確かめる前に `Snapshot.sendApplyNotifications()` を呼ぶ
   (Robolectric ではテストの順番によって変更の通知が止まり、`waitForIdle` が 10 秒で `AppNotIdleException` になるため)。

## 手順(TDD)
1. 上のテストを書く。2. 対象テストを実行し、修正前に失敗することを確かめる(どのテストがどう失敗したかを報告に書く)。
3. 実装する。4. 対象テストが全部通るまで直す(最大3回)。通ったら `assembleDebug`。

## この Task でしないこと
- §6-1・6・7 の画面、車との接続の検知、分割非対応の判定。`ConfigRepository`・`SetRunner` の中身の変更。
- 全テストの実行、実機の操作、git のコミット等。

## 報告
実行したコマンドと結果(件数・失敗件数、修正前の失敗の内容、assembleDebug)、作成・変更したファイル、**決めたこと**。
