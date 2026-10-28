# Task 15b: アプリ一覧・2本指スワイプ・パネル最小幅 516dp・アプリ情報の問い合わせ削減

**最初に `tasks/RULES.md` を読み、従うこと。** 計画 `docs/superpowers/plans/2026-10-28-gt8d-launcher.md` の **Task 15** の後半、仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §4.2(2本指スワイプ)・§4.5・§4.6・§7.2 に従う。計画の Global Constraints を守る。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

## 作成・変更するファイル(これで全部。許可外の書込みは拒否され、そこで停止する)

新規作成:
- `M/ui/apps/AppList.kt`
- `M/ui/apps/AppListScreen.kt`
- `M/apps/StockLauncher.kt`
- `M/ui/gesture/TwoFingerSwipe.kt`
- `T/ui/apps/AppListScreenTest.kt`
- `T/apps/StockLauncherTest.kt`
- `T/ui/gesture/TwoFingerSwipeTest.kt`

変更:
- `M/apps/AppCatalog.kt`、`M/ui/panel/PanelScreen.kt`、`M/ui/home/HomeActivity.kt`、`M/ui/panel/PanelActivity.kt`
- `T/ui/panel/PanelContentTest.kt`(下の「パネルの最小幅」で指定した2か所だけ)
- `M/ui/panel/PanelContent.kt`、`M/ui/panel/Dock.kt`、`M/ui/panel/IconGrid.kt`、`M/ui/panel/MediaCard.kt` は、**516dp のテストが失敗したときだけ**、通すための最小の変更をしてよい(押せる領域 72dp 以上は保つ。内容を切り捨てず、収まらなければスクロール)。失敗しなければ触らない

使ってよいコマンド: `/home/munde/tools/gradle-run.sh test`、`/home/munde/tools/gradle-run.sh test assembleDebug`。**Gradle はバックグラウンドに回さず、終わるまで待つ形で実行する**(前回、バックグラウンド実行の待ちで時間切れになった)。

## 仕様

### AppList.kt(パッケージ `com.gt8d.launcher.ui.apps`)
```kotlin
data class AppListEntry(val pkg: String, val cls: String, val label: String)

val STOCK_LAUNCHER_ENTRY = AppListEntry(
    pkg = "com.android.launcher3",
    cls = "com.android.launcher3.uioverrides.QuickstepLauncher",
    label = "純正ランチャー",
)

fun buildAppList(all: List<AppListEntry>, ownPkg: String): List<AppListEntry>
```
- `pkg == ownPkg` の項目(自作の「GT8D パネル」)は除く
- `pkg == "com.android.launcher3"` の項目は除き、代わりに `STOCK_LAUNCHER_ENTRY` を**必ず先頭に1つ**置く
- 残りは `java.text.Collator.getInstance(java.util.Locale.JAPANESE)` で `label` の順に並べる
- `(pkg, cls)` が同じ項目は1つにまとめる

### StockLauncher.kt(パッケージ `com.gt8d.launcher.apps`)
```kotlin
fun stockLauncherIntent(): Intent
```
- `Intent.ACTION_MAIN`、コンポーネント `com.android.launcher3/com.android.launcher3.uioverrides.QuickstepLauncher` を明示、`FLAG_ACTIVITY_NEW_TASK`。Task 16 の SafetyGuard 退避でも使う

### AppCatalog.kt の変更(問い合わせの削減)
- 今の `info(pkg)` は、呼ぶたびに `installed()`(端末への問い合わせ)をする。パネルは再生中 500ms ごとに描き直すので、毎回十数回の問い合わせになっている。これをやめる
- `fun info(pkg: String, installedPkgs: Set<String>): AppInfo` に変える(引数の集合で `installed` を決める。既定値は付けない)
- `fun icon(pkg: String): ImageBitmap?` を公開する(今の `loadIcon` と同じ。キャッシュあり)
- `fun launchables(): List<AppListEntry>` を足す: `MAIN`+`LAUNCHER` の `queryIntentActivities` の結果を、`activityInfo.packageName`・`activityInfo.name`・`loadLabel(pm).toString()` で `AppListEntry` にする。失敗したら空リスト
- 呼ぶ側(`HomeActivity`・`PanelScreen`)は、`val installedPkgs = remember(config) { AppGraph.catalog.installed() }` で1回だけ取り、`info(pkg, installedPkgs)` を使う

### TwoFingerSwipe.kt(パッケージ `com.gt8d.launcher.ui.gesture`)
```kotlin
object TwoFingerSwipe {
    const val MIN_DP = 80f
    const val MAX_SLOPE = 0.5f
    fun classify(maxPointers: Int, dxPx: Float, dyPx: Float, density: Float): EdgeAction
}
fun Modifier.twoFingerSwipe(onAction: (EdgeAction) -> Unit): Modifier
```
- `classify`: `maxPointers != 2` なら `None`。`|dx|/density >= MIN_DP` かつ `|dy| < |dx| * MAX_SLOPE` のとき、`dx < 0`(左へ払う)なら `EdgeAction.Next`、`dx > 0`(右へ払う)なら `EdgeAction.Prev`。それ以外は `None`。`density <= 0` は 1 として扱う
- `twoFingerSwipe`: `pointerInput(Unit) { awaitEachGesture { ... } }` で、`PointerEventPass.Initial` で読む。**イベントを consume しない**(1本指のタップ・スクロールはそのまま子に届く)。1回のジェスチャー(最初の指が触れてから全部の指が離れるまで)で、同時に触れていた指の最大数と、最初の2本の指それぞれの移動量(押した位置→離した位置)の平均 dx・dy を求め、全部離れたら `classify` に渡す。`None` 以外なら `onAction` を呼ぶ
- `EdgeAction` は既存の `com.gt8d.launcher.edge.EdgeAction`

