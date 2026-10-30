# Task 047: パネルとホームを、どの幅・比率でも崩れない配置にする

最初に `tasks/RULES.md` を読み、従うこと。仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` §4.4・§4.5・**§4.9(2026-10-31 追加)**・§9 に従う。
UI の決まり(計画の Global Constraints): 文言は日本語、ダーク固定、配色は `Palette`、タッチ領域は最小 72dp(`Dims.MinTouch`)、本文 20sp 以上(`Dims.BodyText`)。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

## 背景(司令塔とユーザーが実機で確かめた事実、2026-10-31)
- 画面は 1920x1080px、全画面で w1291dp h726dp。分割の右は 4:6 で約 516dp、5:5 で約 645dp、6:4 で約 774dp、3:7 で約 387dp。仕切りのドラッグで任意の幅になる。
- 仕切りをドラッグしても PanelActivity は作り直されず、Compose が新しい幅で描き直す(状態は保たれる)。
- ホームの画面を分割の右に出すと、ホームの左右の重み 0.55:0.45・アイコン 4 列・時計 96sp が固定のため、アイコン名が1〜2文字ずつ折り返し、時計が縦に崩れて読めない。
  再生カードの停止中アプリ名(「Apple Music」)も1文字ずつ縦に並ぶ。パネルでもアイコン名が折り返す。
- 壁紙 `wallpaper.png` の中央に「分割の模様」(角丸の板2枚+緑の線)があり、`ContentScale.Crop` で幅ごとに位置が変わって、部品の後ろでずれた板に見える。
- スワイプ帯(画面の左右端の重ね窓)の上のタッチは下の画面に届かない。帯の既定の幅は Task 048 で 48px に広げる。

## 作成・変更するファイル(これで全部)
新規作成:
- `M/ui/layout/OneLineText.kt` — 操作ラベル用の1行の Text(`maxLines = 1`、`softWrap = false`、`TextOverflow.Ellipsis`、既定 `Dims.BodyText`)
- `M/ui/layout/FitClock.kt` — 時計(下の「時計」)
- `M/ui/layout/Backgrounds.kt` — 無地のグラデーションの背景 `Modifier.launcherBackground()`(縦に #101016 → #0A0A0E)
- `M/ui/layout/StripInsets.kt` — 帯の逃げ(下の「帯の逃げ」)
- `M/ui/layout/PanelLayout.kt` — 幅から列数・ボタンの段数などを決める純粋な関数(下の「配置の計算」)
- `M/ui/home/HomeContent.kt` — ホームの中身の Composable(今の `HomeActivity.setContent` の中身を移す。テストで幅を変えて描くため)
- `T/ui/layout/PanelLayoutTest.kt`、`T/ui/layout/StripInsetsTest.kt`、`T/ui/layout/FitClockTest.kt`
- `T/ui/layout/LayoutMatrixTest.kt` — 幅・高さ・文字の大きさの組み合わせで、崩れないことを確かめる(下の「テスト」)
変更:
- `M/ui/panel/PanelContent.kt`、`M/ui/panel/MediaCard.kt`、`M/ui/panel/IconGrid.kt`、`M/ui/panel/Dock.kt`、`M/ui/panel/PanelScreen.kt`
- `M/ui/home/HomeActivity.kt`(中身を `HomeContent` に移し、背景と帯の逃げを付ける)
- `M/ui/panel/PanelActivity.kt`(壁紙の `Image` を `launcherBackground()` に替え、帯の逃げを付けるだけ)
- `M/edge/EdgeStripService.kt`(帯の矩形を公開するだけ。下の「帯の逃げ」)
- 既存のテスト `T/ui/panel/IconGridTest.kt`、`T/ui/panel/DockTest.kt`、`T/ui/panel/PanelContentTest.kt`、`T/ui/panel/MediaCardTest.kt`、`T/ui/panel/PanelScreenTest.kt`、`T/ui/home/HomeActivityUiTest.kt`、`T/ui/home/HomeActivityTest.kt`、`T/ui/panel/PanelActivityTest.kt`:
  **アイコン名を画面の文字で確かめている箇所(`hasText(名前)`・`onNodeWithText(名前)`、「アプリが見つかりません」の文字)だけ**を、`hasContentDescription(…)` での確認に置き換えてよい(名前を表示しない仕様に変わったため。司令塔が許可)。
  対象は**ホーム・パネルの格子とドックのアイコン名だけ**。**アプリ一覧(AppListScreen)の名前の文字の確認は変えない**(アプリ一覧は名前を出し続ける)。
  期待する中身(どのアプリか、未インストールの扱い、押したときの動き)は変えない。置き換えた行は報告に全部並べる。
  さらに `T/ui/panel/MediaCardTest.kt` の「停止中に `clock` が出る」確認(127 行付近)は、時計を再生カードから外すため、`PanelContentTest` で「停止中・再生中とも `clock` が出る」確認に**移してよい**(司令塔が許可)。
  ほかの変更は禁止。
- `M/ui/theme/Theme.kt`(`Palette` に背景の2色 `WallTop = Color(0xFF101016)`、`WallBottom = Color(0xFF0A0A0E)` を足すだけ)

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.ui.* --tests com.gt8d.launcher.edge.*`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様

