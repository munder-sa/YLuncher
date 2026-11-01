# Task 091: ドックの「分割」を「セット追加」の絵に、パネルのミニプレイヤーの操作を1行に固定(v1.6 B1・B6)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
v1.6 設計書 `docs/superpowers/specs/2026-10-31-v1.6-ux-design.md` の §4 B1・B6 と §2、計画 Task 091 を読むこと(この指示書が優先)。
- ドック(`ui/panel/Dock.kt`)の「分割」ボタン(testTag `dock_split`、`Icons.Default.ViewColumn`)は、押すと分割ではなく新しいセットの編集を開く(`PanelScreen` の `onSplit → onNewSet`)。**ドックに文字は付けない**(ユーザーの判断)。絵だけで「セットを追加」と分かるようにする。
- 分割のパネルのミニプレイヤー(`ui/panel/MediaCard.kt`)は、☆を操作の列に入れ、幅によって2段になる(`PanelLayout.controlRows`)。ホームのミニプレイヤー(`ui/home/HomeMiniPlayer.kt`)は ☆ を曲名・アーティスト名の右に置き、下は「前・再生/一時停止・次」の1行(再生は白い丸の地・暗い図柄)。

## 変更・作成するファイル(これで全部)
- create `app/src/main/java/com/gt8d/launcher/ui/panel/SetAddIcon.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/Dock.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/MediaCard.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/DockTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/MediaCardTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/layout/LayoutMatrixTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.ui.panel.*' --tests 'com.gt8d.launcher.ui.layout.*'`

## 仕様
1. **B1**: `SetAddIcon.kt` に `val SetAddIcon: ImageVector`(24x24 の viewport)。`PanelContent.kt` の private な `SetIcon`(左右 6:4 の2つの角丸の四角)と同じ形を左上寄りに少し小さく描き、右下に「＋」(線の太さ 2、長さ 8)を重ねる。`Dock` の `dock_split` のアイコンを `SetAddIcon` に、`contentDescription` を「セットを追加」に。大きさ・位置・動作は今のまま。文字は付けない。
2. **B6**: `MediaCard` の再生中の表示(`ActiveMediaContent`):
   - ☆(`btn_favorite`、`now.hasFavorite` のとき)を、曲名・アーティスト名の列の右に 8dp 空けて置く(72dp、縦は中央)。曲名・アーティスト名は ☆ の分だけ幅が狭くなる(`MarqueeText` はその幅で流れる)。
   - 下の操作は「前・再生/一時停止・次」の3つで**常に1行**(`controlRows` を使わない)。再生/一時停止は白い丸の地(`Palette.PanelAccent`)に暗い図柄(`Palette.Background0`)、72dp。前・次は今のまま白い図柄 72dp。3つ(216dp + 間隔)が入らない狭い幅(パネルの最小幅)でも1行に収まることを確かめる。収まらない場合は間隔を 0 まで詰める(大きさは 72dp を保つ)。
   - testTag(`btn_prev`・`btn_play`・`btn_next`・`btn_favorite` など今のもの)は変えない。
3. 他の見た目は変えない。

## テスト(足す。既存は弱めない。並びの変更に由来する既存の期待値だけ合わせ、報告する)
- `DockTest`: `dock_split` の説明が「セットを追加」、押すと今と同じ呼び出し。
- `MediaCardTest`: ☆ が曲名の右にある(☆ の中心 y が曲名〜アーティスト名の範囲内、☆ の left ≥ 曲名の列の right)、操作3つの中心 y が同じ(±1px)、再生ボタンの地が白(semantics か、テスト用の印で)。☆ が無いとき曲名の列が全幅。
- `LayoutMatrixTest`: 狭い表示の既存の組み合わせで、操作3つが1行・画面内・72dp 以上。

## この Task でしないこと
- ホームのミニプレイヤー・待ち受けの表示の変更(Task 095)。ドックの他のボタンの変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、合わせた既存の期待値、**決めたこと**。
