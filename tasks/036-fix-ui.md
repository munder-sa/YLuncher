# Task 036: 修正: パネルとホームの画面

最初に `tasks/RULES.md` を読み、従うこと。

レビュー `docs/reviews/2026-10-29-codex-review.md` の C1/E1・C3・C4・C5 の PanelScreen 側・C7 を対象とする。仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §4.2・§4.5・§4.6・§5.2・§9 に従う。

Task 17b (`tasks/032-link-browser.md`) の変更がコミットされた状態から始める。034・035・本 Task を含む4本を別 worktree で同時実行する。担当外の変更が必要になっても変更しない。git のコミット・リセット・ブランチ操作はしない。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

## 作成・変更するファイル(これで全部。これ以外のファイルは作らない・変えない)

新規作成:
- `M/ui/panel/PanelEnvironment.kt`
- `T/ui/panel/PanelEnvironmentTest.kt`
- `T/ui/panel/PanelScreenTest.kt`
- `T/apps/AppCatalogTest.kt`
- `T/split/AndroidActivityLauncherTest.kt`
- `T/ui/gesture/TwoFingerSwipeInputTest.kt`

変更:
- `M/ui/panel/PanelForwarder.kt`
- `M/ui/panel/PanelScreen.kt`
- `M/apps/AppCatalog.kt`
- `M/split/ActivityLauncher.kt`
- `T/ui/panel/PanelForwarderTest.kt`
- `T/ui/panel/MediaCardTest.kt`
- `T/ui/panel/PanelContentTest.kt`

司令塔の追加(4回目の後): 次の2つも変更してよい。
- `M/ui/apps/AppListScreen.kt`(一覧の描画を1か所にまとめるため。下の「一覧の描画は AppListScreen に1つだけ」を参照)
- `T/ui/apps/AppListScreenTest.kt`(テストを足す。既存のテストは変えない)

### 一覧の描画は AppListScreen に1つだけ(司令塔の追加)
`PanelScreen.kt` の中に作った一覧の描画(`PanelAppListScreen`)は消し、`AppListScreen` を使う形に戻す。
`AppListScreen` に、引数 `gridState: LazyGridState = rememberLazyGridState()` と `iconRevision: Int = 0` を足す
(既定値があるので、既存の呼び出しとテストはそのまま通る)。アイコンの記憶は `iconRevision` が変わったときだけ作り直し、
グリッドの状態(スクロール位置)は `gridState` で保つ。`PanelScreen` は `rememberLazyGridState()` を持ち、
`iconRevision = environment.revision` を渡す。`panelScreen_radioChange_keepsAppListScroll` はそのまま通ること。
`AppListScreenTest` に `appList_iconRevisionChange_reloadsIconsKeepsScroll` を足す(スクロールしてから iconRevision を変えると、
iconFor が呼び直され、スクロール位置は変わらない)。

次のファイルは、C7 の実ポインター入力・寸法・到達性テストが実際に失敗した場合だけ、その失敗を直す最小限の変更をしてよい:
- `M/ui/gesture/TwoFingerSwipe.kt`
- `M/ui/panel/MediaCard.kt`
- `M/ui/panel/PanelContent.kt`
- `M/ui/panel/Dock.kt`
- `M/ui/panel/IconGrid.kt`

## 使ってよいコマンド

読み取り・調査コマンドは RULES に従う。ビルド・テストは次の完全な2行だけ。全テスト `test` は実行しない。

- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.ui.panel.PanelForwarderTest --tests com.gt8d.launcher.ui.panel.PanelEnvironmentTest --tests com.gt8d.launcher.ui.panel.PanelScreenTest --tests com.gt8d.launcher.apps.AppCatalogTest --tests com.gt8d.launcher.split.AndroidActivityLauncherTest --tests com.gt8d.launcher.ui.apps.AppListScreenTest --tests com.gt8d.launcher.ui.gesture.TwoFingerSwipeTest --tests com.gt8d.launcher.ui.gesture.TwoFingerSwipeInputTest --tests com.gt8d.launcher.ui.panel.MediaCardTest --tests com.gt8d.launcher.ui.panel.PanelContentTest --tests com.gt8d.launcher.ui.panel.DockTest --tests com.gt8d.launcher.ui.panel.IconGridTest`
- `/home/munde/tools/gradle-run.sh assembleDebug`

Gradle はバックグラウンドに回さず、終わるまで待つ形で実行する。

## 仕様

### 共通: 指摘の再現を先に行う

各指摘について、下記の名前のテストを先に追加し、修正前に実行する。

- 対応するテストが修正前から通る場合、そのテストが確認した範囲では指摘が再現しなかったものとして、その項目の製品コードを直さない。テストは残し、報告にテスト名・結果・未確認範囲を書く。次の独立した指摘へ進む。
- C7 は「テスト欠落」の指摘であり、追加テストが通ること自体は正常である。動作不良が再現しなければテスト追加だけで完了する。
- 新しい API の未実装によるコンパイル失敗は記録するが、既存の不具合を再現した証拠とは区別する。既存経路で観測した誤動作を検証するテストも用意する。
- 失敗を作るための製品コードの破壊、無条件失敗、実装の内部構造だけを比較するテストは禁止。
- 既存テストの名前・期待値・検証内容は変更しない。本 Task には、RULES の「テストを弱めない」の例外として司令塔が認めた既存期待値変更はない。

### C1/E1: PanelForwarder

仕様 §5.2 の「印があるあいだは転送しない」に合わせる。

- 1500ms タイマーの発火時にも `opening() == null` を必要条件とする。
- 発火時に opening が非nullなら転送しない。1500ms タイマーを繰り返し予約しない。既存の `onOpeningCleared()` 通知を待つ。
- `onOpeningCleared()` も、現在の `opening() == null` を確認してから転送する。古い完了通知の直後に別の opening が立っている場合は転送しない。
- opening がnullになった通知では、表示中・分割外・未転送なら即時に1回だけ転送する。
- 非表示・分割内では転送しない。既存の1500ms待ち、キャンセル、1表示につき最大1回の規則は保つ。
- PanelActivity と AppGraph の通知接続は変えない。

`PanelForwarderTest` に追加:
- `forwarder_openingStartsDuringWait_suppressesTimerThenForwardsOnClear`
  - opening=null、分割外で表示。
  - 1000ms後に `Opening("a", "b", 1000L)` を設定。
  - 合計1500ms、さらに5000ms進めても転送0。
  - opening=nullとして `onOpeningCleared()` → 転送1。
  - 同通知と時間経過を追加しても1。
- `forwarder_staleClearWhileOpening_doesNotForward`
  - opening非nullで表示し、非nullのまま `onOpeningCleared()` → 転送0。
  - nullにして同通知 → 転送1。
- `forwarder_openingStartsDuringWait_thenHidden_neverForwards`
  - 待機途中でopeningを設定して非表示。
  - タイマー経過・opening解除通知でも転送0。

### C3: 一覧で選んだ Activity を開く

Task 025 の「`onLaunchApp(entry.pkg)`」という指定を訂正する。アプリ一覧からは選択した `(pkg, cls)` を保持する。仕様 §4.5 の分割中フラグと §4.6 の起動可能アプリ一覧を根拠とする。

`AndroidActivityLauncher` に、インターフェースを変更せず次の公開メソッドを追加する:

```kotlin
fun startComponent(component: ComponentName, adjacent: Boolean): Boolean
```

- `Intent.ACTION_MAIN` と `Intent.CATEGORY_LAUNCHER` を設定し、componentを明示する。
- `adjacent=false`: `FLAG_ACTIVITY_NEW_TASK` を付ける。
- `adjacent=true`: `NEW_TASK | LAUNCH_ADJACENT | MULTIPLE_TASK` を付ける。
- `getLaunchIntentForPackage()` による代表入口の再取得をしない。
- `context.startActivity(intent)` 成功時true、例外時false。別Activityへのフォールバックはしない。
- 既存の `ActivityLauncher` インターフェース、`startApp`・`start`・`canResolve`・`isResizeable` は変更しない。035はこのファイルを変更しない前提。

PanelScreen:
- 公開関数の既存引数 `inSplit`・`onLaunchApp` は保持する。既存呼び出し側を変更しない。
- 一覧の通常項目では `AndroidActivityLauncher(context).startComponent(ComponentName(entry.pkg, entry.cls), adjacent = inSplit)` を呼ぶ。
- `STOCK_LAUNCHER_ENTRY` は従来どおり `stockLauncherIntent()` を使い、分割用フラグを付けない。
- 起動試行の後で `showAppList=false`。失敗しても代表入口へ起動し直さない。
- 停止中アプリ・パネルアイコン・ドックのパッケージ起動は既存の `onLaunchApp` を使う。

`AndroidActivityLauncherTest` に追加:
- `startComponent_selectedSecondaryActivity_isPreserved`
  - 同一パッケージの入口A・Bを登録し、代表入口がAになる準備をする。
  - B指定で起動し、開始IntentのcomponentがBであることを確認。
- `startComponent_adjacent_hasAllThreeFlags`
  - trueで3フラグすべて、ACTION_MAIN・CATEGORY_LAUNCHERを確認。
- `startComponent_fullscreen_hasNoAdjacentOrMultipleTask`
  - falseでNEW_TASKあり、残り2フラグなし。
- `startComponent_startFailure_returnsFalseWithoutFallback`
  - startActivityが例外になるContextでfalse、起動試行1回。

`PanelScreenTest` に追加:
- `panelScreen_appListSelection_launchesSelectedComponent`
  - 実際の一覧に同一pkgのA・Bを表示し、ラベルBの項目を押す。
  - 開始IntentがB、パッケージ起動コールバックは0回、一覧は閉じる。
- `panelScreen_appListSelection_inSplit_usesAdjacentFlags`
  - inSplit=trueで通常項目を押し、3フラグを確認。
- `panelScreen_stockLauncher_doesNotUseAdjacentFlags`
  - inSplit=trueでも純正ランチャーの明示component、隣接・複数タスクフラグなし。

同一pkgの一覧項目は既存testTagが重複するため、テストではラベルとクリック可能な親でBを特定する。testTagの仕様は変更しない。

### C4/C5: 画面の外部状態を更新する共通部品

`M/ui/panel/PanelEnvironment.kt` に、次を作る:

```kotlin
data class PanelEnvironment(
    val dockStatus: DockStatus,
    val needsPermission: Boolean,
    val installedPkgs: Set<String>,
    val appListEntries: List<AppListEntry>,
    val revision: Long,
)