### 配置の計算(PanelLayout.kt、パッケージ `com.gt8d.launcher.ui.layout`、Android に依存しない純粋な関数)
```kotlin
object PanelLayout {
    const val CELL_DP = 88f        // アイコンのセル(アイコン 64dp)
    const val GAP_DP = 8f
    const val DOCK_DP = 88f        // ドックの幅(固定)
    const val HOME_SPLIT_MIN_DP = 900f
    fun columns(availableDp: Float): Int                  // floor((available + GAP) / (CELL + GAP))、最低 1
    fun controlRows(availableDp: Float, buttons: Int): Int // 72dp のボタン buttons 個(間隔 GAP)が1段に入れば 1、入らなければ 2(2段で入らない幅は 2 のまま)
    fun homePanelWidthDp(totalDp: Float): Float?           // total >= 900 なら clamp(total*0.45, 516, 640)(丸めない)、未満なら null(パネルだけ)
}
```

### 時計(FitClock.kt)
- `@Composable fun FitClock(text: String, maxHeight: Dp, modifier: Modifier = Modifier, maxSp: Float = 96f, minSp: Float = 40f)`。タグ `clock`。
- 高さの上限 `maxHeight` は呼ぶ側が渡す(パネルでは「パネルの本体の高さの 30%」)。幅は `BoxWithConstraints` の `maxWidth`。
- `rememberTextMeasurer` を使い、**`"00:00"` を太字・等幅数字(`fontFeatureSettings = "tnum"`)で測って**、幅に収まり高さが `maxHeight` 以下になる最大の sp を、`maxSp` から 2sp 刻みで `minSp` まで下げて探す(時刻が変わっても大きさが揺れないよう、表示する時刻ではなく `"00:00"` で測る)。
- 40sp でも収まらなければ 40sp(はみ出しは司令塔が実機で見る。テストでは 360dp 幅でも収まることを確かめる)。
- 表示は `maxLines = 1`、`softWrap = false`、省略記号なし。文字の大きさ(fontScale)の設定を考慮して測る(`LocalDensity`)。
- 時刻の更新(今の 10 秒ごと)は FitClock の外(呼ぶ側)で行う。

### パネル(PanelContent.kt / MediaCard.kt / IconGrid.kt / Dock.kt)
1. 全体: `Row { 本体(weight 1f) ; Dock(幅 88dp 固定) }`、外側の余白 8dp、間隔 8dp。
2. 本体: 上から
   - **時計**(`FitClock`)。スクロールしない固定の段。停止中も再生中も出す(今は停止中だけ。仕様 §4.5 の変更)。
   - その下はスクロールする列: 許可のバナー(今のまま)→ 再生カード → アイコンの格子。
3. 再生カード:
   - 停止中: 停止中アプリのアイコン(48dp)+名前(`OneLineText`)。カードの中の幅が 300dp 未満なら名前を出さない(アイコンの `contentDescription` は名前)。時計はカードから外す(上の固定の段に移す)。
   - 再生中: ジャケット+曲名・アーティスト(`OneLineText`)+シークバー+経過/残り時間(今のまま)+操作ボタン。
     操作ボタン(前・再生/一時停止・次・☆(あるときだけ))は 72dp 角。`PanelLayout.controlRows(カードの中の幅, 個数)` が 2 なら2段にし、**上の段に ceil(個数/2) 個、下の段に残り**を並べる(例: 4個→2+2、3個→2+1)。どのボタンも消さない。
     カードの内側の余白は 8dp にする(狭い幅で 2個×72dp+間隔が確実に入るため)。
