最初に tasks/RULES.md を読み、従う。

# Task 134: 歌詞の切り替えボタンの線幅と、LyricsPane のレイアウト中の状態書き込みを直す

計画: `docs/superpowers/plans/2026-11-03-lyrics.md`(Task 132 の仕上げ)。

## 変えるファイル(これ以外は変えない)
- `app/src/main/java/com/gt8d/launcher/ui/home/LyricsToggle.kt`
- `app/src/main/java/com/gt8d/launcher/ui/home/LyricsPane.kt`

テストファイルは変えない(`LyricsPaneTest`・`HomeMiniPlayerTest` はそのまま通ること)。

## 直すこと
1. **図柄の線幅**: `LyricsIcon` は viewBox 40 を 28dp で描くため、`strokeLineWidth = 2.5f` だと実際は約 1.75dp と細い。見た目で **2.5dp** になるよう、`strokeLineWidth = 2.5f * 40f / 28f`(約 3.57)にする。ほかは変えない。
2. **LyricsPane の Synced**: 今は `Layout` の measure ブロックの中で `target`・`animate`・`resolved`(snapshot の状態)に書き込んでいる。**measure・layout のラムダの中で snapshot の状態に書き込まない**形に直す。
   - 例: 行ごとの高さから求めた「今の行を中央に置く縦のずらし量」を、状態ではない入れ物(`remember { object { var … } }` など)に measure で入れ、`LaunchedEffect(index)` の中で `withFrameNanos` を1回待ってから読み、`Animatable` を `animateTo(tween(300))`(ちょうど1行進んだとき)または `snapTo`(それ以外)で動かす。layout では `Animatable.value` を読んで置く。
   - 動きの決まりは今と同じ: ちょうど1行進んだときだけ 300ms で送る。2行以上の飛び・戻り・曲替え・最初の表示はすぐ(最初のフレームから正しい位置。中央からずれた1フレームを見せない)。
   - 方法は上の例に限らないが、選んだ方法を「決めたこと」に書く。

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.*'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、決めたこと(RULES.md 1)。
