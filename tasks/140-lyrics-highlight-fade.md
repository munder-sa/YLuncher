最初に tasks/RULES.md を読み、従う。

# Task 140: 歌詞の行の明るさの切り替えを滑らかにする(フェード)

ユーザーの指示(2026-11-03、実機確認): 「文字のハイライトの切り替えが唐突なので、切り替えがスムーズに見えるようなエフェクト(フェードイン/フェードアウト)にしてほしい」。

## 変えるファイル(これ以外は変えない)
- `app/src/main/java/com/gt8d/launcher/ui/home/LyricsPane.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/LyricsPaneTest.kt`(追加のみ)

## 変更(Synced)
- 各行の不透明度(今の行 1.0、他は距離で 0.45/0.35/0.25)を、目標の値へ **300ms**(`tween(300, easing = FastOutSlowInEasing)`)で動かす。今の行が次の行へ進むと、新しい今の行はフェードインし(0.45→1.0)、前の今の行はフェードアウトする(1.0→0.45)。他の行も距離が変わった分だけ同時に動く。行送り(300ms の上へのずらし)と同じ時間で、同時に始まること。
- 2行以上の飛び・戻り・曲替え・最初の表示では、不透明度もすぐ目標の値にする(行送りと同じ決まり)。
- 単語の時刻がある行の塗り(白い重ね)は、その行が今の行でなくなったら、重ねも 300ms でフェードアウトしてから消す(パッと消さない)。今の行になったときは、塗りは 0 から始まるのでフェードは不要。
- 間奏の「●●●」も、今の行になる/でなくなるときは、丸の不透明度を同じく 300ms で動かす。
- 不透明度の値は描画(`graphicsLayer { alpha = … }` など)で読み、再レイアウトを起こさない。measure・layout で snapshot の状態に書き込まない作り(Task 134)は保つ。
- 端末のアニメーションの速さの設定(アニメーション オフ)に従う(Compose の既定の動き)。

## テスト(追加)
- `currentLineFadesIn`: `mainClock.autoAdvance = false`。再生中に今の行が1つ進んだ直後から 150ms 進めた時点で、新しい今の行の不透明度が 0.45 より大きく 1.0 より小さい。さらに 300ms 進めると 1.0。不透明度は、行に `testTag("player_lyrics_line_<添字>")` を付け、`SemanticsProperties` に独自のキー(例 `LyricAlphaKey`)で現在の値を公開して読む、などのテスト用の口を作ってよい(作り方は決めたことに書く)
- `previousLineFadesOut`: 同じ時点で、前の今の行が 0.45 より大きく 1.0 より小さい、300ms 後に 0.45
- `jumpSetsAlphaImmediately`: 2行以上飛んだら、次のフレームで新しい今の行が 1.0
既存のテストは弱めない。

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.*'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、決めたこと(RULES.md 1)。
