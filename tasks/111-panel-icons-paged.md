# Task 111: パネルのアイコンを最大 10 個にし、パネルの「アプリ」は 2 段のページ送りにする

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
ユーザーの決定(2026-11-01、モック `build/mock/panel-icons/panel-icons.png` の案B):
- パネルのアイコン(`LauncherConfig.panelApps`)の上限を 5 → **10** にする。
- パネルの「アプリ」表示のアイコン一覧(`IconGrid`)は、**最大 2 段のページ送り**(横にスワイプでページを切り替え、下に点)。並びは読む順(左→右、上→下)。ミニプレイヤー(メディアカード)の位置・大きさは変えない。
- ワイドのホームの下段(`HomeAppRow`、Task 110)は今のまま(1 行・横に自由スクロール)で、上限が 10 になるだけ。

## 変更・作成するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/config/Model.kt`(`MAX_PANEL_APPS` だけ)
- `app/src/main/java/com/gt8d/launcher/ui/panel/IconGrid.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelContent.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt`(ページの状態を持ち上げる所だけ)
- テスト: `app/src/test/java/com/gt8d/launcher/ui/panel/IconGridTest.kt`、`app/src/test/java/com/gt8d/launcher/ui/panel/PanelContentTest.kt`、`app/src/test/java/com/gt8d/launcher/ui/panel/PanelScreenTest.kt`、`app/src/test/java/com/gt8d/launcher/ui/layout/LayoutMatrixTest.kt`、`app/src/test/java/com/gt8d/launcher/ui/home/HomeAppRowTest.kt`、`app/src/test/java/com/gt8d/launcher/settings/DailyScreensTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.ui.panel.*' --tests 'com.gt8d.launcher.ui.layout.*' --tests 'com.gt8d.launcher.settings.*' --tests 'com.gt8d.launcher.config.*'`

## 仕様
1. `MAX_PANEL_APPS = 10`。設定画面「パネルのアイコン」の追加の上限・ホームの段・パネルの一覧は、すべてこの定数を使う(今どおり)。設定データの形・`CONFIG_VERSION` は変えない。
2. `IconGrid`(パネルの「アプリ」表示。狭いホームのパネル配置も同じ):
   - マスは今どおり: アプリのマス(88dp、アイコン 64dp、入っていないアプリは 0.4・白黒・押しても開かない、タグ `icon_<pkg>` / `missing_<pkg>`)と、最後の「＋」のマス(`icon_add`)。マスの大きさ・形・色・間隔 8dp は変えない。
   - 列数は今どおり `PanelLayout.columns(幅)`。1 ページ = **列数 × 2 段**。マス(アプリ+「＋」)を読む順(左→右、上→下)に 1 ページ目から詰める。
   - マスの数が 1 ページに収まるとき: ページ送りなし・点なし。見た目は今と同じ(マスが 1 段に収まれば 1 段、2 段なら 2 段)。
   - 2 ページ以上のとき: `HorizontalPager`(`androidx.compose.foundation.pager`)。各ページは 2 段の高さに固定(最後のページのマスが少なくても高さは同じ。空きは左上から詰め、残りは空ける)。ページの下に 8dp 空けて点の列(高さ 24dp、中央寄せ。点は直径 8dp・間隔 8dp、今のページは白、ほかは白の不透明度 0.4)。点は押せない(飾り。内容説明「ページ 1/2」の形でまとめて 1 つ)。ページはスナップ(`HorizontalPager` の既定)。タグ: 一覧全体 `icon_grid`(今どおり)、ページ送り `icon_pager`、点の列 `icon_pager_dots`。
   - ページの状態は、`PanelScreen` の中の、アプリ一覧で早く return する所より前で `rememberPagerState` を作り、`PanelContent` → `IconGrid` に渡す(引数 `pagerState: PagerState? = null`、null なら中で作る)。アプリ一覧の開閉・再描画・「アプリ | セット」の切り替えの後もページが残る。幅が変わってページの数が減ったら、最後のページへ寄せる(範囲外のページのままにしない)。
   - 縦のスクロール(`PanelContent` のメディアカードと一覧の列の `verticalScroll`)は今どおり残す(高さが足りない画面では今どおり縦にスクロールできる)。ページ送りの横のスワイプと、縦のスクロールがぶつからないこと(横のスワイプでページが変わり、縦のスワイプで列がスクロールする)。
3. ワイドのホームの段(`HomeAppRow`)は変えない。`state.panelApps.take(MAX_PANEL_APPS)` で 10 個まで出るようになるだけ。

## テスト(足す。既存は弱めない。上限 5 を前提にした期待値は 10 に合わせて置き換え、どれを置き換えたか報告する)
- `IconGridTest`: (幅は列数 3 になる大きさ、例 300dp)
  - 5 個+「＋」= 6 マス → 2 段、点なし、`icon_pager` なし。
  - 10 個+「＋」= 11 マス → 2 ページ、1 ページ目に 1〜6 個目が読む順(1 段目 1,2,3、2 段目 4,5,6)、点あり(内容説明「ページ 1/2」)、横にスワイプすると 2 ページ目に 7〜10 個目と「＋」、点が「ページ 2/2」。
  - 2 ページ目に「＋」があり押せる、入っていないアプリが 2 ページ目にあっても 0.4・押しても開かない。
  - 幅を広げて列数 6(1 ページ 12 マス)にすると 11 マスが 1 ページに収まり、点が消える。2 ページ目にいた状態で広げても範囲外にならない。
  - ページの高さは 2 段で固定(2 ページ目のマスが 5 個でも 1 ページ目と同じ高さ)。
