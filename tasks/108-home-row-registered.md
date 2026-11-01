# Task 108: ホーム下段のアプリの段を「登録アプリ」にし、自由に横スクロール

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
Task 105 でワイドのホームの左の列の一番下に `HomeAppRow`(パネルのアプリ `panelApps` を最大 5 つ、1 行)を足した。ユーザーの決定: この段に並べるのは **アプリ一覧の「登録アプリ」**(Task 107 の `LauncherConfig.appListPkgs`、アプリ一覧の「登録」と同じもの・同じ並び)にし、**指で自由に横スクロール**できるようにする(ページ送りではない)。パネルの「アプリ」(`panelApps`、`IconGrid`)は変えない。

## 変更・作成するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeAppRow.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelContent.kt`(`PanelState` / `PanelActions` に足す所だけ)
- テスト: `app/src/test/java/com/gt8d/launcher/ui/home/HomeAppRowTest.kt`、`app/src/test/java/com/gt8d/launcher/ui/panel/PanelScreenTest.kt`、`app/src/test/java/com/gt8d/launcher/ui/layout/LayoutMatrixTest.kt`、`app/src/test/java/com/gt8d/launcher/ui/home/HomeActivityUiTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.ui.panel.*' --tests 'com.gt8d.launcher.ui.layout.*' --tests 'com.gt8d.launcher.ui.apps.*'`

## 仕様
1. 登録アプリの決め方を 1 か所に: `PanelScreen` で `registered = config.appListPkgs ?: seed`(今のアプリ一覧の所と同じ)を、アプリ一覧を開いていないときも求める。最初の保存(`appListPkgs == null` のときの seed の保存。今は `if (showAppList)` の中の `LaunchedEffect`)を `if (showAppList)` の外に出し、ホーム・パネルが出ているときにも 1 回だけ走るようにする(中身・条件・ラムダの中の null の確認は今のまま)。
2. 段の中身: `registeredEntries(environment.appListEntries, registered)`(アプリ一覧の「登録」と同じ並び。純正ランチャーが登録されていれば先頭)。`PanelState` に `homeEntries: List<AppListEntry> = emptyList()`、`PanelActions` に `onLaunchEntry: (AppListEntry) -> Unit = {}` を足す。`onLaunchEntry` は、今のアプリ一覧の `onLaunch`(純正ランチャーは `stockLauncherIntent`、ほかは component を `onLaunchComponent` か `AndroidActivityLauncher(context).startComponent(component, adjacent = inSplit)`)と同じ処理を 1 つの関数にまとめて両方から使う(アプリ一覧の方は今どおり開いた後に一覧を閉じる)。
3. `HomeAppRow(entries: List<AppListEntry>, iconFor: (String) -> ImageBitmap?, onLaunch: (AppListEntry) -> Unit, modifier)`:
   - `LazyRow`、アイコンは **96dp 固定**(幅で縮めない)、間隔 24dp、左寄せ、指で自由に横スクロール(スナップしない)。入りきらないときは右端のアイコンが途中で切れて見えてよい(続きがある手がかり)。
   - アイコンが取れないときは今どおり頭文字の四角(地 `Palette.Panel`、白い文字、角丸 12dp)。内容説明はアプリ名。タグ `home_app_<pkg>`(同じパッケージの別の activity があればタグも複数)。キーは `"pkg/cls"`。
   - 押すと `onLaunch(entry)`。スクロールの位置はホームの再描画で保つ(`rememberLazyListState`)。
   - 0 件なら何も描かない(`HomeWideContent` でも出さない)。入っていないアプリの扱いは要らない(登録アプリは今入っているものだけ)。
4. `HomeWideContent`: `HomeAppRow(state.homeEntries, { appInfo(it).icon }, actions.onLaunchEntry, Modifier.fillMaxWidth())`。`panelApps.take(MAX_PANEL_APPS)` を渡すのはやめる。並び(時計 → セット → 段)・高さ 96dp は今のまま。
5. ホームのアプリの段から開いたときは全画面(`adjacent = false`。ホームは `inSplit = false`)。

## テスト(足す。既存は弱めない。`panelApps` を段に渡していた前提のテスト(5 つ・幅による縮み・入っていないアプリ)は、新しい仕様で同じ場面(並ぶ・押すと開く・はみ出さない)を確かめる形に置き換えて報告する)
- `HomeAppRowTest`: 96dp 固定・間隔 24dp、12 個で横にスクロールでき最後まで届く(届いたとき全体が段の中)、狭い幅(300dp)でも 96dp のまま、押すと `onLaunch(entry)`、頭文字、0 個で何も無い。
- `PanelScreenTest`: ワイドのホームで、段に `appListPkgs` の登録アプリが一覧と同じ並びで出る、`appListPkgs == null` でも seed が出て 1 回保存される、登録を外すと段からも消える、段から押すと `adjacent = false` で component が開く(今の確かめ方に合わせる)、純正ランチャーが登録されていれば段から開ける。
- `LayoutMatrixTest`: ワイドのホームで、段(`home_apps`)が画面の中・セット・時計・ミニプレイヤー・ドックと重ならない(登録 0 / 3 / 12 個)。
- `HomeActivityUiTest`: 105 で足した「段から開く」テストを、登録アプリで同じことを確かめる形に。

## この Task でしないこと
- アプリ一覧の画面・パネルのアプリ・ドック・設定画面の変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、置き換えた既存テスト、**決めたこと**。

## 追記(実装前レビューの反映。本文より優先)
- 型: 表示用は `val registered: Set<String> = (config.appListPkgs ?: seed).toSet()`。トグルの保存は今どおりリスト(`it.appListPkgs ?: seed`)。
- 既存テスト `appList_nullSeedsOnceToggleSurvivesRecomposeAndReopen`(PanelScreenTest)は、「パネルを出すと seed の保存が済む(一覧を開く前に待つ)→ その後の再描画・一覧の開閉では保存し直さない」を確かめる形に変えてよい(RULES の例外としてこの Task で許可)。
- 「1 回だけ保存」= 保存に成功した後は、再描画・一覧の開閉・ホームの再表示で保存し直さない。保存前の表示は履歴なしの seed、保存後は保存した値(最近 30 日を含む)で、内容が変わってよい。空のリスト `[]` は上書きしない(今のまま)。
- `LayoutMatrixTest` の今のワイドのホームの場面(パラメータの表と別の viewport のテスト)は、`homeEntries` に登録アプリを入れる形に直し、今の画面の大きさ・文字の倍率・許可の帯の条件はそのまま。そのうえで 0 / 3 / 12 個を足す。
- スクロールの確かめ方: 段に `performScrollToIndex`(または `performScrollToKey`)で最後へ動かし、最後のアイコン全体が段の中にあることを確かめる。別に、段の上での横のスワイプ(`performTouchInput { swipeLeft() }`)で最初のアイコンの位置が動くことも確かめる。
- スクロールの位置は、`PanelScreen` の中の、アプリ一覧で早く return する所より前で `rememberLazyListState()` を作って `HomeAppRow` に渡す(引数 `state: LazyListState = rememberLazyListState()` を足す)。アプリ一覧を開いて閉じた後も位置が残ることをテストする。
