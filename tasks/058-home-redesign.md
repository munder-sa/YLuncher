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
- `M/ui/panel/PanelScreen.kt`(下の「状態の持ち主」のための変更だけ)
- 新規 `M/ui/panel/SeekBar.kt`(今の `MediaCard` の中のシークの部品を取り出して共通にする。**`MediaCard` の見た目と動きは変えない**)、`M/ui/panel/MediaCard.kt`(取り出した部品を使うだけ)
- `M/sets/SetRunner.kt`(今のセットの番号を Compose から見られるようにするだけ。下の「今のセット」)
- テスト(下の「既存テストの扱い」で許した変更だけ): `T/ui/home/HomeActivityUiTest.kt`、`T/ui/layout/LayoutMatrixTest.kt`、`T/ui/layout/PanelLayoutTest.kt`、`T/settings/DailyScreensTest.kt`、`T/ui/panel/PanelActivityTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.ui.home.* --tests com.gt8d.launcher.ui.layout.* --tests com.gt8d.launcher.settings.* --tests com.gt8d.launcher.ui.panel.* --tests com.gt8d.launcher.sets.SetRunnerTest`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様
### 配置の計算(PanelLayout に足す。純粋な関数)
- `homePlayerWidthDp(totalDp: Float): Float` = clamp(total / 3, 360, 600)
- `homeSetColumns(leftAreaDp: Float): Int` = 2列の判定はカード 300dp・間隔 24dp(624dp 以上で 2)、未満は 1。**1列のときはカードを左の領域の幅いっぱいに縮めてよい**(最小 240dp)。840dp のとき左の領域は約 330dp(=840−360−88−余白)になる
- ホームを「左の領域・ミニプレイヤー・ドック」にする境目は今の `homePanelWidthDp` と同じ 840dp(未満はパネルだけ。今のまま)

### 状態の持ち主(最優先)
- アプリ一覧の開閉とその位置は今 `PanelScreen` が持ち、`PanelScreen` を同じ呼び出し位置に置くことで、幅が変わっても状態を保っている(047)。これを保つため、**`PanelScreen` に引数 `homeWide: Boolean = false` を足し、true のときは「左の領域・ミニプレイヤー・ドック」の配置を `PanelScreen` の中で描く**(アプリ一覧を開いているときは、今どおりアプリ一覧を描く)。`HomeContent` は幅を測り、`homeWide = 幅 ≥ 840dp` で `PanelScreen` を同じ位置から呼ぶ。分割の右のパネル(`PanelActivity`)は `homeWide = false` のまま。
- 引数は既定値つきで、最後の引数(末尾ラムダの `onLaunchApp`)の後ろに置かない。
- 「許可が必要」のバナー(今の `PanelContent`)は、ホームの広い配置では左の領域の一番上(時計の上)に出す。

### 今のセット
- `SetRunner` に `val currentIndexFlow: StateFlow<Int>` を足し、`currentIndex` が変わる所(setter)で更新する。カードの緑の枠はこれを `collectAsState` で見る。ほかの `SetRunner` の動きは変えない。

### 時計+日付(HomeClockHeader)
- 時計は 047 の `FitClock`(最大 150sp、高さの上限は左の領域の高さの 22%)。大きさは左の領域の幅で決める(日付のために縮めない)。日付は「10月31日(金)」(`Locale.JAPAN`、`M月d日(E)`)、灰色 `Palette.Muted`、32sp、**省略・折り返しをしない**(`softWrap = false`、`maxLines = 1`、`TextOverflow.Clip`。`OneLineText` は省略記号を付けるので使わない)。
- 横に並べられる(時計の幅+24dp+日付の幅 ≦ 左の領域の幅。`rememberTextMeasurer` で測る)なら、時計の右に**文字のベースライン揃え**(`Modifier.alignByBaseline()`)で並べる。並べられなければ、日付を時計の下に置く。
- 時刻と日付は定期的に更新する(日付が変わったら変わる)。テストのため、今の日時を渡せる引数(`now: () -> LocalDateTime`)を持たせる。

