# Task 067: 狭いパネルの切り替えを「アプリ | セット」のセグメントにし、セット表示で画面セットのカードを出す

最初に `tasks/RULES.md` を読み、従うこと。

## 背景(ユーザーの判断 2026-10-31)
065 は意図の取り違えだった。正しくは:
- 「アプリ」表示(既定)= 今の「時計 + ミニプレイヤー(MediaCard) + アイコン一覧」
- 「セット」表示 = 「時計 + **画面セットのカード一覧**」(ホームの `HomeSetCards` と同じカード。押すとそのセットを開く。最後に「＋ セットを追加」)
切り替えは、時計の**下**に置く**セグメント**(ユーザーがモック案1を選んだ: `build/mock/toggle-mock.html` の案1)。065 の丸いトグル(音符のアイコン)・再生中の印・横並び/下の行の判定はやめる。

## 変更するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/config/Model.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelContent.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/PanelContentTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/PanelScreenTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/layout/LayoutMatrixTest.kt`
- `app/src/test/java/com/gt8d/launcher/config/ConfigCodecTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.ui.panel.*' --tests 'com.gt8d.launcher.ui.layout.*' --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.config.*'`

## 仕様
1. 保存: `LauncherConfig.panelMediaVisible` を消し、`val panelView: PanelView = PanelView.Apps` を足す(`@Serializable enum class PanelView { Apps, Sets }` を Model.kt に)。古い JSON(`panelMediaVisible` を含む・含まない)はどちらも `Apps` で読める(`ignoreUnknownKeys = true` なので)。`CONFIG_VERSION` は変えない。
2. `PanelState.mediaVisible` → `val view: PanelView = PanelView.Apps`。`PanelActions.onToggleMedia` → `val onSelectView: (PanelView) -> Unit = {}`。セット表示に要るものとして `PanelState` に `val sets: List<ScreenSet> = emptyList()`、`val currentSetIndex: Int = 0`、`val setAppInfo: (String) -> AppInfo = { AppInfo(it, it, null, false) }`(名前は「決めたこと」で変えてよい)、`PanelActions` に `val onOpenSet: (Int) -> Unit = {}`、`val onNewSet: () -> Unit = {}` を足す(既定値付きで既存の呼び出しを壊さない)。
3. `PanelScreen`(狭い表示の分岐だけ): 065 の `pending`・Mutex・保存の仕組みは残し、値を `panelView` に置き換える(押した値を書く)。`sets = config.screenSets`、`currentSetIndex = AppGraph.runner.currentIndexFlow` の値、`setAppInfo = { catalog.info(it, installedPkgs) }`、`onOpenSet = { AppGraph.runner.open(it) }`(ワイドのホームと同じ。開いている最中 `AppGraph.runner.opening.value != null` なら無視)、`onNewSet = onNewSet` を渡す。ワイド(`HomeWideContent`)は変えない。
4. `PanelContent`:
   - 時計は 065 以前のとおり単独の行(`FitClock(clockText, clockHeight)`)。065 の `inlineToggle` の判定・`PanelMediaToggle`・再生中の印は消す。
   - 時計の下に**セグメント**(testTag `panel_view_segment`): 全幅、角丸いっぱい(`RoundedCornerShape(50)`)、背景 `Palette.Background1`、内側 4dp、2つの区画(testTag `panel_view_apps` / `panel_view_sets`)を 4dp 間隔で等幅。各区画は高さ 72dp(= `Dims.MinTouch`)、アイコン 28dp + 8dp + 文字(`OneLineText`、`Dims.BodyText`)を中央に。選んでいる区画は背景 `Palette.PanelAccent`(白)・文字とアイコン `Palette.Background0`・太字。選んでいない区画は背景なし・文字とアイコン白。
     - 「アプリ」: `Icons.Default.Apps`、文字「アプリ」。
     - 「セット」: 左右に並んだ2つの角丸の四角(左 6 : 右 4、ホームのセットのカードと同じ比)を描いた独自の `ImageVector`(24x24 の viewport。名前 `SetIcon` を PanelContent.kt の中に private で)。文字「セット」。
     - semantics: セグメント全体に `selectableGroup()`、各区画に `Modifier.selectable(selected = ..., role = Role.Tab, onClick = { actions.onSelectView(...) })`。選んでいる区画をもう一度押しても何もしない(onSelectView を呼ばない)。
   - その下: `view == Apps` なら今の中身(許可の案内・MediaCard・アイコン一覧。スクロール)。`view == Sets` なら許可の案内(必要なら)と `HomeSetCards(state.sets, state.setAppInfo, state.currentSetIndex, actions.onOpenSet, actions.onNewSet, Modifier.fillMaxWidth().weight(1f))`。HomeSetCards は狭い幅で1列になる(既存の `PanelLayout.homeSetColumns`)。HomeSetCards.kt は変えない。
