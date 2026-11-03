最初に tasks/RULES.md を読み、従う。

# Task 152: 歌詞の明暗・行ごとの時間差の送り・今の行以外の縮小

仕様: `docs/superpowers/specs/2026-11-04-v2.0-lyrics-visual-design.md`。計画: `docs/superpowers/plans/2026-11-04-v2.0-lyrics-visual.md` の **Task 152** の Files・Interfaces・Step をそのまま実施する。Task 150 の `ui/theme/LyricsVisualStyle.kt`(定数・`lineTargetAlpha`)を使う(変えない)。

## 計画の「150 Step 0 の指示」(解消済み、両文書の末尾「ユーザーの決定」)
- 既存の `LyricsPaneTest` で、次の行 0.45 など新しい値と食い違う期待値(`lineAlphaByDistance`・`currentLineFadesIn` など)は、**新しい仕様の値に合わせて直してよい**。確かめている内容(途中の値が範囲内、終わりで目標値、など)は減らさない。直したテストは名前と変更をすべて報告する。
- v1.7.1 の作り(描画の層で動かす、組み立て直し・再レイアウトを起こさない、measure・layout で状態に書き込まない、semantics の扱い)を保つ。`noPerFrameLoopForLineLevelLyrics`・`scrollAnimationDoesNotRelayout` が今どおり通ること。

## 変える・作るファイル(これ以外は変えない)
- 作る: `app/src/main/java/com/gt8d/launcher/ui/home/LyricMotion.kt`、`app/src/test/java/com/gt8d/launcher/ui/home/LyricMotionTest.kt`
- 変える: `app/src/main/java/com/gt8d/launcher/ui/home/LyricsPane.kt`、`app/src/test/java/com/gt8d/launcher/ui/home/LyricsPaneTest.kt`

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.LyricMotionTest' --tests 'com.gt8d.launcher.ui.home.LyricsPaneTest'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、直した既存テスト、決めたこと(RULES.md 1)。

## 追記(codex の質問への回答)
- 既存の `currentLineCenteredWithUniformSize` は、縮小の仕様に合わせて「レイアウトで測った行の高さは今の行と他の行で同じ」「描画の倍率は今の行 1.0・他の行 `LyricsVisualStyle.InactiveScale`(0.97)」「今の行の縦中央が歌詞の場所の縦中央と 1dp 以内」を確かめる形に直してよい。中央配置の検証は残す。同じように、縮小・時間差の仕様と食い違う既存テストが他にもあれば、確かめる内容を減らさない範囲で直してよい(すべて報告する)。
