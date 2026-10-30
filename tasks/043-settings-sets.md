# Task 043(計画の Task 18b): 設定「画面セット」の一覧と編集・ドックの「分割」ボタン

最初に `tasks/RULES.md` を読み、従うこと。仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` §4.1・§4.5(パネルの「分割」)・§6-1・§8 に従う。
UI の決まり(計画の Global Constraints): 文言は日本語、ダーク固定、配色は `Palette`、タッチ領域は最小 72dp(`Dims.MinTouch`)、本文 20sp 以上(`Dims.BodyText`)。
**分割の右のパネル(最小幅 516dp)でも崩れない**こと。比率の表示と E の値は `SplitRatio`(`config/Model.kt`)をそのまま使う。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

前提: Task 042 で `settings/SettingsNav.kt`(`SettingsRoute.Sets`)・`settings/SettingsScreen.kt`(`SettingsHost`)・`settings/SettingsActivity.kt` ができている。まずそれらを読む。

## ユーザーと司令塔が決めたこと(2026-10-30)
- **保存の単位**: 編集画面で「保存」を押したときだけ保存する。「戻る」(ボタンもシステムの戻るも)は、変更を捨てて一覧へ戻る(確認は出さない)。
- **ドックの「分割」ボタンから来たときだけ**、保存した後にそのセットを開く(設定は閉じる)。設定の一覧から追加・編集したときは開かない。
- **並べ替え・削除の後も、今開いているセットを ID で追う**。今のセットが削除されたら、今の番号を「ホーム」(`SetNavigator.HOME`)にする(画面の切り替えはしない)。
- **並べ替えは ▲▼ のボタン**にする(車の画面でドラッグは扱いにくいため。仕様 §6-1 の「ドラッグ」は司令塔が書き換える)。
- **分割非対応アプリの警告は、この Task では出さない**(判定方法を後の Task で調べる)。編集画面に「分割に対応していないアプリは、分割できないことがあります」の案内文を常に出すだけ。
- アプリはパッケージ単位で選ぶ(今の設定の形のまま)。

## 作成・変更するファイル(これで全部)
新規作成:
- `M/settings/SetsEdit.kt`(純粋な Kotlin。編集の下書きと、番号の付け替え)
- `M/settings/SetsScreen.kt`(一覧と編集の Composable)
- `T/settings/SetsEditTest.kt`
- `T/settings/SetsScreenTest.kt`
変更:
- `M/settings/SettingsNav.kt`(ルートを1つ足すだけ: `data class SetEdit(val setId: String?, val fromSplitButton: Boolean = false) : SettingsRoute`。`setId == null` は新規)
- `M/settings/SettingsScreen.kt`(Sets・SetEdit のルートで `SetsScreen` 側を表示する分岐を足す。`SettingsHost` に引数 `initialRoute: SettingsRoute? = null` を足し、指定があれば `SettingsNav(listOf(Root, initialRoute))` で始める)
- `M/settings/SettingsActivity.kt`(extra `open_new_set=true` のとき `SetEdit(null, fromSplitButton = true)` から始める)
- `M/ui/panel/PanelScreen.kt`(引数 `onNewSet: () -> Unit = {}` を `onOpenSettings` の直後に足し、`onSplit = onNewSet` にする)
- `M/ui/panel/PanelActivity.kt`、`M/ui/home/HomeActivity.kt`(`onNewSet` の接続)
- `M/sets/SetRunner.kt`(`followIndex` を足すだけ)
- `T/sets/SetRunnerTest.kt`(テスト `followIndex_updatesCurrentAndSaved_withoutOpening` を1つ足すだけ。E が呼ばれず、`currentIndex` と保存値が変わる)
- `T/ui/panel/PanelActivityTest.kt`、`T/ui/home/HomeActivityUiTest.kt`(テストを足す。既存のテストは変えない)

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.settings.* --tests com.gt8d.launcher.ui.panel.PanelActivityTest --tests com.gt8d.launcher.ui.panel.PanelScreenTest --tests com.gt8d.launcher.ui.home.HomeActivityUiTest --tests com.gt8d.launcher.sets.SetRunnerTest`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様

