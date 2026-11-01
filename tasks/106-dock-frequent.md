# Task 106: ドックを「よく使うアプリ」の自動に、最後に設定アプリを固定(ドックのアプリ 5 枠)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
ユーザーの決定(モック `/mnt/f/YLuncher/build/mock/dock-applist/dock-applist.png` の上の2枠。HTML は同じ場所の `.html`)。ただしユーザーの指示で **ドックのアプリは 5 枠 = 自動(または手動)の 4 つ + 設定アプリ(固定)**(モックの「3 つ + 設定」より、この指示書が優先)。
- 自動: 最近 7 日によく開いたアプリの上位 4 つ。ホームのアプリの段(パネルのアプリ)と重複しても除かない。並びが変わるのは、ホームが表に出たとき(`HomeActivity.onResume`)とアプリの起動時だけ(使っている最中には動かない)。
- 設定アプリ: GT8D の「設定」は `com.suding.settings`(ランチャーに出る activity は `com.suding.settings/.MainActivity`)。`com.android.settings` は LAUNCHER の activity を持たない。

## 変更・作成するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/config/Model.kt`(`DockMode` と `LauncherConfig.dockMode` を足すだけ)
- create `app/src/main/java/com/gt8d/launcher/apps/UsageHistory.kt`
- create `app/src/main/java/com/gt8d/launcher/apps/FrequentApps.kt`
- create `app/src/main/java/com/gt8d/launcher/apps/SettingsApp.kt`
- `app/src/main/java/com/gt8d/launcher/AppGraph.kt`(よく使うアプリの StateFlow と更新の関数)
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeActivity.kt`(`onResume` で更新を呼ぶ・アプリの起動の後に呼ぶ所だけ)
- `app/src/main/java/com/gt8d/launcher/ui/panel/Dock.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelContent.kt`(`PanelState`/`PanelActions` に設定アプリを足し、`Dock` に渡す所だけ)
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt`(ドックのアプリの決め方・設定アプリを渡す所だけ)
- `app/src/main/java/com/gt8d/launcher/settings/ListsScreen.kt`(ドックの所だけ)
- テスト: create `app/src/test/java/com/gt8d/launcher/apps/FrequentAppsTest.kt`、create `app/src/test/java/com/gt8d/launcher/apps/SettingsAppTest.kt`、`app/src/test/java/com/gt8d/launcher/ui/panel/DockTest.kt`、`app/src/test/java/com/gt8d/launcher/settings/DailyScreensTest.kt`、`app/src/test/java/com/gt8d/launcher/config/ConfigCodecTest.kt`、`app/src/test/java/com/gt8d/launcher/ui/layout/LayoutMatrixTest.kt`、`app/src/test/java/com/gt8d/launcher/ui/panel/PanelScreenTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.apps.*' --tests 'com.gt8d.launcher.config.*' --tests 'com.gt8d.launcher.settings.*' --tests 'com.gt8d.launcher.ui.panel.*' --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.ui.layout.*'`

## 仕様
1. 設定の形(`Model.kt`): `@Serializable enum class DockMode { Auto, Manual }`、`LauncherConfig` の最後に `val dockMode: DockMode = DockMode.Auto`。`CONFIG_VERSION` は上げない(無い古い設定は Auto で読める)。`MAX_DOCK_APPS` は 4 のまま(手動のリストも 4 まで)。
2. `UsageHistory.kt`: `class UsageHistory(context, query: ((beginWallMs: Long, endWallMs: Long) -> List<UsageLaunch>)? = null)`、`data class UsageLaunch(val pkg: String, val wallMs: Long)`。`fun launchesSince(sinceWallMs: Long): List<UsageLaunch>?`: `UsageStatsManager.queryEvents` の `ACTIVITY_RESUMED` を時間順に返す。許可が無い・失敗したら `null`(例外を外に出さない)。`available()` は `UsageStatsForegroundEvents` と同じ確かめ方。
3. `FrequentApps.kt`: 純粋な関数 `fun rankFrequent(launches: List<UsageLaunch>, eligible: Set<String>, limit: Int = MAX_DOCK_APPS): List<FrequentApp>`、`data class FrequentApp(val pkg: String, val count: Int)`。
   - 回数 = そのアプリが「別のアプリの次に」表に出た回数(同じアプリの続けての RESUMED は 1 回)。間にどのパッケージ(自分・ランチャー・除外のものを含む)が挟まっても「別のアプリ」とみなす。
   - `eligible` に無いパッケージは数えない(呼ぶ側で: ランチャーに出るアプリ(`AppCatalog.installed()`)から、自分(`context.packageName`)・`OwnPackages.PANEL_HOST`・純正ランチャー(`STOCK_LAUNCHER_ENTRY.pkg`)・設定アプリのパッケージを除いたもの)。
   - 並び: 回数の多い順 → 同じなら最後に使った時刻が新しい順 → パッケージ名順。上位 `limit` 個。
   - `fun dockAppsFor(mode: DockMode, manual: List<String>, ranked: List<FrequentApp>?, installed: Set<String>): List<String>`: Manual → `manual` から入っているものを 4 つまで。Auto → `ranked` のパッケージ、4 つに満たなければ `manual` の入っているもので重ならないように埋める(`ranked == null`(許可が無い・まだ数えていない)なら `manual` だけ)。