### 画面セットのカード(HomeSetCards)
- 入力: セットの一覧(`LauncherConfig.screenSets`)、アプリ名・アイコンの引き方、今のセットの番号(`AppGraph.runner.currentIndex`。1 始まり。0 = ホーム)、押したとき(`onOpen(index)`)、「＋ セットを追加」(`onAdd()`)。
- カード: 角丸 28dp、背景 `Palette.Background1`。上に**配置図**(左右のブロックの幅を比率どおりに。アプリのブロックは暗い面にアイコン 56dp、パネルのブロックは緑の枠と「パネル」の文字)、下にセット名(`OneLineText`、「左のアプリ名 | 右のアプリ名」、右がパネルなら「| パネル」)。高さは 160dp 程度で固定。押せる領域はカード全体。タグ `home_set_<id>`。
- **今のセット**のカードは緑(`Palette.Accent`)の枠 4dp。`Modifier.semantics { selected = true }` を付け、テスト用に独自の semantics の印(例 `HomeSetHighlight = true`)も付ける(枠の描画そのものは司令塔が実機の画像で見る)。
- 左のアプリが未インストールのカードは、そのアイコンの所を今の未インストールの表示(灰色など)にし、`contentDescription` を「<名前>(アプリが見つかりません)」にする。押すと今どおり `runner.open(index)` に渡す(SetRunner が「左が見つからない」として扱う)。
- 最後に「＋ セットを追加」のカード(点線の枠、緑の文字。タグ `home_set_add`)。
- 並べ方: `homeSetColumns(幅)` 列の格子。**格子全体の高さが使える高さより低いときは、格子を縦の中央に置く**。高いときは上から並べ、縦にスクロールする(`verticalScroll`)。
- 押したとき: `AppGraph.runner.opening.value != null` でも `runner.open(index)` に渡してよい(SetRunner が行き先の更新として扱う)。
### ミニプレイヤー(HomeMiniPlayer)
- 背景 `Palette.Panel`、角丸 32dp、内側の余白 32dp。タグ `home_player`。
- **`MediaWatcher.nowPlaying` が null でなければ「再生中の表示」**(一時停止中 `playing = false` でも同じ表示で、真ん中のボタンが再生の印になる)。**null のときだけ「停止中の表示」**。
- 再生中の表示: 上に再生中のアプリ(`NowPlaying.pkg`)のアイコン(36dp。`AppGraph.catalog.icon`)+アプリ名(灰色、`OneLineText`。`catalog.label`)、ジャケット(幅いっぱいの正方形。無ければ今のパネルと同じ代わりの画像)、曲名(`OneLineText`、SemiBold、28sp 程度。タグ `player_title`)、アーティスト(`OneLineText`、灰色。タグ `player_artist`)、進行バー(**`SeekBar.kt` に取り出した共通の部品**。ドラッグ中は送らず、離したとき1回だけ送る、両端は 0 と曲の長さにそろえる、という今の `MediaCard` の動きのまま。曲の長さが不明・0 以下ならバーと時間を出さない。タグ `player_seek`)、経過/残り時間、ボタン列(☆(あるときだけ。タグ `player_fav`)、戻る `player_prev`、再生/一時停止 `player_play`(白い円で大きく 96dp 以上)、送る `player_next`)。ボタンは 72dp 以上。操作は今の `MediaAction` と `MediaWatcher` を使う。
- 縦が足りないときは、ジャケットを縮める(残りの高さに収まる正方形。0 まで縮めてよい)。アーティストが null なら出さない。**高さ 400dp でも曲名とボタン列は必ず見える**(それでも入らなければアプリ名の行と時間の行を消す)。
- 停止中: ジャケットの位置に「停止中に表示するアプリ」(`config.idleApp`)の大きなアイコン(160dp)と名前、「タップで開く」。押すとそのアプリを開く(タグ `player_idle_app`。起動と暖かい記録は今のホームのパネルの `onLaunchApp` と同じ)。アイコンが無ければ今の `MediaCard` と同じ頭文字の代わりの表示(160dp)。未インストールなら今の `MediaCard` と同じ「アプリが見つかりません」を出し、押せないようにする(「タップで開く」は出さない)。
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
  `assertLaunch`(アイコンを押してアプリが起動し暖かい記録がつく)は、カードを押すと `runner.open(index)` が呼ばれる確認に置き換える(起動の仕方が変わったため)。表示用の設定と runner が読む設定を同じにするため、このファイルの既存のテストにある `SetRunnerDeps` の差し替えの書き方を使ってよい。テスト名は中身に合わせて変えてよい(旧名→新名を報告に書く)。
