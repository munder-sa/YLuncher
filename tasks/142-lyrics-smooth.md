最初に tasks/RULES.md を読み、従う。

# Task 142: 歌詞の切り替えを 500ms に、行送りのカクつきをなくす(描画の負荷を減らす)

ユーザーの指示(2026-11-03、v1.7.0 の実機確認): 「300ms だとまだ唐突。500ms で切り替えたい」「行送りがカクカクしているので改善したい」。

## 実機の計測(v1.7.0、ホームで歌詞表示、`dumpsys gfxinfo com.gt8d.launcher`)
- 924 フレーム中 223 フレーム(24%)が間に合わない。1フレームの中央値 27ms、90% 38ms(60fps には 16ms 以内が必要)。GPU は 7ms で、UI スレッド側が重い。

## 疑っている原因(コードを読んで確かめ、違えば報告する)
1. `LyricsPane` の塗りのための `withFrameMillis` のループが、単語の時刻が無い行(ほとんど)でも、再生中ずっと毎フレーム状態を書いている。
2. 行送りが `Layout` の配置で `offset.value` を読んでいるため、毎フレーム全行の配置をやり直している。
3. フェードの値(`emphasis.value` など)を composition で読んでいて、毎フレーム行ごとに再コンポーズが起きる。`semantics { this[LyricAlphaKey] = alpha.value }` も毎フレーム semantics を作り直す。

## 変えるファイル(これ以外は変えない)
- `app/src/main/java/com/gt8d/launcher/ui/home/LyricsPane.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/LyricsPaneTest.kt`

## 変更
1. **時間**: 行送り・行の明るさのフェード・塗りの重ねと「●●●」のフェードを、すべて **500ms**(`tween(500, easing = FastOutSlowInEasing)`)にする。同時に始まること(今どおり)。
2. **行送りは描画の層で動かす**: 行の並び全体を、`Layout` の配置では「目標の位置」に置き、動きの分だけ `graphicsLayer { translationY = … }` でずらす(配置のやり直しを毎フレーム起こさない)。最初のフレームから正しい位置(中央からずれた1フレームを見せない)、2行以上の飛び・戻り・曲替え・最初の表示はすぐ、は今どおり。
3. **毎フレームの更新は必要なときだけ**: 毎フレームのループは「今の行が単語の時刻を持つ」か「今の行が間奏・前奏の●●●」のときだけ回す。それ以外は、今の行の判定(`rememberLyricIndex` の 200ms)だけにする。
4. **毎フレーム変わる値は描画でだけ読む**: アニメーション中・再生中に毎フレーム変わる値(フェードの不透明度、塗りの位置、送りのずれ、●●● の経過)は、`graphicsLayer {}` / `drawWithContent {}` / `Canvas` のラムダの中でだけ読む。composition と measure・layout では読まない(読まないと決められない分岐、例えば「重ねを出すか」は、目標の値で決めて重ねは常に置いておき、不透明度 0 で隠す)。measure・layout で snapshot の状態に書き込まない(Task 134)。
5. **テスト用の不透明度の口**(`LyricAlphaKey`)は残してよいが、毎フレーム semantics を作り直さない形にする(例: アニメーションの「目標の値」を semantics に出し、途中の値は描画の層から読めるテスト用の口にする、など。方法は決めたことに書く)。既存のフェードのテスト(`currentLineFadesIn`・`previousLineFadesOut`・`jumpSetsAlphaImmediately`)は、時間が 500ms になった分だけ時刻を直し、「途中で 0.45 と 1.0 の間」「終わりで目標の値」を確かめる内容は保つ。直したものは報告する。

## テスト(追加)
- `noPerFrameLoopForLineLevelLyrics`: 単語の時刻が無い同期歌詞を再生中にして、`mainClock.advanceTimeByFrame()` を 30 回進めても、`LyricsPane` の再コンポーズの回数が増えない(行の中身に再コンポーズの回数を数える仕掛けを置くか、`SideEffect` で数える。方法は決めたことに書く)。今の行が変わったときだけ増えてよい
- `scrollAnimationDoesNotRelayout`: 行送りの 500ms の間、行の並びの `Layout` の measure の回数が増えない(数える仕掛けは同様)
- 既存の行送りのテスト(`nextLineAnimates` など)は、500ms に合わせて時刻だけ直す(途中で最終位置に達していない、終わりで達している、の内容は保つ)

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.*'`

## 報告
- 原因の見立てが合っていたか、実行したコマンドと結果(テストの件数・失敗数)、直した既存テスト、決めたこと(RULES.md 1)。