4. `SettingsApp.kt`: `fun settingsComponent(pm: PackageManager): ComponentName?`: `com.suding.settings` の LAUNCHER の activity があればそれ、無ければ `Intent(Settings.ACTION_SETTINGS)` を解決した activity、どちらも無ければ null。
5. `AppGraph`: `val frequentApps: StateFlow<List<FrequentApp>?>`(初めは null)と `fun refreshFrequentApps()`: 別のスレッド(IO)で `UsageHistory.launchesSince(今 − 7 日)` → `rankFrequent` → 値を入れる。同時に何度呼ばれても 1 本だけ走る(走っている間の呼び出しは捨ててよい)。`HomeActivity.onResume` と、ホームからアプリを開いた後(`onLaunchApp`)に呼ぶ。アプリの起動時(`AppGraph.init`)にも 1 回。ログはタグ "GT8D"(今のログの仕組み `DiagLog` を使う)で、件数と上位だけ。
6. ドック(`Dock.kt`):
   - アプリの枠: 渡されたアプリ(最大 4、入っているものだけ。今どおり)→ 続けて**設定アプリのボタン**(`settingsApp: AppInfo?` が null でなければ。タグ `dock_settings`、内容説明「設定」、そのアプリのアイコン 64dp、無ければ `Icons.Default.Settings` 32dp 白)。押すと `onSettings()`。→ ホーム → セット追加 → (車)→ アプリ一覧(今のまま)。
   - GT8D の高さ(726dp)・車なし・アプリ 4 つ+設定で、ドックの全部のボタンがスクロールなしで見えること。車ありで入らないときは今どおり縦にスクロール。
7. `PanelScreen`: ドックのアプリ = `dockAppsFor(config.dockMode, config.dockApps, AppGraph.frequentApps の値, installed)` を `catalog.info` にしたもの。`frequentApps` は `collectAsState` で読むが、更新は 5 の時だけなので使っている最中には変わらない。設定アプリ = `settingsComponent` の `catalog.info(pkg)`(覚えておき、毎回 PackageManager を読まない)。`onSettings` = その component を、今のアプリ一覧の起動と同じ方法(`onLaunchComponent` があればそれ、無ければ `AndroidActivityLauncher(context).startComponent(component, adjacent = inSplit)`)で開く。
8. 設定の「パネルとホーム」(`ListsScreen` のドックの所):
   - 見出し「ドックのアプリ」の下に `SettingsSegments`「自動(よく使う)」(タグ `lists_dock_mode_auto`)/「自分で選ぶ」(`lists_dock_mode_manual`)。押すと `dockMode` を保存(この画面の今の即時保存の方法)。
   - Auto のとき: 今のドックの 4 つを番号つきの行で(タグ `lists_dock_auto_row_<pkg>`)、右に `Palette.Muted` で「7日で N 回」(埋めたものは「記録なし」)。編集のボタンは出さない。許可が無いとき(`ranked == null`)は、行の上に「使用状況へのアクセスを許可すると、よく使うアプリが並びます」(Muted)。
   - Manual のとき: 今の編集(▲▼削除・追加、4 つまで)のまま。
   - どちらも最後に「設定」の行(右に Muted で「固定」、タグ `lists_dock_settings_row`)。
   - 「パネルのアイコン」・停止中のアプリの所は変えない。

