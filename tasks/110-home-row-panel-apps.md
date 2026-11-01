# Task 110: ワイドのホーム下段のアプリの段を「パネルのアイコン」にする

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
Task 108 で、ワイドのホームの左の列の一番下の段(`HomeAppRow`)をアプリ一覧の「登録アプリ」にした。ユーザーの新しい決定: この段は **パネルのアイコン**(`LauncherConfig.panelApps`、パネルの「アプリ」表示の `IconGrid` と同じもの・同じ並び、最大 `MAX_PANEL_APPS` = 5)にする。最後に、パネルと同じく「＋」(パネルのアイコンの設定を開く)を置く。
アプリ一覧(登録・すべて・編集)、パネルの `IconGrid`、ドック、設定画面は変えない。最初の登録の保存(`appListPkgs == null` のときの seed の `LaunchedEffect`)も今の場所・中身のまま残す(アプリ一覧のため)。

## 変更・作成するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeAppRow.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelContent.kt`(`PanelState` / `PanelActions` の所だけ)
- テスト: `app/src/test/java/com/gt8d/launcher/ui/home/HomeAppRowTest.kt`、`app/src/test/java/com/gt8d/launcher/ui/panel/PanelScreenTest.kt`、`app/src/test/java/com/gt8d/launcher/ui/layout/LayoutMatrixTest.kt`、`app/src/test/java/com/gt8d/launcher/ui/home/HomeActivityUiTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.ui.panel.*' --tests 'com.gt8d.launcher.ui.layout.*' --tests 'com.gt8d.launcher.ui.apps.*'`

## 仕様
1. `HomeAppRow(apps: List<AppInfo>, onLaunch: (String) -> Unit, onAdd: (() -> Unit)?, modifier: Modifier = Modifier, state: LazyListState = rememberLazyListState())`
   - 今の見た目のまま: `LazyRow`、タグ `home_apps`、高さ 96dp、アイコン **96dp 固定**、間隔 24dp、左寄せ、指で自由に横スクロール(スナップしない)。アイテムのキーは付けない(Task 108 後の修正で外した。付けると並びが変わったとき先頭からずれる)。
   - アプリのマス: タグ・内容説明・押したときの動きは `IconGrid` の `AppCell` に合わせる。入っているアプリはタグ `home_app_<pkg>`、内容説明はアプリ名、押すと `onLaunch(pkg)`。入っていないアプリはタグ `home_missing_<pkg>`、内容説明「<アプリ名>(アプリが見つかりません)」、不透明度 0.4、アイコンは白黒(`setToSaturation(0f)`)、押しても開かない。
   - アイコンが無いときは今どおり頭文字の四角(地 `Palette.Panel`、白い文字、角丸 12dp、96dp)。
   - 最後のマス: `onAdd != null` なら「＋」のマス(タグ `home_app_add`、96dp、内容説明「パネルのアイコンを追加」、`IconGrid` の `AddCell` と同じ色(地 `Palette.Panel`・文字 `Palette.PanelAccent`・角丸 12dp の四角)。押すと `onAdd()`)。
   - アプリが 0 個でも `onAdd != null` なら「＋」だけの段を出す。アプリ 0 個かつ `onAdd == null` なら何も描かない。
2. `HomeWideContent`: `HomeAppRow(state.panelApps.take(MAX_PANEL_APPS), actions.onLaunch, actions.onAdd, Modifier.fillMaxWidth(), state = homeAppRowState)`。`state.homeEntries.isNotEmpty()` の条件はやめ、常に呼ぶ(中で上の規則に従う)。並び(時計 → セット → 段)は今のまま。
3. 開き方: ホームからは `actions.onLaunch`(HomeActivity の `onLaunchApp` = `startApp(pkg, adjacent = false)`)。パネルの `IconGrid` と同じ経路。
4. 使わなくなるもの: `PanelState.homeEntries`、`PanelActions.onLaunchEntry` は削る。`PanelScreen` の `launchEntry` はアプリ一覧の `onLaunch` で今どおり使う。`registeredEntries` の呼び出しで段のためだけのものは削る(関数自体は `AppList.kt` に残す)。

