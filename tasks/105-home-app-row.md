# Task 105: ホーム(分割していないとき)にアプリの段を足し、セットを横長カードにする(案B)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
ユーザーの要望: 分割していないときのホームから、切り替えなしでアプリを(全画面で)開きたい。ユーザーがモックで **案B** を選んだ: `/mnt/f/YLuncher/build/mock/home-apps/home-apps.png`(左下の枠。HTML は同じ場所の `home-apps.html`)。今の実機は `/mnt/f/YLuncher/build/mock/home-apps/ref/home.png`。
案B: 時計の下にセット(高さ 104dp の横長カード、2 列)、左の列の一番下にアプリの段(パネルの「アプリ」と同じリスト `panelApps`、最大 5 つ、1 行)。時計・ミニプレイヤー・ドックは今のまま。
対象はワイドのホーム(`HomeWideContent` と、そこで使う `HomeSetCards`)だけ。パネル(`PanelContent`・`PanelSetCards`)は変えない。

## 変更・作成するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeSetCards.kt`
- create `app/src/main/java/com/gt8d/launcher/ui/home/HomeAppRow.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt`(`HomeWideContent` の中だけ)
- `app/src/test/java/com/gt8d/launcher/ui/home/HomeSetCardsTest.kt`
- create `app/src/test/java/com/gt8d/launcher/ui/home/HomeAppRowTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/PanelScreenTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/layout/LayoutMatrixTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/HomeActivityUiTest.kt`(壊れたときに合わせる所だけ)

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.ui.panel.*' --tests 'com.gt8d.launcher.ui.layout.*'`

## 仕様
1. `HomeSetCards`(引数は今のまま):
   - 列の数は今どおり `PanelLayout.homeSetColumns(幅)`(2 列 / 1 列)。間隔は縦横 24dp(今のまま)。
   - 各カード: 高さ **104dp 固定**(領域に合わせて伸び縮みしない)、角丸 24dp、地 `Palette.Background1`、左右の内側の余白 16dp、中身は縦の中央。横に: 番号(`Palette.Muted`、`Dims.BodyText`、幅 24dp で中央ぞろえ)→ 12dp → 左のアイコン 48dp → 4dp → 右のアイコン 48dp → 16dp → 名前の列(残りの幅)。
   - アイコン: パネルの側は `PanelIcon` 48dp(左の側なら `mirrored = true`)。アプリはアイコンの画像 48dp。入っているがアイコンが取れない → 頭文字の四角(地 `Palette.Panel`、白い文字、角丸 12dp)。入っていない → 頭文字の四角(地 `Palette.Control`、`Palette.Muted` の文字)、内容説明は「<名前>(アプリが見つかりません)」。アイコンの箱のタグは今どおり `home_set_<id>_left` / `home_set_<id>_right`。
   - 名前の列: 2 行。1 行目 = 左の名前、2 行目 = 右の名前(パネルなら「パネル」)。どちらも白・`Dims.BodyText`・1 行で省略(`OneLineKey` を付ける)。入っていないアプリの行は「見つかりません」(`Palette.Muted`)。
   - 今のセット(`currentIndex == 番号`): 白い輪郭 3dp。`selected` と `HomeSetHighlight` は今どおり。押すと `onOpen(番号)`。タグ `home_set_<id>`。カード全体の内容説明に `numberedSetLabel` 相当(「1  YouTube | パネル」)を付け、今までどおり名前で見つけられるようにする。
   - 比率の絵(`SetBlock` の幅の比)はやめる。
   - 「セットを追加」: 見た目は今の `AddSetCard` のまま。`sets.size % 列の数 != 0` なら最後の段の空いたマスに高さ 104dp で、そうでなければ最後の段の下に全幅・高さ 72dp で。0 セットなら全幅 72dp の追加だけ。タグ `home_set_add`。
   - 並びは**上寄せ**(縦の中央にしない)。入りきらないときは `HomeSetCards` の中だけ縦にスクロール(上から始まる)。
2. 新しく `HomeAppRow(apps: List<AppInfo>, onLaunch: (String) -> Unit, modifier: Modifier = Modifier)`(`HomeAppRow.kt`、タグ `home_apps`):
   - 1 行に左寄せで並べる。アイコンの大きさ = `min(96dp, (幅 − (個数−1)×8dp) / 個数)`、ただし 72dp を下回らない。間隔 = `min(48dp, (幅 − 個数×大きさ) / (個数−1))`、ただし 8dp を下回らない(1 個なら間隔なし)。それでも入らない(72dp・8dp でもはみ出す)ときは横にスクロール。
   - 各アイコン: 大きさの正方形全体が押せる。画像はその大きさいっぱい。アイコンが取れないときは頭文字の四角(地 `Palette.Panel`、白い文字 `Dims.BodyText`、角丸 12dp)。タグ: 入っている → `home_app_<pkg>`、入っていない → `home_missing_<pkg>`。
   - 入っていないアプリ: `IconGrid` と同じ扱い(透明度 0.4、画像があれば白黒、押しても `onLaunch` を呼ばない、内容説明「<名前>(アプリが見つかりません)」)。入っているアプリの内容説明は名前。
   - 押すと `onLaunch(pkg)`(ホームでは今の `actions.onLaunch` = `HomeActivity` の `startApp(pkg, adjacent = false)`。全画面で開く。ここは変えない)。
3. `HomeWideContent`: 左の列の `Column` を、(許可の帯)→ 時計 → `HomeSetCards(Modifier.fillMaxWidth().weight(1f))` → `HomeAppRow(state.panelApps, actions.onLaunch, Modifier.fillMaxWidth())` に。`state.panelApps` が空なら `HomeAppRow` を出さない(セットが下まで使う)。列の間隔は今の 16dp。アプリの段は左の列の一番下にくっつく。
4. 見た目・動きのほかは変えない(時計・ミニプレイヤー・ドック・パネル・設定)。