- `PanelContentTest` / `PanelScreenTest`: パネルで 10 個が 2 ページに分かれて出る、11 個目は出ない、2 ページ目にしてからアプリ一覧を開いて閉じても 2 ページ目のまま、「アプリ | セット」を切り替えて戻っても同じ。2 ページ目のアイコンを押すと今どおり反対側に開く(今の確かめ方に合わせる)。
- `LayoutMatrixTest`: パネルの今の場面(幅・高さ・文字の倍率)に、アイコン 10 個の場合を足す。一覧(`icon_grid`)・点がドック・時計・メディアカード・画面の外と重ならない。最小の大きさ(360dp × 400dp)でも崩れない(縦スクロールで点まで届く)。ワイドのホームの段は 10 個でも重ならない。
- `HomeAppRowTest`: 10 個+「＋」で最後まで横にスクロールでき、11 個目は渡されても出さない(`take` は呼び出し側なので、`PanelScreenTest` で 11 個のとき段に 10 個までを確かめる形でもよい)。
- `DailyScreensTest`: 「パネルのアイコン」に 10 個まで追加でき、10 個のとき追加の行が押せない。

## この Task でしないこと
- ドック・アプリ一覧・セットのカード・ワイドのホームの段の見た目の変更。設定データの形の変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、置き換えた既存テスト、**決めたこと**。

## 追記(実装前レビューの反映。本文より優先)
- **ページの状態の持ち方(本文の `rememberPagerState` の持ち上げは取りやめ)**: 持ち上げるのは「選んでいるページ番号」(`Int`)だけにする。
  - `PanelScreen` の、アプリ一覧で早く return する所より前で `val iconPage = rememberSaveable { mutableIntStateOf(0) }` を作り、`PanelContent(iconPage = iconPage)` → `IconGrid(page = iconPage.intValue, onPageSettled = { iconPage.intValue = it })` に渡す。
  - `PanelContent` は引数 `iconPage: MutableIntState? = null`。null のときは、「アプリ | セット」の分岐より前で `remember { mutableIntStateOf(0) }` を作って使う(セットへ切り替えても失わない)。
  - `IconGrid(…, page: Int = 0, onPageSettled: (Int) -> Unit = {})`: 中で、実際の幅から求めたページ数 `pages` で `rememberPagerState(initialPage = page.coerceIn(0, pages - 1)) { pages }` を作る。表示するページは `min(page, pages - 1)`。スワイプで止まったページ(`settledPage`)を `onPageSettled` で知らせる。`pages` が減って今のページが範囲外になったら、最後のページへ寄せる(`scrollToPage`)が、保存しているページ番号は書き換えない(広げて 1 ページになり、また狭めたら元のページに戻ってよい)。マスが 1 ページに収まるときは `HorizontalPager` を作らず、`onPageSettled` も呼ばない。マスが 0(アプリ 0 個で `onAdd == null`)なら今どおり何も描かない。
  - ページ数は `IconGrid` の中の実際の幅(`BoxWithConstraints`)だけで決める。`PanelScreen` で画面の幅から計算しない。
- **テストの置き換え(RULES の例外としてこの Task で許可)**: 上限 5 → 10 に直接対応するデータ・期待値・テスト名の変更は許可する(例: `PanelContentTest.panel_limitsPanelAppsTo5` → 10、`PanelScreenTest` の上限のテスト、`LayoutMatrixTest` の `take(5)`・追加のマスの index・`drop(5)`)。5 個の配置・スクロールを確かめる既存テストは残し、10 個の場面は**足す**。
- **固定の高さのテスト**: 3 列で 7 マス(6 個+「＋」)→ 2 ページ目が 1 マスでも、ページの高さが 184dp(88×2+8)であることを確かめる。
- **統合テストの幅**: `PanelContentTest` / `PanelScreenTest` で「10 個が 2 ページ」を確かめるときは、パネル全体 516dp(一覧の実幅が 4 列になる)を使い、1 ページ目 8 マス・2 ページ目 3 マス(9・10 個目と「＋」)を確かめる。3 列の読む順は `IconGridTest`(300dp)で確かめる。
- **LayoutMatrix**: 「スクロールの前後とも一覧・点がドック・時計の矩形に描画で重ならない」「縦にスクロールすると点の列全体が画面の中に出る」を確かめる。点は `icon_grid` の内側なので、両者の重なりは比べない。高さはレイアウトの大きさで比べる(切り取られた表示の矩形で比べない)。
- **Pager のテストの手順**: ページを動かした後は `rule.waitForIdle()`(スナップが終わるまで)してからノードを取り直す。別のページのアイコンは「無い」と断定せず、今のページと表示状態を確かめる。横のスワイプは、まず `icon_pager` を縦の表示の中に出してから `performTouchInput { swipeLeft() }`。縦のスワイプで列がスクロールしページが変わらないテストも 1 つ。幅の変更は同じ composition の中で(`mutableStateOf` の幅を変える)行う。縦のスクロール位置を取るときは縦の軸を持つノードに絞る。
- **反対側に開く**: 今回の範囲は「2 ページ目のアイコンでも今の起動のコールバック(`onLaunch` / `onLaunchApp`)に同じパッケージが渡る」まで。Activity 側は変えないので確かめない。
- **DailyScreensTest**: 候補のアプリを 11 個以上用意し、10 個まで追加でき、10 個のとき残りの候補が押せないことを確かめる。
