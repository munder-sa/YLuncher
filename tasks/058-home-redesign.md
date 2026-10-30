# Task 058: ホーム画面を作り直す(時計+日付・画面セットのカード・ミニプレイヤー)

最初に `tasks/RULES.md` を読み、従うこと。仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` **§4.4(2026-10-31 作り直し)**・§4.9・§9 に従う。
UI の決まり: 文言は日本語、ダーク固定、配色は `Palette`、タッチ領域は最小 72dp、本文 20sp 以上。迷ったら Material Design 3 と iOS のヒューマンインターフェースガイドラインに合わせる。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

## 背景
ユーザーがモック(`build/mock/home-mock-D.png`。司令塔の手元。仕様 §4.4 に言葉で書いた)から選んだ形:
左の領域 = 上に「時計(大きく)+右に日付」、その下に**画面セットのカード**(押すとそのセットが開く)。右の約 1/3 = **Apple Music などのミニプレイヤー**。右端 = ドック(今のまま)。
ホームの左のアプリのアイコンの一覧はなくす。**カードが少ないときは、カードの並びを縦の中央にそろえる**(上に寄ると格好悪い、とユーザー)。

## 作成・変更するファイル(これで全部)
新規:
- `M/ui/home/HomeSetCards.kt`(画面セットのカードの並び)
- `M/ui/home/HomeMiniPlayer.kt`(ミニプレイヤー)
- `M/ui/home/HomeClockHeader.kt`(時計+日付)
- `T/ui/home/HomeSetCardsTest.kt`、`T/ui/home/HomeMiniPlayerTest.kt`、`T/ui/home/HomeClockHeaderTest.kt`
変更:
- `M/ui/home/HomeContent.kt`、`M/ui/home/HomeActivity.kt`(ホームの中身の組み立てと、セットを開く・新しいセット・停止中のアプリを開くの接続だけ)
- `M/ui/layout/PanelLayout.kt`(下の「配置の計算」の関数を足すだけ)
- `M/settings/ListsScreen.kt`(「ホームのアイコン」の区分を出さないだけ)
- テスト(下の「既存テストの扱い」で許した変更だけ): `T/ui/home/HomeActivityUiTest.kt`、`T/ui/layout/LayoutMatrixTest.kt`、`T/ui/layout/PanelLayoutTest.kt`、`T/settings/DailyScreensTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.ui.home.* --tests com.gt8d.launcher.ui.layout.* --tests com.gt8d.launcher.settings.*`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様
### 配置の計算(PanelLayout に足す。純粋な関数)
- `homePlayerWidthDp(totalDp: Float): Float` = clamp(total / 3, 420, 600)
- `homeSetColumns(leftAreaDp: Float): Int` = カードの最小幅 300dp・間隔 24dp で、2列入れば 2、入らなければ 1
- ホームを「左の領域・ミニプレイヤー・ドック」にする境目は今の `homePanelWidthDp` と同じ 840dp(未満はパネルだけ。今のまま)

### 時計+日付(HomeClockHeader)
- `Row(verticalAlignment = 下揃え(ベースライン))`: 時計は 047 の `FitClock`(最大 150sp、高さの上限は左の領域の高さの 22%)、右に日付 `OneLineText`(例「10月31日(金)」、`Locale.JAPAN` の `M月d日(E)`、灰色 `Palette.Muted`、32sp 程度)。日付は時計と同じく定期的に更新する(日付が変わったら変わる)。
- 幅が足りず日付が入らないときは、日付を時計の下に回す(折り返しや省略で崩さない)。

### 画面セットのカード(HomeSetCards)
- 入力: セットの一覧(`LauncherConfig.screenSets`)、アプリ名・アイコンの引き方、今のセットの番号(`AppGraph.runner.currentIndex`。1 始まり。0 = ホーム)、押したとき(`onOpen(index)`)、「＋ セットを追加」(`onAdd()`)。
- カード: 角丸 28dp、背景 `Palette.Background1`。上に**配置図**(左右のブロックの幅を比率どおりに。アプリのブロックは暗い面にアイコン 56dp、パネルのブロックは緑の枠と「パネル」の文字)、下にセット名(`OneLineText`、「左のアプリ名 | 右のアプリ名」、右がパネルなら「| パネル」)。高さは 160dp 程度で固定。押せる領域はカード全体。タグ `home_set_<id>`。
- **今のセット**のカードは緑(`Palette.Accent`)の枠 4dp。`Modifier.semantics { selected = true }` を付ける。
- 最後に「＋ セットを追加」のカード(点線の枠、緑の文字。タグ `home_set_add`)。
- 並べ方: `homeSetColumns(幅)` 列の格子。**格子全体の高さが使える高さより低いときは、格子を縦の中央に置く**。高いときは上から並べ、縦にスクロールする(`verticalScroll`)。
- 押したとき: `AppGraph.runner.opening.value != null` でも `runner.open(index)` に渡してよい(SetRunner が行き先の更新として扱う)。
### ミニプレイヤー(HomeMiniPlayer)
- 背景 `Palette.Panel`、角丸 32dp、内側の余白 32dp。タグ `home_player`。
- 再生中(`MediaWatcher.nowPlaying` が null でない): 上にアプリのアイコン(36dp)+アプリ名(灰色、`OneLineText`)、ジャケット(幅いっぱいの正方形。無ければ今のパネルと同じ代わりの画像)、曲名(`OneLineText`、SemiBold、28sp 程度。タグ `player_title`)、アーティスト(`OneLineText`、灰色。タグ `player_artist`)、進行バー(今のパネルの `MediaCard` と同じシーク)、経過/残り時間、ボタン列(☆(あるときだけ。タグ `player_fav`)、戻る `player_prev`、再生/一時停止 `player_play`(白い円で大きく 96dp 以上)、送る `player_next`)。ボタンは 72dp 以上。操作は今の `MediaAction` と `MediaWatcher` を使う。
- 縦が足りないときは、ジャケットを縮める(ジャケットは残りの高さに収まる正方形)。ボタン列と曲名は必ず見える。
- 停止中: ジャケットの位置に「停止中に表示するアプリ」(`config.idleApp`)の大きなアイコン(160dp)と名前、「タップで開く」。押すとそのアプリを開く(タグ `player_idle_app`)。未インストールなら今のパネルと同じ警告。
- 今のパネルの `MediaCard` は変えない(パネルでは今のまま使う)。

### ホームの組み立て(HomeContent / HomeActivity)
- 840dp 以上: `Row { 左の領域(weight 1: HomeClockHeader + HomeSetCards) ; HomeMiniPlayer(幅 homePlayerWidthDp) ; Dock(今のパネルと同じ部品・同じ動き) }`。外側の余白・間隔は 16〜24dp。帯の逃げ(047 の `stripSafePadding`)は今のまま根に付ける。
- 840dp 未満: 今のまま(パネルの配置だけ)。
- **アプリ一覧(ドック)を開いた状態などの状態は、幅が変わっても保つ**(047 と同じ決まり。ドックの状態を持つ部品を同じ呼び出し位置に置く)。
- 「＋ セットを追加」とドックの「分割」は、今のホームの `onNewSet`(新しいセットの編集)と同じ。カードを押したら `AppGraph.runner.open(index)`。停止中のアプリは今の起動の仕方(`activityLauncher.startApp`、暖かい記録)と同じ。

### 設定
- `ListsScreen` の「ホームのアイコン」の区分を出さない(設定データの `homeApps` は消さない・変えない)。ほかの区分は今のまま。

## 既存テストの扱い(司令塔が許可する変更。これ以外の既存テストは変えない。変えた行は報告に全部並べる)
- `HomeActivityUiTest` のうち、**ホームの左のアイコン(`icon_$pkg`)を対象にしているテスト**(`assertLaunch` を使うもの、`homeActivity_packageAdded_…`・`…packageRemoved_…`・`…packageChanged_…`・`…resume_refreshes…`):
  確かめたい中身(パッケージの追加・削除・変更・再開でアイコンと名前が新しくなる)を、**画面セットのカード**(左がそのパッケージのセット)で確かめる形に書き換える。名前はカードのセット名、アイコンの有無は `contentDescription` で見る。
  `assertLaunch`(アイコンを押してアプリが起動し暖かい記録がつく)は、カードを押すと `runner.open(index)` が呼ばれる確認に置き換える(起動の仕方が変わったため)。テスト名は中身に合わせて変えてよい(旧名→新名を報告に書く)。
- `LayoutMatrixTest`: `home_grid` を見ている所を、840dp 以上で `home_sets` と `home_player` があり、839dp で無い確認に置き換える。重ならない確認の対象に `home_sets`・`home_player`・`dock`・`clock` を入れる。
- `DailyScreensTest` の「ホームのアイコン」の区分(`lists_home_*`)を確かめるテストは、区分を出さなくなったため、「`lists_home_add` が無い」確認に置き換える。

## テスト(先に書き、修正前に失敗することを確かめる)
- `PanelLayoutTest`: `homePlayerWidth_cases`(1000→420、1291→430.33(誤差 0.01)、2000→600)、`homeSetColumns_cases`(623→1、624→2)。
- `HomeSetCardsTest`(Robolectric + Compose): `cards_showNamesAndPanelLabel`、`currentSet_isSelected_withAccentBorder`、`click_opensIndex`、`add_callsOnAdd`、
  `fewCards_areVerticallyCentered`(高さ 800dp の領域にカード1枚+追加 → 格子の上の余白と下の余白の差が 2dp 以内)、`manyCards_scrollFromTop`(カード 8枚 → 最初のカードの上端が領域の上端、最後のカードに `performScrollTo` で届く)、
  `columns_followWidth`(幅 600dp で1列、700dp で2列)。
- `HomeMiniPlayerTest`: `playing_showsTitleArtistAndControls`、`play_pause_next_prev_fav_callActions`、`noFavorite_hidesFav`、`idle_showsIdleApp_andOpens`、`shortHeight_keepsTitleAndControlsVisible`(高さ 480dp でも `player_title`・`player_play` が見える)、`longTitle_isOneLine`。
- `HomeClockHeaderTest`: `clockAndDate_sameRow_whenWide`、`date_movesBelow_whenNarrow`(どちらも日付が1行で、はみ出さない)、`dateFormat_japanese`。
- 既存のテストは、上で許した変更以外は変えずに通ること。

## 手順(TDD)
1. テストを書く。2. 対象テストを実行し、修正前に失敗することを確かめる(どのテストがどう失敗したかを報告に書く)。
3. 実装する。4. 対象テストが全部通るまで直す(最大3回)。通ったら `assembleDebug`。
- Robolectric で Activity の中の Compose の状態を書き換えて確かめるテストでは、確かめる前に `Snapshot.sendApplyNotifications()` を呼ぶ。

## この Task でしないこと
- パネル(分割の右)・設定のほかの画面・ブラウザの変更。`homeApps` の設定データの削除・移行。全テストの実行、実機の操作、git のコミット等。

## 報告
実行したコマンドと結果(件数・失敗件数、修正前の失敗の内容、assembleDebug)、作成・変更したファイル、変えた既存テストの行(旧名→新名)、**決めたこと**。
