最初に tasks/RULES.md を読み、従う。

# Task 165: 歌詞の構築範囲の計算と契約(v2.2.0 負荷軽減)

計画 `docs/superpowers/plans/2026-11-04-perf.md` の **Task 165** の Files・Interfaces・Step をそのまま実施する。設計書 `docs/superpowers/specs/2026-11-04-perf-design.md`(末尾の「ユーザーの決定」を含む)。v1.7.1・v2.0 の作り(描画の層で動かす、組み立て直し・再レイアウトを起こさない、measure・layout で状態に書き込まない、リリース版は画面をユーザー補助に「パネル」1つ)を壊さない。既存テストは弱めない(仕様の変更に伴う期待値の調整は報告する)。

## 変える・作るファイル
- `tasks/165-lyric-window.allow` に書いたものだけ。足りなければ止めて報告する。

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `tasks/165-lyric-window.allow` の command に書いたものだけ(一字一句そのまま)。

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、直した既存テスト、決めたこと(RULES.md 1)。

## 追記(codex の質問への回答)
- `LyricWindowTest.kt` は今回新しく作るテストなので、仕様に合わせて直してよい。キャッシュの試験は「すでに窓の中にある(保持済みの)行は測り直さない」「新しく窓に入る行だけ測る」を確かめる形にする。