## テスト(足す。既存は弱めない。ドックの並び・手動のリストの既存テストは、Manual にした上で今のまま通るか、Auto が既定になったことで変わる所だけ合わせて報告する)
- `FrequentAppsTest`: 続けての RESUMED は 1 回、間に別のものが挟まると数える、eligible 外は数えない、回数→最後の時刻→名前の並び、上位 4、`dockAppsFor` の Manual / Auto / 埋める / 重ならない / null で手動 / 入っていないものを除く。
- `SettingsAppTest`(Robolectric の ShadowPackageManager): suding があればそれ、無ければ ACTION_SETTINGS、両方無ければ null。
- `ConfigCodecTest`: `dockMode` の無い JSON が Auto で読める、Manual が往復する。
- `DockTest`: 設定のボタンがアプリの後・ホームの前に出る、押すと `onSettings`、アイコンなしで Settings のアイコン、`settingsApp == null` で出ない。
- `LayoutMatrixTest`(または `DockTest`): 726dp の高さ・車なし・アプリ 4+設定で、ドックの全部のボタンが画面の中(スクロールなし)。
- `PanelScreenTest`: Auto で `frequentApps` の順にドックが並ぶ、Manual で `dockApps` の順。
- `DailyScreensTest`: セグメントで dockMode が保存される、Auto で回数の行・「記録なし」・許可なしの案内、Manual で今の編集、設定の「固定」の行。

## この Task でしないこと
- アプリ一覧(Task 107 が別に行う)。ホームのアプリの段・パネルのアプリの変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、置き換えた既存テスト、**決めたこと**。

## 追記(実装前レビューの反映。本文より優先)
- 更新の契機は「`AppGraph.init`」「`HomeActivity.onResume`」「設定の『パネルとホーム』を開いたとき」の 3 つだけ(`onLaunchApp` の後に呼ぶのはやめる。`HomeActivity.kt` は onResume の所だけ)。集計は非同期で、終わった時点で並びが変わってよい(ホームが出た直後に入れ替わるのは許す)。
- 設定アプリのパッケージは、4 つの枠(Auto の順位・Auto の埋め・Manual)のどれにも入れない。`dockAppsFor(..., exclude: Set<String>)` を足して除く。Manual の選択の候補からも除く。設定アプリが解決できないときは除くものなし。
- 回数の数え方の細部: 最初の RESUMED は 1 回。同じアプリが続く間は回数を増やさず、最後に使った時刻はその続きの最後の時刻に更新。時刻が同じものは入力の順。`eligible` 外を除いてから数え、上位 `limit` 個。例: `A@1, A@10, B@11, A@12` → A=2(最後 12)、B=1。
- 設定アプリのボタン: `settingsComponent` が解決できたら出す(`installed` の集合とは無関係)。`AppInfo(pkg = component.packageName, label = "設定", icon = catalog.icon(component.packageName), installed = true)`。解決は `PanelEnvironment` の `revision` が変わったとき(復帰・パッケージの変化)にやり直し、普段の再描画では覚えたものを使う。解決できないときはドックにも設定画面にも「設定」の枠・行を出さない。
- `AndroidManifest.xml` の `<queries>` に `android.settings.SETTINGS` の `<intent>` を足す(変更してよいファイルに足す)。
- 許可とランキングは別に持つ: `AppGraph.usageAvailable: StateFlow<Boolean>`(`UsageHistory.available()`)。設定画面の案内は `usageAvailable == false` のときだけ。`frequentApps == null` で許可ありのとき(まだ数えていない)は、案内を出さず `dockAppsFor` の結果(手動で埋めたもの)を「記録なし」で並べる。
- テストのための入口: `ListsScreen` に引数 `frequent: List<FrequentApp>? = <AppGraph の値>`、`usageAvailable: Boolean = <AppGraph の値>`、`settingsApp: AppInfo? = <解決したもの>` を足す(既定は今の値)。`AppGraph` に、テストから `frequentApps`・`usageAvailable` を入れる internal な関数を足してよい(テストでは `init` の集計が後から上書きしないこと)。
- `Dock` を呼ぶ所は 2 つ(`PanelContent` と `PanelScreen.kt` の `HomeWideContent`)。`PanelState` / `PanelActions` / `Dock` に足すものは、最後に既定値つき(`settingsApp: AppInfo? = null`、`onSettings: () -> Unit = {}`)。変更してよいテストに `app/src/test/java/com/gt8d/launcher/ui/panel/PanelContentTest.kt` を足す。
- 高さのテスト: 1291×726dp で、`HomeWideContent` と分割の `PanelContent`(パネルの幅)の両方、車なし・アプリ 4+設定で、スクロールする前に全部のボタンが画面の中にあり、ドックのスクロールの最大量が 0。ボタンの領域は 72dp のまま(アイコン 64dp)。
- `DailyScreensTest` の、既定の設定のままドックの「追加」を探しているテスト(`lists_addToDock_disabledAt4` など)は、入力を Manual にして今の確かめ方を残す。Manual での並べ替え・削除のテストも足す。
- `UsageHistory` のテスト(許可なし → null・例外 → null・期間・時間順)を `FrequentAppsTest` か新しいテストで足す(新しいテストファイルなら create で `app/src/test/java/com/gt8d/launcher/apps/UsageHistoryTest.kt`)。