## テスト(足す。既存は弱めない。今の大きなカードの形(比率の幅・56dp のアイコン・縦の中央・136〜160dp の高さ・名前の中央ぞろえ・「見つかりません」がマスの中)を確かめていた既存のテストは、新しい形で同じ場面を確かめる形に置き換え、どれを置き換えたか報告する)
- `HomeSetCardsTest`: カードの高さ 104dp・角丸 24dp・間隔 24dp、番号・アイコン 2 つ・名前 2 行(左・右、パネルは「パネル」)、長い名前が 1 行で省略されカードからはみ出さない、文字の倍率 1.3 でも 104dp の中に収まる、左パネルの PanelIcon が反転・右は反転しない、入っていない/アイコンが取れない の頭文字の四角の色、今のセットの輪郭と `selected`、押すと番号、5 セットで追加が空いたマス(104dp)、6 セット・1 列 3 セットで追加が全幅 72dp、0 セットで追加だけ、少ないときは上寄せ、8 セットで縦にスクロールでき最後の追加まで届く、列の数が幅に従う。
- `HomeAppRowTest`: 5 つで各 96dp・左寄せ・間隔の計算どおり、押すと `onLaunch(pkg)`、入っていないアプリは押しても呼ばれない・透明度・内容説明、アイコンなしは頭文字、狭い幅(例 400dp)で大きさが縮み 72dp を下回らず、入りきらないときは横にスクロールして最後のアイコンまで届く、1 つだけでも崩れない。
- `PanelScreenTest`(ワイドのホーム): `home_apps` が `home_sets` の下にあり、重ならず、左の列の下端にそろう。`panelApps` が空なら `home_apps` が無い。
- `LayoutMatrixTest`: ワイドのホームの組み合わせで、`home_apps` と各アイコンが画面の中にあり、時計・`home_sets`・ミニプレイヤー・ドックと重ならない。

## この Task でしないこと
- パネル・設定・幕。アプリの開き方(`startApp`)の変更。設定画面のリストの変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、置き換えた既存テスト、**決めたこと**。

## 追記(実装前レビューの反映。本文より優先)
- アプリの段に渡すのは `state.panelApps.take(MAX_PANEL_APPS)`(パネルと同じ件数)。
- `HomeAppRow` の大きさ・間隔は、外側で取った有限の幅(`BoxWithConstraints` の `maxWidth`)から計算し、その内側を横スクロールにする。0 件なら何も描かない(`HomeWideContent` でも出さない)。1 件なら間隔の計算をしない。
- 横スクロールのとき(例: ワイドになった直後の全幅 840dp で左の列が約 300dp): 段の表示領域は常に画面の中。各アイコンは横にスクロールすると届き、届いた時点でアイコン全体が段の表示領域の中にある。テストはスクロール前後で分けて確かめる。
- セットの領域の高さは、時計・日付の折り返し・許可の帯で変わる。合格の条件は「全部が常に見える」ではなく「カードは 104dp のまま、縦にスクロールして最後の追加まで届き、アプリの段・時計と重ならない」。
- 名前の 2 行は左寄せ、行の高さは文字の既定のまま。「見つかりません」も省略されてよい。カード全体の内容説明は `numberedSetLabel`(入っていないアプリも元の名前のまま)。
- 既存テストの置き換え(RULES の「期待値・テスト名を変えない」の例外として、この Task で許可する): `HomeSetCardsTest` の、比率の幅・56dp のアイコン・名前の中央ぞろえ・縦の中央(`fewCards_areVerticallyCentered`、`noSets_showsOnlyAddRow` の中央の判定を含む)・カードの高さ(136〜160dp、159dp・150dp などの値)・結合した名前の `onNodeWithText` での検索・「見つかりません」がマスの中、を確かめている所は、新しい形(104dp・上寄せ・カードのタグ+内容説明・左右の名前の行)で同じ場面を確かめる形に変えてよい。テスト名が合わなくなったものは改名してよい。押す・選択・入っていないアプリ・追加の場所の確認は残す。置き換えたものは全部報告する。`HomeActivityUiTest` で結合した名前を探している所も同じ扱い。
- 見た目の確かめ方は、`PanelSetCardsTest`(Task 104)で同じことを確かめている方法に合わせる(セマンティクスのキーか、描画した画素)。ホーム用のキーが要るなら `HomeSetCards.kt` / `HomeAppRow.kt` に足してよい(パネルのキーは流用しない)。今のセットは「`selected`/`HomeSetHighlight`」と「白い 3dp の輪郭」を別々に確かめる。
- `HomeActivityUiTest` に足す: `panelApps` を入れたホームで、`home_app_<pkg>` を押すと `startApp(pkg, adjacent = false)` 相当で開く(今のテストのランチャーの確かめ方に合わせる)。
- `LayoutMatrixTest` に足す(今のパラメータの表は増やさず、ワイドのホーム用の別のテストでよい): 1291×726dp(GT8D 相当)と 840×600dp で、文字の倍率 1 / 1.3、許可の帯 あり/なし、セット 5 / 6 / 8、アプリ 0 / 1 / 5 の組み合わせ(全部の掛け合わせでなくてよい。各値が少なくとも 1 回、GT8D 相当では 5 セット・5 アプリ・帯なし・倍率 1 と 1.3 を必ず含む)。`home_apps`・各アイコン(スクロール後)・各カード(スクロール後)が画面の中(外側の 16dp の余白の内側)、時計・`home_sets`・ミニプレイヤー・ドックと重ならない。GT8D 相当・5 アプリ・帯なしでは、5 つのアイコンがスクロールなしで全部見えること。