### SetsEdit.kt(パッケージ `com.gt8d.launcher.settings`。純粋な Kotlin、Android に依存しない)
```kotlin
data class SetDraft(val id: String, val left: String?, val right: Slot, val ratio: SplitRatio) {
    val canSave: Boolean get() = left != null && ratio.allowedFor(right)
    fun withRight(right: Slot): SetDraft   // 右をパネルにして比率が使えなくなったら、近い比率に直す: 3:7→4:6、7:3→6:4
    fun toScreenSet(): ScreenSet           // canSave が false なら IllegalStateException
    companion object {
        fun from(set: ScreenSet): SetDraft
        fun new(id: String): SetDraft = SetDraft(id, left = null, right = Slot.Panel, ratio = SplitRatio.R6_4)  // §8 の既定と同じ「パネル 6:4」
    }
}

object SetsEdit {
    fun upsert(sets: List<ScreenSet>, set: ScreenSet): List<ScreenSet>   // 同じ id があれば置き換え、無ければ末尾に追加
    fun delete(sets: List<ScreenSet>, id: String): List<ScreenSet>
    fun moveUp(sets: List<ScreenSet>, id: String): List<ScreenSet>       // 先頭なら変えない
    fun moveDown(sets: List<ScreenSet>, id: String): List<ScreenSet>     // 末尾なら変えない
    // 今のセット番号(1 始まり。0 = ホーム)を、同じ id のセットの新しい番号に付け替える。消えていたら HOME。もともと HOME なら HOME
    fun remapIndex(before: List<ScreenSet>, after: List<ScreenSet>, index: Int): Int
}
```
- 既存の `ScreenSet` の `appPairLabel` は、編集で消さない(`toScreenSet` で元の値を保つ。`SetDraft` に `appPairLabel: String? = null` を持たせてよい)。

### 保存の流れ(SetsScreen.kt の中、または SetsScreen.kt に置く関数)
設定を書き換えるときは必ず次の順にする(`ConfigRepository.update` の中で `before` と `after` が分かる):
1. `AppGraph.config.update { cfg -> cfg.copy(screenSets = 変更後) }`
2. 同じ `before`・`after` で `SetsEdit.remapIndex` を計算し、`AppGraph.runner.followIndex(新しい番号)` を呼ぶ(main スレッドで)。
   `SetRunner` に次の関数を足す(`currentIndex` は `private set` のため):
   ```kotlin
   // 設定でセットの並びが変わったとき、今のセットの番号だけを付け替える(画面は切り替えない)
   fun followIndex(index: Int) { currentIndex = index; d.lastIndex.lastSetIndex = index }
   ```
   `SetRunner` のほかの部分は変えない。
- テストしやすいように、この流れは `class SetsStore(update: suspend ((List<ScreenSet>) -> List<ScreenSet>) -> Pair<List<ScreenSet>, List<ScreenSet>>, currentIndex: () -> Int, setCurrentIndex: (Int) -> Unit)` のような小さな部品に分けてよい(名前と形は自由。報告の「決めたこと」に書く)。

### SetsScreen.kt
1. **一覧(`SettingsRoute.Sets`)**:
   - 各セットを1行: 「左のアプリ名 | 右(アプリ名 または『パネル』)」と比率(例「6:4」)。アプリ名は `AppGraph.catalog.label(pkg)`(テストでは差し替えられるよう、名前を引く関数を引数で受ける)。
   - 行のタグ `set_row_<id>`。行を押すと `SetEdit(id)`。各行に ▲(`set_up_<id>`)・▼(`set_down_<id>`)・削除(`set_delete_<id>`)。
     先頭の ▲・末尾の ▼ は無効。削除は確認ダイアログ「このセットを削除しますか?」(`set_delete_yes`・`set_delete_no`)。
   - 末尾に「セットを追加」(`set_add`)→ `SetEdit(null)`。
   - セットが0件のときは「セットがありません」と「セットを追加」だけ。
2. **編集(`SettingsRoute.SetEdit`)**:
   - 下書き(`SetDraft`)は画面の中で持つ(`rememberSaveable` でなくてよい)。新規の id は `UUID.randomUUID().toString()`。既存の id が見つからなければ一覧へ戻る。
   - 左: 「左のアプリ」(`set_left`)→ アプリの選択(下の 3)。未選択なら「選んでください」。
   - 右: 「パネル」(`set_right_panel`)/「アプリ」(`set_right_app`)の切り替え。アプリのときはアプリの選択。
   - 比率: `SplitRatio.entries` のうち `allowedFor(right)` のものだけを、表示「3:7」等で並べる(`set_ratio_<label>`。例 `set_ratio_6:4`)。選択中が分かる見た目。
   - 案内文: 「分割に対応していないアプリは、分割できないことがあります」(タグ `set_unresizeable_note`)。
   - 「保存」(`set_save`)は `canSave` のときだけ押せる。押したら保存の流れ → `fromSplitButton` なら、ホストの `onOpenSet(index)` を呼ぶ(下の 4)。そうでなければ一覧へ戻る(nav を pop)。
   - 「戻る」は保存せずに pop(Task 042 の上のバーの「戻る」と、システムの戻るのまま)。ただし `fromSplitButton` で始めたとき(`SetEdit` が Root の直後)は、戻ると設定を閉じる(`pop` が Root を返したら、Root を見せずに `onExit()`)。
