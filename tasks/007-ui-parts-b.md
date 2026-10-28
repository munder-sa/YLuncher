# Task 6b: ドックとパネル全体

計画 `docs/superpowers/plans/2026-10-28-gt8d-launcher.md` の Task 6 の後半。仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §4.4 と §9 も読むこと。前半(6a)で作った `Theme.kt`、`PanelModels.kt`、`MediaCard.kt`、`IconGrid.kt` を使う(変更しない)。

## 作るファイル(これで全部。許可外の書込みは拒否され、そこで停止する)

- `app/src/main/java/com/gt8d/launcher/ui/panel/Dock.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelContent.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/DockTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/PanelContentTest.kt`

既存のファイルは変更しない。

## 仕様

パッケージ `com.gt8d.launcher.ui.panel`。

`Dock.kt`(パネルの右端バー。縦に並べる):
```kotlin
@Composable fun Dock(
    status: DockStatus, dockApps: List<AppInfo>, showCar: Boolean,
    onHome: () -> Unit, onSplit: () -> Unit, onCar: () -> Unit,
    onAppList: () -> Unit, onAppListLongPress: () -> Unit,
    onLaunch: (String) -> Unit,
)
```
上から次の順:
1. ステータス: Wi-Fi アイコン(testTag `dock_wifi`)。`status.bluetoothOn != null` のときだけ Bluetooth アイコン(testTag `dock_bt`)。`wifiOn`/`bluetoothOn` が false のときは薄い色(アイコン自体は出す)。ステータスはボタンではない(タップ不可)
2. よく使うアプリ: `dockApps` の先頭から `MAX_DOCK_APPS`(4。`com.gt8d.launcher.config` の定数)件まで。testTag `dock_app_<pkg>`。タップで `onLaunch(pkg)`。`installed == false` のアプリは出さない
3. ホーム(testTag `dock_home`、`onHome`)
4. 分割(testTag `dock_split`、`onSplit`)
5. 車(testTag `dock_car`、`onCar`)。`showCar == false` のときは出さない
6. アプリ一覧(testTag `dock_applist`)。**タップで `onAppList`、長押しで `onAppListLongPress`**(長押しのとき `onAppList` は呼ばない)。`combinedClickable` を使う

アイコンは Material Icons Extended: `Home`、`ViewColumn`(分割)、`DirectionsCar`、`Apps`、`Wifi`、`Bluetooth`。すべてのボタンは最小 72dp 角（`Dims.MinTouch`）で `contentDescription` を日本語で付ける（「ホーム」「分割」「車」「アプリ一覧」）。縦に収まらない場合に備えて、全体を `verticalScroll` できる `Column` にする(内容を切り捨てない)。

`PanelContent.kt`:
```kotlin
data class PanelState(
    val now: NowPlaying?, val idleApp: AppInfo?, val positionMs: Long,
    val panelApps: List<AppInfo>, val dockApps: List<AppInfo>, val dockStatus: DockStatus,
    val showCar: Boolean, val needsPermission: Boolean,
)
data class PanelActions(
    val onMediaAction: (MediaAction) -> Unit, val onSeek: (Long) -> Unit,
    val onLaunch: (String) -> Unit, val onAdd: () -> Unit,
    val onHome: () -> Unit, val onSplit: () -> Unit, val onCar: () -> Unit,
    val onAppList: () -> Unit, val onAppListLongPress: () -> Unit,
    val onOpenPermissions: () -> Unit,
)
@Composable fun PanelContent(state: PanelState, actions: PanelActions)
```
- 左側（幅いっぱいから Dock の幅を引いた残り）に縦並びで、(`needsPermission` のときだけ)許可バナー → `MediaCard` → `IconGrid`。右側に `Dock`
- 許可バナー: 「許可が必要」と表示し、タップで `onOpenPermissions`。testTag `permission_banner`。小さく控えめに、ただしタッチ領域は 72dp 以上
- `IconGrid`: `panelApps` の先頭から `MAX_PANEL_APPS`(5。`com.gt8d.launcher.config`)件まで、3列、`onAdd = actions.onAdd`（最後のマスが「＋」で、2行×3列に収まる）
- `MediaCard` には `state.now`、`state.idleApp`、`state.positionMs`、`actions.onMediaAction`、`actions.onSeek`、`actions.onLaunch` を渡す
- 幅 576dp(ホームの 3:7 分割の右側）でも、はみ出さず・ボタンが潰れず（各ボタン 72dp 以上）、全部のボタンが存在する。内容が縦に収まらない場合は、左側の列を `verticalScroll` にする

## 手順(TDD)

1. テストを書く。`@RunWith(RobolectricTestRunner::class)`、`@Config(sdk = [35])`、`@get:Rule val rule = createComposeRule()`。必須のテスト名:
   - DockTest:
     - `dock_hasButtonsInOrder`: `showCar=true` で `dock_home` → `dock_split` → `dock_car` → `dock_applist` が上から順に並ぶ（`getBoundsInRoot().top` が増える）。`showCar=false` では `dock_car` が存在しない
     - `dock_status_hidesBluetoothWithoutPermission`: `bluetoothOn=null` で `dock_bt` が存在しない、`dock_wifi` は存在する。`bluetoothOn=true/false` では `dock_bt` が存在する
     - `dock_limitsAppsTo4`: 5 件渡しても `dock_app_*` が 4 件だけ
     - `dock_appList_tapAndLongPress`: タップで `onAppList` が1回（`onAppListLongPress` は0回）、長押しで `onAppListLongPress` が1回（`onAppList` は増えない）
     - `dock_buttons_atLeast72dp`: `dock_home`・`dock_split`・`dock_car`・`dock_applist` の幅・高さが 72dp 以上
   - PanelContentTest（`Modifier.size(576.dp, 800.dp)` の枠の中に置く）:
     - `panel_needsPermission_showsBanner`: `needsPermission=true` で `permission_banner` と「許可が必要」がある。タップで `onOpenPermissions`。`false` ではバナーが存在しない
     - `panel_narrowWidth_noOverflow`: 幅 576dp で、`dock_home`・`dock_split`・`dock_applist`・`icon_add`・（再生中のとき）`btn_prev`・`btn_playpause`・`btn_next` がすべて存在し、`getBoundsInRoot()` の幅・高さが各 72dp 以上で、右端が 576dp を超えない
     - `panel_limitsPanelAppsTo5`: 6 件渡しても `icon_<pkg>` が 5 件
2. `/home/munde/tools/gradle-run.sh test` を実行し、新しいテストが失敗する(コンパイルエラーを含む)ことを確認する
3. 実装する
4. `/home/munde/tools/gradle-run.sh test assembleDebug` を実行し、すべて通るまで直す（最大3回。通らなければ、失敗したテスト名とエラーの先頭30行を報告して止まる）

## 守ること

- テストの期待値を、実装に合わせて弱めない。仕様と食い違うと思ったら、直さずに報告して止まる
- 重いエフェクト、走行状態による分岐、ライトテーマを入れない
- 最後に、`test assembleDebug` の結果（実行件数と失敗件数）と、作ったファイル名を報告する。確認していないこと（実機での見た目）を「確認した」と書かない