- `LayoutMatrixTest`: ホームを描く所は、**広い配置(840dp 以上)と狭い配置で確かめる対象を分けてよい**。広い配置では `home_sets`・`home_player`・`dock`・`clock` があり、互いに重ならず、`media_card`・`icon_grid` が無いこと。狭い配置(839dp 以下)は今の確認のまま。`HomeContent` の呼び出し(通常とリサイズの両方)は新しい引数に合わせて直してよい。状態の保持のテスト(アプリ一覧を開いたまま幅を変える)はそのまま通ること。
- `DailyScreensTest` の「ホームのアイコン」の区分(`lists_home_*`)を確かめるテストは、**確かめている操作(並べ替え・削除・タッチの大きさ・重複の追加の防止)をパネルの区分(`lists_panel_*`)に移し**、加えて「`lists_home_add` が無い」「`homeApps` が変わらない」確認を足す。
- `PanelActivityTest` のうち `lists_home_add` を見ている確認(208 行付近)は、`lists_panel_add` を見る形にしてよい。

## テスト(先に書き、修正前に失敗することを確かめる)
- `PanelLayoutTest`: `homePlayerWidth_cases`(840→360、1291→430.33(誤差 0.01)、2000→600)、`homeSetColumns_cases`(623→1、624→2)。
- `HomeSetCardsTest`(Robolectric + Compose): `cards_showNamesAndPanelLabel`、`currentSet_isSelected_withAccentBorder`、`click_opensIndex`、`add_callsOnAdd`、
  `fewCards_areVerticallyCentered`(高さ 800dp の領域にカード1枚+追加 → 格子の上の余白と下の余白の差が 2dp 以内)、`manyCards_scrollFromTop`(幅 700dp・高さ 500dp の領域にカード 8枚 → 最初のカードの上端が領域の上端、最後のカードに `performScrollTo` で届く)、
  `columns_followWidth`(幅 600dp で1列、700dp で2列)。
- `HomeMiniPlayerTest`: `paused_showsPlayerWithPlayIcon`、`seek_sendsOnceOnRelease`、`unknownDuration_hidesSeek`、`idle_uninstalled_showsWarning_notClickable`、`height400_keepsTitleAndPlay`(☆あり)、`playing_showsTitleArtistAndControls`、`play_pause_next_prev_fav_callActions`、`noFavorite_hidesFav`、`idle_showsIdleApp_andOpens`、`shortHeight_keepsTitleAndControlsVisible`(高さ 480dp でも `player_title`・`player_play` が見える)、`longTitle_isOneLine`。
- `HomeClockHeaderTest`: `clockAndDate_sameRow_whenWide`(幅 700dp)、`date_movesBelow_whenNarrow`(幅 330dp)(どちらも日付が1行で、はみ出さない)、`dateFormat_japanese`(2026-10-31 → 「10月31日(金)」)。
- `SetRunnerTest`: `currentIndexFlow_followsOpenAndHome`。
- 既存のテストは、上で許した変更以外は変えずに通ること。

## 手順(TDD)
1. テストを書く。2. 対象テストを実行し、修正前に失敗することを確かめる(どのテストがどう失敗したかを報告に書く)。
3. 実装する。4. 対象テストが全部通るまで直す(最大3回)。通ったら `assembleDebug`。
- Robolectric で Activity の中の Compose の状態を書き換えて確かめるテストでは、確かめる前に `Snapshot.sendApplyNotifications()` を呼ぶ。

## この Task でしないこと
- パネル(分割の右)・設定のほかの画面・ブラウザの変更。`homeApps` の設定データの削除・移行。全テストの実行、実機の操作、git のコミット等。

## 報告
実行したコマンドと結果(件数・失敗件数、修正前の失敗の内容、assembleDebug)、作成・変更したファイル、変えた既存テストの行(旧名→新名)、**決めたこと**。
