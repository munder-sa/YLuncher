# Task 065: 狭いパネルに「ミニプレイヤーの表示/非表示」のトグルを付ける

最初に `tasks/RULES.md` を読み、従うこと。

## 背景(ユーザーの希望 2026-10-31、codex と相談済み)
狭いパネル(分割の右・狭いホーム。`PanelContent` を使う画面)で、
- モードA(既定)= 今の「時計 + ミニプレイヤー(MediaCard) + アイコン一覧」
- モードB = 「時計 + アイコン一覧」(ミニプレイヤーを隠し、一覧を広く)
を切り替えるトグルを、時計の横に置く。ワイドのホーム(`HomeWideContent`)には出さない。

## 変更するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/config/Model.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelContent.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/PanelContentTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/PanelScreenTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/layout/LayoutMatrixTest.kt`
- `app/src/test/java/com/gt8d/launcher/config/ConfigCodecTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.ui.panel.* --tests com.gt8d.launcher.ui.layout.* --tests com.gt8d.launcher.config.*`

## 仕様
1. 保存: `LauncherConfig` の末尾に `val panelMediaVisible: Boolean = true` を足す(既定 = モードA。古い JSON は true で読めること)。`CONFIG_VERSION` は変えない(既定値付きの追加なので)。
2. `PanelState` に `val mediaVisible: Boolean = true`、`PanelActions` に `val onToggleMedia: () -> Unit = {}` を足す(既定値付きにして、既存の呼び出し・テストを壊さない)。
3. `PanelScreen`: `config.panelMediaVisible` を `PanelState.mediaVisible` に渡し、`onToggleMedia` で `AppGraph.config.update { it.copy(panelMediaVisible = !it.panelMediaVisible) }` を(コルーチンで)呼ぶ。保存が終わるまで画面が戻らないよう、**押した直後から見た目を変える**: `rememberSaveable` の一時状態を持ち、config の値が届いたらそれに合わせる(Activity の作り直しでも、保存済みの値で正しく出ること)。ワイド(`HomeWideContent`)には渡さない・出さない。
4. `PanelContent` の時計の部分を `Row` にする: `FitClock` を `Modifier.weight(1f)` で左、8dp 空けて右にトグル(72×72dp = `Dims.MinTouch`)。
   - 横に並べると時計が `FitClock` の最小(40sp)でも収まらない幅のときは、トグルを時計の**下の行の右端**に回す(測って判断。判断の方法は「決めたこと」へ)。
   - 図柄: `Icons.Default.QueueMusic`(32dp)、背景は円。モードA(表示中)= 背景 `Palette.PanelAccent`(白)・図柄 `Palette.Background0`、モードB(非表示)= 背景 `Palette.Panel`・枠 2dp `Palette.Divider`・図柄 白。色だけに頼らないよう、`semantics { stateDescription = "表示中"/"非表示"; role = Role.Switch }`、`contentDescription = "ミニプレイヤー"`、`toggleableState` か `selected` を付ける(どちらか「決めたこと」へ)。testTag `panel_media_toggle`。
   - モードBで `state.now?.playing == true` のとき、トグルの右上に小さな静止の再生の印(直径 20dp の円 `Palette.PanelAccent`、中に `Icons.Default.PlayArrow` 14dp `Palette.Background0`、testTag `panel_media_playing_badge`)を出す。押せない(印はトグルの一部)。動かさない。
5. モードBでは `MediaCard` を出さない(許可の案内・アイコン一覧・ドックは今のまま)。
6. テスト(足す。既存は弱めない。既存の検証はモードAのまま通ること):
   - `PanelContentTest`: (a) 既定で `panel_media_toggle` が有り、72dp 以上、MediaCard が有る。(b) `mediaVisible = false` で MediaCard が無く、アイコン一覧の上端が (a) より上にある。(c) トグルを押すと `onToggleMedia` が1回。(d) モードBで再生中なら `panel_media_playing_badge` が有り、停止中・モードAでは無い。
   - `PanelScreenTest`: トグルを押すと config の `panelMediaVisible` が false になり、MediaCard が消える。ワイドのホームでは `panel_media_toggle` が無い。
   - `LayoutMatrixTest`: 狭い表示の既存の組み合わせ(360dp・文字倍率 1.3 を含む)で、トグルが時計と重ならず、72dp 以上で画面内にあること。モードBの組み合わせも足す。
   - `ConfigCodecTest`: `panelMediaVisible` の無い古い JSON が true で読める。false の往復。

## この Task でしないこと
- MediaCard・HomeMiniPlayer の変更(064 で変更中)。設定画面への項目の追加。ワイドのホームの変更。git のコミット等。

## 報告
変更点、テストの結果、**決めたこと**。

## 補足(点検の反映)
- **状態の持ち方(3 の具体化)**: 押したら一時状態 `pending: Boolean?` に新しい値を入れ、見た目は `pending ?: config.panelMediaVisible`。保存は `AppGraph.config.update { it.copy(panelMediaVisible = 新しい値) }`(反転ではなく**値を書く**。連打しても最後に押した値が残る)。config の値が `pending` と一致したら `pending = null`。保存に失敗しても画面は落とさない(ログだけ)。`pending` は `rememberSaveable`。
- 作り直しのテスト: `PanelScreenTest` で、false を保存 → Activity を作り直す(既存の host の作り方に合わせる)→ MediaCard が無いこと。
- `LayoutMatrixTest`: モードAの既存の検証は全部残す。モードBの組み合わせでは `media_card` が無いことを確かめ、カードを使う重なり・スクロールの検証からは外す。トグルはスクロール領域の外なので、`performScrollTo` の対象から外し、表示・72dp 以上・画面内・時計と重ならないことを直接確かめる。
- 折り返しの判定: `FitClock` と同じ条件(`"00:00"`・Bold・tnum・文字倍率込み)で 40sp の幅を測り、左の列の幅から 72dp + 8dp を引いた幅に収まらなければ下の行へ回す。横並び・下の行の両方の場合をテストする(幅を変えて)。
- ワイド: `HomeWideContent` は `mediaVisible`・`onToggleMedia` を参照せず、見た目も動きも変えない。