## テスト(既存は弱めない。登録アプリを段に出す前提のテストは、パネルのアイコンで同じ場面を確かめる形に置き換え、どれを置き換えたか報告する)
- `HomeAppRowTest`: 5 個+「＋」が 96dp・間隔 24dp・左寄せ、押すと `onLaunch(pkg)`、頭文字、入っていないアプリ(`home_missing_<pkg>`、内容説明、押しても `onLaunch` されない)、「＋」で `onAdd`、0 個+`onAdd` で「＋」だけ、0 個+`onAdd == null` で何も無い、狭い幅(300dp)で横にスクロールでき最後(「＋」)まで届き 96dp のまま。
- `PanelScreenTest`: ワイドのホームで、段に `panelApps` が同じ並びで最大 5 個出る(6 個目以降は出ない)、登録アプリ(`appListPkgs`)にだけあるアプリは段に出ない、段から押すと `onLaunchApp(pkg)` が呼ばれる、「＋」で `onEditLists` が呼ばれる、スクロールの位置がアプリ一覧を開いて閉じた後も残る(今のテストを置き換え)。
- `LayoutMatrixTest`: ワイドのホームで、段(`home_apps`)が画面の中・セット・時計・ミニプレイヤー・ドックと重ならない(パネルのアイコン 0 / 3 / 5 個、うち入っていないアプリ 1 個を含む場面も)。
- `HomeActivityUiTest`: 「段から開く」テストを、パネルのアイコンで `startApp(pkg, adjacent = false)` 相当になることを確かめる形に。

## この Task でしないこと
- アプリ一覧・パネルの `IconGrid`・ドック・設定画面・設定データの変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、置き換えた既存テスト、**決めたこと**。

## 追記(実装前レビューの反映。本文より優先)
- テストの置き換え(RULES の例外としてこの Task で許可): 今回の仕様変更(段の中身が登録アプリ → パネルのアイコン、「＋」の追加)に直接対応する期待値・データ・テスト名の変更は許可する。無関係な検証は残す。
- `PanelScreenTest` の seed の 1 回だけの保存のテスト(212 行付近): seed・保存内容・更新時刻の検証は残し、「登録を外すと段から消える」の部分は「登録を外してもパネルのアイコンは段に残る」に置き換える。「登録が空なら段なし」(264 行付近)は「登録が空でもパネルのアイコンと「＋」が段に出る」に置き換える。
- 同 186 行付近(同じパッケージの 2 つの activity、非表示アプリ): ホームの段の部分だけを `panelApps` の並び・上限・`onLaunchApp` の確認に置き換え、後半のアプリ一覧の検証(非表示アプリを除く・2 activity・名前順)はそのまま残す。
- スクロールの位置が残るテスト(240 行付近): 別々の 5 パッケージを `panelApps` に入れ、段の幅が 696dp(= 96×6 + 24×5)未満になる画面の大きさにして、「＋」(index 5)まで動かす。再描画後・アプリ一覧の開閉後に位置が完全に同じことは今どおり確かめる。
- `LayoutMatrixTest`: `PanelActions.onAdd` は必ずあるので、0 個でも段と「＋」が出る。0 個のときも段と「＋」が画面の中で他と重ならないことを確かめる。12 個の場面は残し、「先頭 5 個と「＋」に届き、6 個目以降は無い」を確かめる。
- 入っていないアプリの見た目: テストでは、アイコンありの入っていないアプリで不透明度 0.4 と白黒の `ColorFilter` が付くことを確かめる(`IconGrid` の既存テストにやり方があれば合わせる。無ければ semantics の内容説明とタグ・押しても開かないことの確認でよいとし、報告に書く)。パッケージの追加・削除で段が変わるテストは今回は要らない。
- 「＋」の形は、指示書の 96dp・角丸 12dp の四角を優先する(パネルの `AddCell` の 56dp の円とは違ってよい)。