@Composable
fun rememberPanelEnvironment(catalog: AppCatalog): PanelEnvironment
```

- LifecycleOwner は `LocalLifecycleOwner.current` を使用する。
- 初回に状態を読み取る。
- `ON_RESUME` ごとに権限・通信状態・インストール集合・一覧を再読込する。
- Wi-FiとBluetoothの状態通知、パッケージ通知を動的Receiverで購読する。
- 通信用filter:
  - `WifiManager.WIFI_STATE_CHANGED_ACTION`
  - `BluetoothAdapter.ACTION_STATE_CHANGED`
- パッケージ用filter:
  - `Intent.ACTION_PACKAGE_ADDED`
  - `Intent.ACTION_PACKAGE_REMOVED`
  - `Intent.ACTION_PACKAGE_CHANGED`
  - `addDataScheme("package")`
- 2種類のfilterは別Receiverにする。通信通知までpackageスキームで除外しない。
- 登録には `ContextCompat.registerReceiver` と `ContextCompat.RECEIVER_EXPORTED` を使う。システム由来の上記actionだけを処理し、独自actionは受け付けない。
- 登録後にもう一度読み取り、登録直前の変更を取りこぼさない。
- `DisposableEffect` で登録し、破棄時はLifecycleObserverと両Receiverを解除する。ON_RESUMEのたびにReceiverを追加登録しない。
- アプリ一覧表示中も購読を維持する。PanelScreenの一覧表示による早期returnより前で呼ぶ。
- 周期ポーリングは追加しない。メディア位置の500ms更新に問い合わせを結び付けない。
- `revision` は初期0。復帰・対応通知による再読込ごとに1増やす。集合の内容が同じでも、アイコンを描き直せるように状態を更新する。

読取規則:
- Wi-Fi: 現行の `WifiManager.isWifiEnabled`。失敗時false。
- Bluetooth: BLUETOOTH_CONNECT未許可ならnull。許可時は現行のadapter.isEnabled、取得失敗時null。
- needsPermission: 通知リスナー・オーバーレイ・使用状況アクセスのいずれかが未許可ならtrue。現行の3つの判定方法を移す。
- installedPkgs: `catalog.installed()`。
- appListEntries: `buildAppList(catalog.launchables(), context.packageName)`。
- 通信通知ではdockStatusとneedsPermissionを再読込する。インストール一覧への問い合わせとアイコン失効はしない。
- 初回・ON_RESUMEでは全項目を再読込する。
- パッケージ通知では、対象pkgのアイコンを失効させてから全項目を再読込する。
- ON_RESUMEでは、全アイコンを失効させてから全項目を再読込する。非表示中に通知を逃した場合も復旧させる。
- 初回の全読込はアイコンキャッシュを消さない。

AppCatalogに次を追加:
```kotlin
fun invalidatePackage(pkg: String) // iconCache.remove(pkg)
fun invalidateAllIcons()         // iconCache.clear()
```
既存メソッドのシグネチャは変えない。公開キャッシュや書換可能な内部Mapは追加しない。

PanelScreen:
- `remember(config) { catalog.installed() }` を共通部品のinstalledPkgsに置き換える。
- idleApp・panelApps・dockAppsはその集合と最新キャッシュで構築する。
- dockStatus・needsPermissionも共通部品の値を使う。
- 一覧は共通部品のappListEntriesを使い、表示中のパッケージ変更を反映する。
- 一覧のiconForは再コンポーズ後にcatalog.iconを再評価する。古いremember結果を残さない。
- メディア制御・再生位置更新・設定購読の動作は変えない。

`PanelEnvironmentTest` に追加:
- `environment_resume_refreshesPermissionsAndRadios`
  - 同じCompositionを維持し、停止中に権限と通信状態を変更してON_RESUME。
  - 新しいdockStatus・needsPermissionになることを確認。
- `environment_radioBroadcast_refreshesWithoutMediaChange`
  - メディア状態を変更せず、Wi-Fi・Bluetoothそれぞれの状態を変更して通知。
  - 通知後の状態を確認。Bluetooth権限なしではnull。
- `environment_dispose_unregistersObserversAndReceivers`
  - Composition破棄後の通知・復帰で再読込されないことを読取回数で確認。
  - 再度Compositionを作っても1通知につき更新は1回。
- `environment_packageBroadcast_refreshesInstalledAndEntries`
  - 同じ設定でパッケージ追加→削除→変更通知。
  - installedPkgs・一覧の増減と変更後のラベルを確認。
- `environment_resume_refreshesPackagesMissedWhileHidden`
  - 非表示中にパッケージを変更し、通知なしで復帰。
  - 集合・一覧・アイコンが更新されることを確認。

`AppCatalogTest` に追加:
- `catalog_invalidatePackage_reloadsOnlyTargetIcon`
  - A・Bのアイコンをキャッシュ。
  - PackageManager側の両方の画像を変更してAだけ失効。
  - Aは新画像、Bは旧画像。画像のピクセル内容で比較する。
- `catalog_invalidateAllIcons_reloadsCachedIcons`
  - 同条件で全失効後はA・Bとも新画像。

`PanelScreenTest` に追加:
- `panelScreen_resume_updatesPermissionBannerWithoutMediaChange`
  - now=nullの画面で、権限変更と復帰だけによりpermission_bannerが更新される。
- `panelScreen_packageAdded_enablesConfiguredAppWithoutConfigChange`
  - 設定済みの未導入アプリを追加し通知。
  - config・メディアを変更せず、表示が未導入から導入済みに変わり、押すと対象pkgを通知する。
- `panelScreen_packageRemoved_disablesConfiguredAppWithoutConfigChange`
  - 削除通知後、未導入表示となり、IconGridからのタップでは起動通知しない。
- `panelScreen_openAppList_updatesAfterPackageChange`
  - 一覧を開いたまま追加・削除通知を送り、表示項目が更新される。

Robolectricは既存テスト同様sdk=35を使う。ComposeホストActivity登録はMediaCardTestに合わせる。状態変更はRobolectricのPackageManager・権限・各サービスのShadowとLifecycleイベントで制御する。テスト間で状態と登録を片付ける。AppGraphの本番コード・TestApp・共通テスト設定を変更しない。利用可能なShadowで必要な状態を制御できない場合は、成功したことにせずRULESの3点セットで報告する。

### C7: 実ポインター入力の回帰テスト

分類関数だけで代用せず、Composeの `performTouchInput` でdown/move/upを送る。セマンティクスのクリック・SetProgressだけでジェスチャーやシークの検証を済ませない。新規依存・androidTestは追加しない。

`TwoFingerSwipeInputTest`:
- 既存modifierを付けた幅516dp・高さ400dpのホストを使う。入力座標はdensityを使ってdpからpxへ変換する。
- `input_twoFingerLeft_emitsNextOnce`
  - 指0を(400,100)dp、指1を(400,200)dpに順にdown。
  - 両方をx=280dpへ移動。
  - 指0だけupでは通知0、指1もupでNextが1回。
- `input_twoFingerRight_emitsPrevOnce`
  - x=100dpから220dp、yは上と同じ。全部upでPrevが1回。
- `input_thirdFingerDuringSwipe_emitsNothing`
  - 2本移動中に指2を追加して離す。残る指を移動して全部離しても通知0。
- `input_oneFingerScroll_reachesChildWithoutSetChange`
  - 子にverticalScrollと十分に長い内容を配置。
  - 1本指をy=300dpから100dpへ動かして離す。
  - 子のスクロール位置が正方向へ変化し、セット操作通知0。
- `input_sequentialSinglePointers_doNotCountAsTwo`
  - 指0を離した後で指1を押して移動。
  - 各ジェスチャーで通知0。

指0を先に離しても、各指の移動量はそれぞれの押下位置から離脱位置まで。イベントconsumeは禁止。閾値80dp・最大傾き0.5・最大同時指2本という既存規則は変えない。

`MediaCardTest` に追加:
- `mediaCard_pointerSeek_commitsEndOnceOnRelease`
  - duration=120000ms、position=0。
  - progress中央から右端近くへ実ポインターでドラッグ。
  - up前のonSeekは0回、up後は1回、値120000ms。
- `mediaCard_pointerSeek_commitsStartOnceOnRelease`
  - duration=120000ms、position=60000。
  - 左端近くまでドラッグし、up後に1回、値0ms。
- `mediaCard_unknownDuration_hasNoPointerSeekTarget`
  - duration=nullと0の両方でprogressなし、onSeekなし。

端点はprogressノードのローカル座標で右端-1px／左端+1pxを使う。途中値の丸め誤差を避け、端点の確定値を厳密比較する。再生位置の入力値はドラッグ中に変更しない。

`PanelContentTest` に追加:
- `panel_narrowShortWithFavorite_allControlsReachable`
  - 幅516dp・高さ400dp、hasFavorite=true、duration=200000ms、position=50000ms。
  - パネルアプリ5個、ドックアプリ4個、showCar=true、needsPermission=true。
  - permission_banner、progress、btn_prev、btn_playpause、btn_next、btn_favorite、パネル5個、icon_add、ドック4個、dock_home、dock_split、dock_car、dock_applistを検証する。
  - 各対象まで所属列を実際にスクロールして表示させる。存在するだけでは合格にしない。
  - 各操作領域の幅・高さ72dp以上、表示時のboundsがホストの左右内に収まることを確認する。
  - progressは実ポインターによるシーク、その他はタップし、対応コールバックを検証する。
  - 同時に全対象が表示されることは要求しない。内容を削らず、縦スクロールで到達できることを要求する。

製品コード変更が必要な場合:
- progressの操作領域は `sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch)` で72dp以上にする。
- ☆付きの操作ボタン行が横に収まらなければ、順序「戻る・再生/一時停止・送る・☆」を保って複数行にする。ボタンの縮小・削除は禁止。
- 列の縦スクロールを維持し、必要なら適切な列へ追加する。
- それ以外の動作変更が必要だが仕様で一意に決まらない場合は、変更せずRULESの3点セットで報告する。

## 手順(TDD)

1. 基準状態と17bの変更を読み、担当外ファイルに変更が必要ないことを確認する。
2. 指摘ごとに上記テストを先に追加する。
3. 許可された完全なtestDebugUnitTestコマンドを実行し、修正前の失敗テスト名・失敗内容を記録する。
4. 修正前から通る項目は製品コードを変更せず報告対象とし、次の独立項目へ進む。
5. 再現した項目だけを許可ファイル内で実装する。
6. 同じtestDebugUnitTestコマンドで確認する。修正後の再実行は最大3回。通らなければ失敗テスト名とエラー先頭30行を報告して止まる。
7. assembleDebugを実行する。
8. git diffで許可外ファイルの変更・既存テストの弱体化がないことを確認する。

## この Task でしないこと

- HomeActivity.ktの変更。C5のホーム左側は034の担当。
  - 034側には、同じLifecycleOwner下でrememberPanelEnvironment(AppGraph.catalog)を使用し、そのinstalledPkgsでhomeAppsを構築する接続が必要。
  - PanelScreenとHomeActivityで共通部品を別々に呼ぶと購読・再読込が重複する。共有方法の最適化は本Taskでは行わない。
- PanelActivity.ktの変更。17bのpanelInSplit更新、PendingUrl消費、BrowserActivity起動を維持する。
- AppGraph.kt、Manifest、設定・起動・セット実行・前面監視の変更。
- C2/D2の設定読込競合、C6の暖かい起動記録。
- 035担当の分割方式・失敗処理。ActivityLauncher.ktはC3のメソッド追加だけ。
- 実機依存の成立を推測して直すこと。レビューA6の「パネルonResume通知だけで成立確認できるか」は確度「要実機確認」であり変更しない。
- 車経由のマルチタッチ到達、GT8Dでの隣接起動先、実際の通知配送順序、実機の描画・タッチ精度の保証。Robolectric結果と区別して未確認と報告する。
- MediaWatcherの監視・シーク送信方式の変更。
- 既存指示書・仕様・レビュー文書・ビルド設定・依存関係・共有テスト設定の変更。
- 許可外ファイルへの修正。必要になった場合は変更せず、必要なファイルと理由を報告する。

## 報告

日本語で次を報告する:
- 指摘ごとの追加テスト名、修正前の結果、修正内容、修正後の結果。
- 修正前から通った項目と、そのため製品コードを変更しなかった範囲。
- 実行したコマンドを完全な形で記載し、テスト実行件数・失敗件数、assembleDebugの結果。
- 作成・変更したファイル名。
- C5のホーム左側に残る034側の接続事項。
- 実ポインター入力で確認できた範囲と、実機未確認の範囲。
- 許可外ファイルの変更が必要だった場合、そのファイルと理由。
- **決めたこと**。なければ「決めたこと: なし」。