### AppListScreen.kt(パッケージ `com.gt8d.launcher.ui.apps`)
```kotlin
@Composable
fun AppListScreen(
    entries: List<AppListEntry>,
    iconFor: (String) -> ImageBitmap?,
    onLaunch: (AppListEntry) -> Unit,
    onClose: () -> Unit,
)
```
- 上に「アプリ一覧」の見出しと閉じるボタン(testTag `applist_close`、72dp 以上)。その下に `LazyVerticalGrid(GridCells.Adaptive(minSize = 112.dp))` でアイコン+名前(各項目 testTag `applist_<pkg>`、押せる領域 72dp 以上)。アイコンが null なら名前の頭文字の丸
- `BackHandler { onClose() }`
- 色・寸法は既存の `ui/theme` の `Palette`・`Dims` を使う

### PanelScreen の変更(アプリ一覧の表示)
- `var showAppList by remember { mutableStateOf(false) }`。true のあいだは `PanelContent` の代わりに `AppListScreen` を出す(パネルの領域の中で切り替える。新しい Activity は作らない)
- `entries` は `remember(showAppList) { buildAppList(AppGraph.catalog.launchables(), context.packageName) }`
- 項目を押したら: `STOCK_LAUNCHER_ENTRY` なら `context.startActivity(stockLauncherIntent())`(`runCatching` で囲む)、それ以外は `onLaunchApp(entry.pkg)`。どちらも押したあと `showAppList = false`
- `PanelActions`: `onAppList` → `showAppList = true`。`onSplit`・`onAdd`・`onAppListLongPress` は設定画面(Task 18)につなぐので、空のラムダのまま、コメントを「Task 18 で接続」に変える

### HomeActivity・PanelActivity の変更(2本指スワイプ)
- 一番外側の `Box` に `Modifier.twoFingerSwipe { action -> ... }` を付ける。`Next` → `AppGraph.runner.next()`、`Prev` → `AppGraph.runner.prev()`、それ以外は何もしない
- HomeActivity の `info` 呼び出しを上の「問い合わせの削減」に合わせる

### パネルの最小幅(PanelContentTest の変更)
- `panel_narrowWidth_noOverflow` の `Box(modifier = Modifier.size(576.dp, 800.dp))` を `516.dp` に、`assertTrue("$tag right (${bounds.right}) > 576.dp", bounds.right <= 576.dp)` を `516.dp`(メッセージ・比較とも)に変える。**計画の指定による変更で、この2か所以外は変えない**。他のテストの 576dp はそのまま

## 手順(TDD)

1. テストを書く:
   - `T/ui/apps/AppListScreenTest.kt`(純粋な JUnit。`buildAppList` を試す。ownPkg は `"com.gt8d.launcher"`)
     - `appList_alwaysIncludesStockLauncher`: 入力 `[AppListEntry("com.google.android.youtube","com.google.android.youtube.HomeActivity","YouTube")]` → 結果の先頭が `STOCK_LAUNCHER_ENTRY`、件数 2。さらに入力に `AppListEntry("com.android.launcher3","com.android.launcher3.Launcher","Quickstep")` を足しても、`pkg == "com.android.launcher3"` の項目は `STOCK_LAUNCHER_ENTRY` の1件だけ
     - `appList_sortedByLabel`: 入力のラベルが `"YouTube"`・`"chrome"`・`"Apple Music"`(pkg はそれぞれ `y`・`c`・`a`、cls は `y.Main`・`c.Main`・`a.Main`)→ 結果のラベルが `["純正ランチャー", "Apple Music", "chrome", "YouTube"]`
     - `appList_excludesOwnPanel`: 入力に `AppListEntry("com.gt8d.launcher","com.gt8d.launcher.ui.panel.PanelActivity","GT8D パネル")` と YouTube → 結果に `pkg == "com.gt8d.launcher"` が無い、件数 2
   - `T/apps/StockLauncherTest.kt`(Robolectric、`@Config(sdk = [35])`)
     - `stockLauncherIntent_explicitComponent`: `component` が `ComponentName("com.android.launcher3","com.android.launcher3.uioverrides.QuickstepLauncher")`、`action == Intent.ACTION_MAIN`、`flags and FLAG_ACTIVITY_NEW_TASK != 0`
   - `T/ui/gesture/TwoFingerSwipeTest.kt`(純粋な JUnit、density = 2f)
     - `twoFinger_swipeLeft_next`: `classify(2, -200f, 10f, 2f) == Next`
     - `twoFinger_swipeRight_prev`: `classify(2, 200f, -10f, 2f) == Prev`
     - `twoFinger_oneFinger_none`: `classify(1, -400f, 0f, 2f) == None`
     - `twoFinger_threeFingers_none`: `classify(3, -400f, 0f, 2f) == None`
     - `twoFinger_tooShort_none`: `classify(2, -159f, 0f, 2f) == None`(79.5dp)、`classify(2, -160f, 0f, 2f) == Next`(80dp)
     - `twoFinger_vertical_none`: `classify(2, -200f, 100f, 2f) == None`(傾き 0.5 ちょうどは None)
   - `PanelContentTest` の2か所を 516dp に変える
2. `/home/munde/tools/gradle-run.sh test` を実行し、新しいテストが失敗する(またはコンパイルできない)ことを確認する
3. 実装する
4. `/home/munde/tools/gradle-run.sh test assembleDebug` で全部通るまで直す(最大3回。通らなければ失敗したテスト名とエラーの先頭30行を報告して止まる)

## 報告

`test assembleDebug` の結果(実行件数と失敗件数)、作成・変更したファイル名、516dp のテストのために `PanelContent.kt` などを変えたかどうか(変えたなら何を)、**決めたこと**。