3. **アプリの選択**: 編集画面の中で開く一覧(別のルートにしなくてよい)。`AppGraph.catalog.launchables()` のパッケージを重複なく、ラベル順に並べる。自分のパッケージ(`context.packageName`)は出さない
   (右の「パネル」は別のボタンで選ぶため)。各行のタグ `set_pick_<pkg>`。押すと選んで編集画面に戻る。システムの戻るで選ばずに戻る。
4. **開く**: `SettingsHost` に引数 `onOpenSet: (Int) -> Unit = {}` を足す。
   - `SettingsActivity`: `onOpenSet = { AppGraph.runner.open(it); finish() }`
   - `PanelActivity`(パネルの中の設定): `onOpenSet = { settingsOpen = false; AppGraph.runner.open(it) }`

### ドックの「分割」ボタン
- `PanelScreen` の `onNewSet`。`AppGraph.runner.opening.value != null` なら何もしない(Task 042 の設定の入口と同じ)。
- `PanelActivity`: 分割の中なら、パネルの中で設定を `SetEdit(null, fromSplitButton = true)` から開く(Task 042 の `settingsOpen` に、始めのルートを持たせる)。分割の外なら `SettingsActivity` を extra `open_new_set=true` で起動。
- `HomeActivity`: `SettingsActivity` を extra `open_new_set=true` で起動。

## テスト(先に書き、修正前に失敗することを確かめる)
1. `T/settings/SetsEditTest.kt`(純粋な JUnit):
   - `upsert_replacesSameId_appendsNew`、`delete_removes`、`moveUp_first_noChange`、`moveDown_last_noChange`、`moveUp_swaps`
   - `remap_followsMovedSet`(2番目を ▲ → 今の番号 2 が 1 になる)、`remap_deletedCurrent_isHome`、`remap_home_staysHome`、`remap_otherDeleted_shifts`(1 を削除 → 今の 3 が 2)
   - `draft_new_cannotSaveWithoutLeft`、`draft_withPanel_fixesRatio`(7:3 → 6:4、3:7 → 4:6、5:5 はそのまま)、`draft_keepsAppPairLabel`
2. `T/settings/SetsScreenTest.kt`(Robolectric sdk 35 + Compose。設定の読み書きと名前の引き方は偽物を渡す。`w516dp-h1000dp` で崩れないことを1つ以上):
   - 計画の名前: `sets_editRightSlot_canChoosePanel_leftCannot`(左の選択一覧に自分のパッケージが無い。右は「パネル」を選べる)、
     `sets_ratioChoices_panelHas3_appHas5`(表示は「3:7・4:6・5:5・6:4・7:3」、パネルのときは「4:6・5:5・6:4」)
   - `list_showsSetsWithRatio`、`add_save_appendsSet`、`edit_back_discardsChanges`、`edit_systemBack_discardsChanges`、`save_disabledUntilLeftChosen`
   - `delete_requiresConfirm`、`moveUp_reordersAndKeepsCurrentById`、`deleteCurrent_setsIndexHome`
   - `fromSplitButton_save_opensNewSetIndex`(保存後に `onOpenSet(新しい番号)` が1回)、`fromSplitButton_back_exits`
   - `unresizeableNote_isShown`
3. `T/ui/panel/PanelActivityTest.kt` に: `splitButton_inSplit_opensNewSetInPanel`、`splitButton_outsideSplit_startsSettingsWithNewSet`、`splitButton_whileOpening_doesNothing`。
   Activity の中で書いた Compose の状態を確かめる前に `Snapshot.sendApplyNotifications()` を呼ぶこと(Robolectric ではテストの順番によって変更の通知が止まり、`waitForIdle` が 10 秒で `AppNotIdleException` になるため。既存の `pendingUrl_whileBrowserShown_replacesUrl` と同じ対処)。
4. `T/ui/home/HomeActivityUiTest.kt` に: `splitButton_startsSettingsWithNewSet`。

## 手順(TDD)
1. 上のテストを書く。2. 対象テストを実行し、修正前に失敗することを確かめる(どのテストがどう失敗したかを報告に書く)。
3. 実装する。4. 対象テストが全部通るまで直す(最大3回)。通ったら `assembleDebug`。

## この Task でしないこと
- §6 の 2〜7 の詳細画面、パネルの「+」、分割非対応の判定、D のペアのラベルの編集。
- `SetRunner`(`followIndex` を除く)・`SetNavigator`・`ConfigRepository` の中身の変更。
- 全テストの実行、実機の操作、git のコミット等。

## 報告
実行したコマンドと結果(件数・失敗件数、修正前の失敗の内容、assembleDebug)、作成・変更したファイル、**決めたこと**。
