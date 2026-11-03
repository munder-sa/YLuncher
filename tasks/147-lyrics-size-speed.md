最初に tasks/RULES.md を読み、従う。

# Task 147: 歌詞の文字を 22sp に、行送りを 800ms に

ユーザーの判断(2026-11-03、1.7.1-beta3 の実機確認): 「行送りをもっとスローにしていい」→ 800ms。「フォントサイズももう少し小さくて OK」→ アーティスト名(20sp)より少し大きい 22sp。

## 変えるファイル(これ以外は変えない)
- `app/src/main/java/com/gt8d/launcher/ui/home/LyricsPane.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/LyricsPaneTest.kt`

## 変更
1. 同期歌詞の全行(今の行・他の行・塗りの重ね)の文字を 24sp → **22sp**(太さ・揃え・行の間 16dp・最大 2 行は今どおり)。間奏の「●●●」の行の高さを決めている透明な文字も 22sp に(行の高さをそろえる)。Plain の 20sp、Loading の 20sp は変えない。
2. **行送り**(縦のずれの動き)だけを `tween(800, easing = FastOutSlowInEasing)` にする。行の明るさのフェード・塗りの重ねと「●●●」のフェードは 500ms のまま。両方とも、今の行が変わった同じフレームから始まる。送りの途中で次の行に変わったら、今の位置から新しい目標へ続けて動く(途中で跳ばない)。
3. 2行以上の飛び・戻り・曲替え・最初の表示はすぐ、描画の層で動かす作り(Task 142)、measure・layout で状態に書き込まない(Task 134)、semantics の扱い(Task 144)は今どおり。

## テスト
- 既存の行送りのテスト(`nextLineAnimates` など)は、800ms に合わせて時刻だけ直す(途中で最終位置に達していない、終わりで達している、の内容は保つ)。フェードのテストは 500ms のまま通ること。直したテストを報告する。
- 追加:
  - `scrollTakes800msWhileFadeTakes500ms`: ちょうど1行進んだ後、600ms の時点で、新しい今の行の不透明度は 1.0(フェード完了)、行の並びはまだ最終位置に達していない。900ms で達している
  - `retargetDuringScrollIsContinuous`: 送りの途中(300ms)で次の行に変わったとき、その次のフレームの縦の位置が、直前のフレームの位置から 1 行分以上は跳ばない
  - `lyricTextIs22sp`: 今の行の文字の大きさが 22sp(`TextLayoutResult` の `layoutInput.style.fontSize`、既存のテストの文字の測り方に合わせる)

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.*'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、直したテスト、決めたこと(RULES.md 1)。