5. テスト(065 で足したトグルのテストは、この仕様に合わせて置き換える。それ以外の既存は弱めない):
   - `PanelContentTest`: (a) 既定で `panel_view_segment` が有り、`panel_view_apps` が selected、MediaCard とアイコン一覧が有り、両区画が 72dp 以上。(b) `panel_view_sets` を押すと `onSelectView(Sets)` が1回。(c) `view = Sets` で MediaCard・アイコン一覧が無く、`home_sets` と各セットのカード・`home_set_add` が有る。カードを押すと `onOpenSet(番号)`、追加を押すと `onNewSet`。(d) 選んでいる区画を押しても `onSelectView` は呼ばれない。
   - `PanelScreenTest`: セットを押すと config の `panelView` が `Sets` になり、カードが出る。作り直し(既存の host の作り方)の後も `Sets` のまま。ワイドのホームには `panel_view_segment` が無い。
   - `LayoutMatrixTest`: 狭い表示の既存の組み合わせ(360dp・文字倍率 1.3 を含む)で、セグメントの2区画が画面内・72dp 以上・時計と重ならず、文字が1行。Sets の組み合わせも足し、カードが画面内(スクロールで届く)・クリック要素が 72dp 以上(既存の判定に合わせる)。065 で足したトグル用の判定は消す。
   - `ConfigCodecTest`: `panelMediaVisible` を含む古い JSON と含まない JSON が `Apps` で読める。`Sets` の往復。065 の `panelMediaVisible` のテストは置き換える。
   - テストのスレッドから Compose の状態を書いたら `Snapshot.sendApplyNotifications()`。

## この Task でしないこと
- HomeSetCards・HomeMiniPlayer・MediaCard・ワイドのホームの変更。git のコミット等。

## 報告
変更点、テストの結果、**決めたこと**。

## 補足(点検の反映)
- 列数は既存の幅の判定(`PanelLayout.homeSetColumns`)に従い1〜2列(狭いホームの広い幅では2列になってよい)。
- `onNewSet` にも開いている最中のガード(`AppGraph.runner.opening.value != null` なら無視)を付ける(PanelScreen 側で)。番号は HomeSetCards が渡す1始まりのまま runner に渡す。
- 低い高さ(400dp、許可の案内あり)でも、カードの領域が 136dp 以上残ることを確かめる。残らないなら、Sets のときは許可の案内をカードと同じスクロールの中(カードの上)に入れる。どちらにしたか「決めたこと」へ。
- `LayoutMatrixTest`: セグメントはスクロールの外なので `performScrollTo` の対象から外し `assertIsDisplayed` で確かめる。「狭い表示に home_sets が無い」判定は Apps のときだけにする。存在・重なり・対象のタグを Apps/Sets で分ける。
- `PanelScreenTest`: Sets でカードを押すと runner の open がその番号で呼ばれる(既存のテストで runner を差し替え・観測している方法に合わせる。無ければ `AppGraph.runner.currentIndexFlow` の変化か、DiagLog などで観測できる方法を報告で決めてよい)。開いている最中は無視されること。
