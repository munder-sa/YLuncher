# Task 107: アプリ一覧を「登録したアプリだけ」に、残りは「すべてのアプリ」から、編集で登録・解除

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
ユーザーの決定(モック `/mnt/f/YLuncher/build/mock/dock-applist/dock-applist.png` の下の2枠。HTML は同じ場所の `.html`。今の実機は `ref/applist.png`)。ドックの「アプリ一覧」ボタン(`dock_applist`)で開く `AppListScreen`(`ui/apps/AppListScreen.kt`、`PanelScreen` の `showAppList`)を変える。今は端末の全部のアプリ(約 45 個)が名前順に並び、車の中で探しにくい。

## 変更・作成するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/config/Model.kt`(`LauncherConfig.appListPkgs` を足すだけ)
- `app/src/main/java/com/gt8d/launcher/ui/apps/AppList.kt`
- `app/src/main/java/com/gt8d/launcher/ui/apps/AppListScreen.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt`(`AppListScreen` を呼ぶ所だけ)
- テスト: `app/src/test/java/com/gt8d/launcher/ui/apps/AppListScreenTest.kt`、create `app/src/test/java/com/gt8d/launcher/ui/apps/AppListTest.kt`、`app/src/test/java/com/gt8d/launcher/config/ConfigCodecTest.kt`、`app/src/test/java/com/gt8d/launcher/ui/panel/PanelScreenTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.ui.apps.*' --tests 'com.gt8d.launcher.config.*' --tests 'com.gt8d.launcher.ui.panel.*'`

## 仕様
1. 設定の形: `LauncherConfig` の最後に `val appListPkgs: List<String>? = null`(null = まだ作っていない)。`CONFIG_VERSION` は上げない。
2. `AppList.kt` に純粋な関数:
   - `fun seedAppList(config: LauncherConfig, recent: Set<String>, launchable: Set<String>): List<String>`: 最初の登録 = `panelApps` ∪ `dockApps` ∪ 画面セットのアプリ ∪ `homeApps` ∪ `recent` のうち `launchable` にあるもの(重なりなし、順は問わない)。
   - `fun registeredEntries(all: List<AppListEntry>, registered: Set<String>): List<AppListEntry>`: `all`(`buildAppList` の結果)のうち pkg が `registered` にあるもの、`all` の並び(名前順)のまま。純正ランチャーの項目も登録されていれば出る。
   - `fun toggleRegistered(list: List<String>, pkg: String): List<String>`: あれば外し、無ければ最後に足す。
3. `AppListScreen` の画面(引数を足す: `registered: Set<String>`、`onToggle: (String) -> Unit`。今の引数は残す):
   - 状態は 2 つ+編集: 「登録」(既定)/「すべて」/「すべて」の編集中。開くたびに「登録」から(画面の中の状態。`rememberSaveable` でよい)。
   - **登録**: 見出し「アプリ一覧」、右に「編集」(Outlined、鉛筆のアイコン、タグ `applist_edit`)・今の「設定」・今の「×」。グリッドは `registeredEntries`。最後に「すべてのアプリ」のタイル(他のアイコンと同じ大きさの枠に、輪郭 1dp `Palette.Control` の角丸 12dp の四角に「⋯」、下に「すべてのアプリ」`Palette.Muted`。タグ `applist_all`)。押すと「すべて」へ。登録が 0 なら、グリッドの上に「登録したアプリはありません。「編集」で登録できます」(Muted)と「すべてのアプリ」のタイルだけ。
   - **すべて**: 見出し「すべてのアプリ」、左に ←(タグ `applist_back`、「登録」へ戻る)、右に「編集」・「×」。グリッドは今の全部のアプリ(今どおり)。押すと開く(今どおり)。Android の戻るは「登録」へ戻る。
   - **編集中**(登録・すべてのどちらの「編集」からでも、「すべて」の一覧で): 見出し「すべてのアプリ — 押して登録・外す」、右に「完了」(白い filled、暗い文字、タグ `applist_done`)。各アイコンの右上に丸(直径 24dp。登録済み = 白い丸に暗い ✓、未登録 = 白い輪郭 2dp の空の丸。タグ `applist_check_<pkg>`、`selected` の意味を付ける)。未登録のアプリは透明度 0.5。押すと `onToggle(pkg)`(アプリは開かない)。完了・Android の戻る → 編集に入る前の状態(登録 / すべて)へ戻る。
   - 「×」と「設定」(長押しで設定、を含む)の動きは今のまま。
   - 見出しの行は 72dp 以上、ボタンは 72dp 以上、文字は `Dims.BodyText` 以上。狭い幅(360dp)でも見出しの行が崩れない(題は 1 行で省略)。
4. `PanelScreen`(`AppListScreen` を呼ぶ所):
   - `registered` = `config.appListPkgs` を Set に。ただし null なら、`seedAppList(config, recent = emptySet(), launchable = 今の一覧の pkg)` を作って `AppGraph.config.update { it.copy(appListPkgs = seed) }` で 1 回だけ保存し、その間もその seed を表示に使う(最近使ったアプリの `recent` は、後で別の Task がつなぐ。今は空でよい。`seedAppList` の引数として残す)。
   - `onToggle` = `AppGraph.config.update { it.copy(appListPkgs = toggleRegistered(it.appListPkgs ?: seed, pkg)) }`(今の設定の保存の書き方・失敗時のログに合わせる)。
   - アンインストールされたアプリは `buildAppList` に無いので出ない(リストからは消さなくてよい)。

## テスト(足す。既存は弱めない。今の `AppListScreenTest` は「すべて」の状態か、全部を登録した状態で今のまま通す形に合わせ、報告する)
- `AppListTest`: seed の和集合・launchable 外を除く・重なりなし、`registeredEntries` の絞り込みと並び、`toggleRegistered` の追加・削除。
- `AppListScreenTest`: 既定が登録だけ+「すべてのアプリ」のタイル、タイル → すべて(全部)、← と Android の戻るで登録へ、編集 → 丸と `selected`・未登録の透明度、押すと `onToggle` が呼ばれアプリは開かない、完了で元の状態へ、登録 0 の案内、すべての一覧で押すと開く、360dp で見出しが崩れない・ボタン 72dp。
- `ConfigCodecTest`: `appListPkgs` の無い JSON が null で読める、リストが往復する。
- `PanelScreenTest`: `appListPkgs == null` で開くと seed が 1 回保存される、トグルで設定が変わる。

## この Task でしないこと
- ドック(Task 106 が別に行う)。設定画面への項目の追加。git のコミット等。

## 報告
変更点、テストの結果(件数)、置き換えた既存テスト、**決めたこと**。
