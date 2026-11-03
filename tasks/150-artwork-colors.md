最初に tasks/RULES.md を読み、従う。

# Task 150: 色抽出・暗色化・可読性の土台(v2.0.0)

仕様: `docs/superpowers/specs/2026-11-04-v2.0-lyrics-visual-design.md`。計画: `docs/superpowers/plans/2026-11-04-v2.0-lyrics-visual.md` の **Task 150** の Files・Interfaces・Step 1〜4 をそのまま実施する(テストの名前・確かめる値も計画どおり)。

## 計画の Step 0 の未決は解消済み(両文書の末尾「ユーザーの決定」)
- MediaCard にも同じ背景を使う(この Task では土台の関数だけ作る。MediaCard への適用は後の Task)。
- 既存の `LyricsPaneTest` の 0.45 などとの食い違いは、この Task では `LyricsPane.kt`・`LyricsPaneTest.kt` を変えないので扱わない(Task 152 で直す)。

## 作るファイル(これ以外は作らない・変えない)
- `app/src/main/java/com/gt8d/launcher/ui/theme/ArtworkColors.kt`
- `app/src/main/java/com/gt8d/launcher/ui/theme/LyricsVisualStyle.kt`
- `app/src/test/java/com/gt8d/launcher/ui/theme/ArtworkColorsTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/theme/LyricsVisualStyleTest.kt`

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.theme.*'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、計算した最小のコントラスト比、決めたこと(RULES.md 1)。