4. アイコンの格子: **名前を出さない**。セル 88dp・アイコン 64dp。列数は `PanelLayout.columns(使える幅)`(`IconGrid` の `columns` 引数は `Int? = null` にし、null なら自動。既存のテストが渡している値はそのまま使ってよい)。
   各アイコンの**押せるノード(セル)**に `contentDescription` = アプリ名を付ける(画像が無く頭文字を描く代替表示のときも同じ)。未インストールのアプリは、今の見分け方(灰色など)を残し、`contentDescription` を「<名前>(アプリが見つかりません)」にする。「＋」のマスは今のまま。
5. ドック: アプリはアイコンだけ(名前なし)。押せるノードに `contentDescription` = アプリ名。縦に入りきらなければスクロール(今のまま)。
5'. 停止中アプリが未インストールのときの今の警告の文字は残す(`OneLineText` にする)。
6. すべての横並びの文字は `OneLineText`。`OneLineText` は、テストで見分けられるよう独自の semantics の印(例: `SemanticsPropertyKey<Boolean>("OneLine")` を true)を付ける。
7. **OS による作り直しで状態を戻す**: `PanelScreen` のアプリ一覧の開閉を `rememberSaveable` にする(仕様 §4.9。設定は今のとおり「開いていること」だけ戻し、中の画面は Root からでよい)。
8. `PanelScreen` に引数を足す場合は、既定値つきにし、**最後の引数(末尾ラムダで渡されている `onLaunchApp`)の後ろに置かない**。

### ホーム(HomeContent.kt / HomeActivity.kt)
- `@Composable fun HomeContent(homeApps: …, onLaunchHome: (String) -> Unit, panel: @Composable (Modifier) -> Unit)` のような形(名前と引数は自由。「決めたこと」に書く)。
- `BoxWithConstraints` で幅 W を測り、`PanelLayout.homePanelWidthDp(W)` が null でなければ `Row { ホームのアイコンの格子(weight 1f、名前なし、列数自動、縦スクロール) ; panel(幅 = 返り値) }`、null なら `panel(fillMaxSize)` だけ。
- **パネル(PanelScreen)は、どちらの配置でも同じ呼び出し位置に置き、状態(アプリ一覧の開閉など)を失わないこと**(例: `panel` を `Row` の外側の同じ位置から呼ぶ、`movableContentOf` を使う、など。方法は自由。「決めたこと」に書く)。
- 起動画面の重ね表示(`bootPhase`)など、今の HomeActivity の他の処理は変えない。

### 背景
- `HomeActivity`・`PanelActivity` の `Image(painterResource(R.drawable.wallpaper), …)` をやめ、根の Box に `Modifier.launcherBackground()` を付ける。`wallpaper.png` は消さない(幕が使う)。

### 帯の逃げ(StripInsets.kt / EdgeStripService.kt)
- `EdgeStripService` の companion object に `val stripBounds: StateFlow<StripBounds>` を足す。`data class StripBounds(val left: android.graphics.Rect?, val right: android.graphics.Rect?)`(画面座標の px。窓が付いていない側は null)。
  帯を窓に付けた/外した/幅を変えたとき(`ensureStripsAttached`・`releaseOverlays`・`readdStrips`)に更新する。矩形は、**`addView` が成功したときに使った LayoutParams の幅**(最低 1px の補正後の値)と、付けた側(左は x=0、右は x=画面の幅−幅)、高さ=画面の高さから計算する。外すのに失敗して付いたままの側は、付いている幅のまま公開する。付いていない側は null。画面の大きさは `WindowManager.currentWindowMetrics.bounds` を使う。
- `StripInsets.kt`:
  - 純粋な関数 `fun stripOverlapPx(window: Rect, strips: StripBounds): Pair<Int, Int>`(左側・右側に必要な余白の px。窓と帯の矩形の重なりの幅。重ならなければ 0)。`android.graphics.Rect` が JVM テストで使えなければ、自前の小さな矩形の型にしてよい。
  - `Modifier.stripSafePadding()`: その Composable の画面上の矩形と `EdgeStripService.stripBounds` の重なりから、左右の余白(px→dp)を付ける。帯の状態や幅が変わったらすぐ付け直す。
    画面上の矩形 = `onGloballyPositioned` の `boundsInWindow()` + **窓の原点の画面座標**(`LocalView.current.rootView.getLocationOnScreen`。根の View の位置=窓の原点)。
    **測るのは余白を付ける前(外側)の矩形**にする(内側を測ると、余白が往復するため)。
  - `HomeActivity`・`PanelActivity` の根に付ける。内側の PanelScreen には付けない(二重に引かない)。根の下にあるブラウザ・設定・起動画面の重ね表示も、この余白の分だけ狭くなってよい(それらの中の配置は変えない)。
  - 実機で確かめるため、余白が変わったときに `DiagLog.d("Layout", "strip window=… left=… right=… pad=…")` を出す。

