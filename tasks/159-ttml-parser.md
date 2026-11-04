最初に tasks/RULES.md を読み、従う。

# Task 159: TTML の読み取り(主旋律を既存の歌詞のモデルへ)

計画 `docs/superpowers/plans/2026-11-04-ttml-lyrics.md` の **Task 159** の Files・Interfaces・Step 1〜2 をそのまま実施する。データの形は設計書 `docs/superpowers/specs/2026-11-04-ttml-lyrics-design.md` の末尾「実機・データの確認(Task 157 Step 3・4)」に確定済み(秒の小数の時刻、`p begin end itunes:key ttm:agent`、`span begin end`、`ttm:role="x-translation"`・`"x-roman"`・`x-bg` は捨てる)。テストの XML は架空の文で作る(実在の歌詞を入れない)。

## 作るファイル(これ以外は作らない・変えない)
- `app/src/main/java/com/gt8d/launcher/media/lyrics/TtmlParser.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/TtmlParserTest.kt`

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.TtmlParserTest'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、決めたこと(RULES.md 1)。

## 追記(codex の質問への回答)
- 不正な時刻・単語の順の矛盾などは **TTML 全体を棄却**(Invalid)する。設計書の末尾の記述もそのとおりに直した。計画の拒否テストのとおりに実装する。
