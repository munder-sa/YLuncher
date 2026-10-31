# Task 075: パネルの位置を共有し、左ではドックを左に置く(v1.5 Task 2)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
v1.5(設計書 `docs/superpowers/specs/2026-10-31-v1.5-left-panel-design.md` §4.4・§5、計画 `docs/superpowers/plans/2026-10-31-v1.5-left-panel.md` Task 2。この指示書が優先)。分割のパネル(PanelActivity)が、自分の窓の画面上の位置から「左/右/全画面/不明」を決めて共有し、左のときはドックを画面の左端側に置く。仕切りのダブルタップで左右が入れ替わっても PanelActivity は作り直されない(実機で確認済み)ので、その場でドックが移る必要がある。帯の重なりの計算(`ui/layout/StripInsets.kt` の `stripSafePadding`)は、入れ替えの直後に窓の座標の変化を捉えている。

## 変更・作成するファイル(これで全部)
- create `app/src/main/java/com/gt8d/launcher/split/PanelPlacement.kt`
- `app/src/main/java/com/gt8d/launcher/AppGraph.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelActivity.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelContent.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt`
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeActivity.kt`(`placement = null` を明示するだけ。不要なら触らない)
- create `app/src/test/java/com/gt8d/launcher/split/PanelPlacementTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/PanelContentTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/PanelScreenTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/layout/LayoutMatrixTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.split.*' --tests 'com.gt8d.launcher.ui.panel.*' --tests 'com.gt8d.launcher.ui.layout.*' --tests 'com.gt8d.launcher.ui.home.*'`

## 仕様
1. `PanelPlacement.kt`: `enum class PanelPlacement { Left, Right, Full }` と純粋関数
   `fun placementOf(windowLeft: Int, windowRight: Int, screenWidth: Int, inMultiWindow: Boolean): PanelPlacement?`
   判定の順: `!inMultiWindow` → Full。`windowLeft <= 0 && windowRight >= screenWidth` → Full。`windowLeft <= 0` → Left。`windowRight >= screenWidth` → Right。それ以外 → null(移り変わりの途中・不明)。
2. `AppGraph`: `val panelPlacement: StateFlow<PanelPlacement?>`(初期値 null)と `fun reportPanelPlacement(p: PanelPlacement?)`(値が変わったときだけ更新し、`DiagLog.d("Panel", "placement=$p")`)。
3. `PanelActivity`: 自分の窓の画面上の左端・右端(`window.decorView` の `getLocationOnScreen` と幅。`stripSafePadding` と同じ座標の取り方)、画面の幅(`windowManager.maximumWindowMetrics.bounds.width()`)、`isInMultiWindowMode` から `placementOf` を計算して報告する。計算する時: レイアウトのたび(`decorView` の `ViewTreeObserver.OnGlobalLayoutListener`、または Compose の `onGloballyPositioned`)、`onResume`、`onMultiWindowModeChanged`。`onStop` で `reportPanelPlacement(null)`。safetyMode のときも報告してよい(表示に影響しないので)。
4. `PanelScreen` に引数 `placement: PanelPlacement? = null` を足し、`PanelState` に `val placement: PanelPlacement? = null` を足して渡す。PanelActivity は `AppGraph.panelPlacement` を collect して渡す。**HomeActivity は渡さない(null)**。ワイドのホーム(`HomeWideContent`)は placement を使わない。
5. `PanelContent`: `state.placement == PanelPlacement.Left` のとき、外側の Row の並びを「ドック、内容(時計〜一覧)」にする。それ以外は今のまま「内容、ドック」。**内容とドックの composable を条件分岐の別の枝に置かない**(並べ替えで状態が消えないように)。例: 外側の Row に `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl)` を与え、内容とドックの中ではそれぞれ `Ltr` に戻す。他の方法でもよいが、状態が保たれることをテストで示す。ドックの中の並び・内容の向き・文字の寄せは変えない。
6. 帯との重なり(`stripSafePadding`)は今のまま効くこと。

## テスト(足す。既存は弱めない)
- `PanelPlacementTest`: (0,768,1920,true)→Left、(1167,1920,1920,true)→Right、(0,1920,1920,true)→Full、(0,1920,1920,false)→Full、(300,1200,1920,true)→null、(0,768,1920,false)→Full。
- `PanelContentTest.leftPlacement_dockOnLeft`: `placement = Left` で `dock` の right ≤ `panel_view_segment` の left。`Right`・`null` では `dock` の left ≥ `panel_view_segment` の right(今のまま)。
- `PanelContentTest.placementChange_keepsState`: アイコン一覧(またはスクロールする領域)をスクロールし、セグメントで「セット」を選んだ状態(`mutableStateOf` の state を `Snapshot.sendApplyNotifications()` で)で placement を Left→Right に変えると、ドックの位置が入れ替わり、スクロール位置とセグメントの選択が保たれる。
- `PanelScreenTest.home_ignoresPlacement`: `AppGraph.reportPanelPlacement(PanelPlacement.Left)` の後でも、`placement` を渡さない PanelScreen(ホームと同じ)のドックは右。テストの後で `reportPanelPlacement(null)` に戻す。
- `LayoutMatrixTest`: 狭い表示の既存の組み合わせに `placement = Left` の場合を足す(ドックが画面内・72dp 以上・内容と重ならない)。既存の組み合わせはそのまま。

## この Task でしないこと
- セットの起動・入れ替えの保存・リンクの変更。全画面のホームの変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、**決めたこと**。

## 補足(点検の反映)
1. `PanelActivityTest.kt` も変更してよい(allow に追加)。テスト `placement_reportedAndClearedOnStop`: 分割状態にした PanelActivity で `AppGraph.panelPlacement` に値が届き(Robolectric で窓の座標が取れない場合は、座標の取得を差し替えられる口を Activity に設けてよい。設けたら報告)、`onStop` の後は null。
2. 状態保持のテストの手順: 「セット」を選ぶ → セット一覧(スクロール領域)を 0 でない位置までスクロール → placement を Left→Right → セットが選ばれたまま・スクロール位置が同じ。アプリ表示の側でも同様に1つ。
3. 位置の監視は STARTED の間だけ: `onStart` でリスナーを登録して計算、`onStop` でリスナーを外してから null を報告。
4. 幅 0 以下(`windowRight - windowLeft <= 0`)または `screenWidth <= 0` は `null`。テスト (0,0,1920,true)→null を足す。