## テスト(先に書き、修正前に失敗することを確かめる)
1. `PanelLayoutTest`(純粋な JUnit): `columns_boundaries`(87→0 ではなく 1、88→1、183→1、184→2、279→2、280→3)、`controlRows_fitOrWrap`(4個: 311→2、312→1)、`homePanelWidth_cases`(899→null、900→516、1291→581、2000→640)。
2. `StripInsetsTest`(純粋): 全画面の窓で左右とも重なる、右の分割の窓では右だけ、左の分割の窓では左だけ、帯が無い側は 0、帯が窓より広い場合は窓の幅まで。
3. `FitClockTest`(Robolectric + Compose): 幅 360/516/1291dp・高さ 400/726dp で、`clock` の TextLayoutResult(`SemanticsActions.GetTextLayoutResult` で取る)が `lineCount == 1`・`hasVisualOverflow == false`、
   時計のノードの境界が親の境界の内側。fontScale 1.3 でも同じ。時刻 "00:00" と "23:59" で大きさが同じ。
4. `LayoutMatrixTest`(Robolectric + Compose):
   - 描画の大きさは `RuntimeEnvironment.setQualifiers("w<W>dp-h<H>dp")` で決め(既存の `HomeActivityUiTest` の作り方)、**描いた根の実際の大きさが指定どおりかを最初に確かめる**。文字の大きさは `Density(density, fontScale)` を `LocalDensity` で与えてよい。
   - 境界の比較は、画面に見えている(クリップ後の)境界(`fetchSemanticsNode().boundsInRoot`)で行う。スクロールの中の部品は `performScrollTo` してから確かめ、スクロールの外(時計の段など)の部品にはしない。
   - パネル(`PanelContent`。停止中と再生中(☆あり)の両方)を、幅 360・387・516・645・774dp × 高さ 400・600・726dp、fontScale 1.0 と 1.3 で描く。
   - ホーム(`HomeContent`、パネル付き)を、幅 387・516・645・774・899・900・903・1291dp × 高さ 600・726dp で描く。
   - それぞれで確かめる:
     (a) `clock` が1行・はみ出しなし・境界が画面の内側。
     (b) 次のタグの領域が互いに重ならない(親子は除く): `clock`、`media_card`(再生カードに付ける)、`icon_grid`(格子に付ける)、`dock`(ドックに付ける)、ホームなら `home_grid`(ホームの格子に付ける)。
     (c) 押せる部品(`hasClickAction()`)の、クリップ前の大きさが 72dp 以上。各部品にスクロールして(`performScrollTo`)届き、画面の横の範囲の内側にある。
     (d) `OneLineText` の印の付いた文字がすべて1行(lineCount == 1)。
     (e) 899dp ではホームの格子が無く、900dp では有る。
   - **状態の保持**: ホームを 1291dp で描き、ドックの「アプリ一覧」を開く → 幅を 516dp に変える(テストの中で幅の状態を変えて描き直す)→ アプリ一覧が開いたまま → 1291dp に戻しても開いたまま。
   - **作り直しの後の復元**: `PanelActivityTest` に `appList_survivesRecreate`(分割の中でアプリ一覧を開き `controller.recreate()` → 開いたまま)を足す。
5. 既存のテストは、上で許した置き換え以外は変えずに通ること。

## 手順(TDD)
1. 上のテストを書く。2. 対象テストを実行し、修正前に失敗することを確かめる(どのテストがどう失敗したかを報告に書く)。
3. 実装する。4. 対象テストが全部通るまで直す(最大3回)。通ったら `assembleDebug`。
- Robolectric で Activity の中の Compose の状態を書き換えて確かめるテストでは、確かめる前に `Snapshot.sendApplyNotifications()` を呼ぶ(テストの順番によって変更の通知が止まり、`waitForIdle` が 10 秒で `AppNotIdleException` になるため)。

## この Task でしないこと
- 起動画面・設定・ブラウザ・アプリ一覧・幕の配置の変更(後の Task)。帯の判定・帯の窓の幅の変更。`android:configChanges` の追加。資源の幅の修飾子(`values-w600dp` 等)の追加。
- 全テストの実行、実機の操作、git のコミット等。

## 報告
実行したコマンドと結果(件数・失敗件数、修正前の失敗の内容、assembleDebug)、作成・変更したファイル、置き換えた既存テストの行、**決めたこと**。
