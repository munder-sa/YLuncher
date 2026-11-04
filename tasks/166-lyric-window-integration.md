最初に tasks/RULES.md を読み、従う。

# Task 166: 歌詞の構築範囲の組み込み・明暗・重ね文字の Offscreen を絞る(v2.2.0 負荷軽減)

計画 `docs/superpowers/plans/2026-11-04-perf.md` の **Task 166** の Files・Interfaces・Step をそのまま実施する。設計書 `docs/superpowers/specs/2026-11-04-perf-design.md`(末尾の「ユーザーの決定」を含む)。Task 165 の `LyricWindow.kt`(`planLyricWindow` など)を使う。v1.7.1・v2.0 の作り(描画の層で動かす、組み立て直し・再レイアウトを起こさない、measure・layout で状態に書き込まない、リリース版は画面をユーザー補助に「パネル」1つ、デバッグ版の行ごとの semantics)を壊さない。

## 既存テストの扱い(まとめての許可)
- 構築範囲を絞ったこと・明暗の処理を絞ったこと・重ね文字の Offscreen を絞ったことと食い違う既存テストは、**確かめる中身(見えている行の位置・明るさ・塗り・行送りとフェードの動き・組み立て直しが起きないこと)を保ったまま**、新しい仕様に合わせて直してよい。直したものはすべて報告する。見た目や動きを変える必要がある場合だけ止まる。

## 変える・作るファイル
- `tasks/166-lyric-window-integration.allow` に書いたものだけ。

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `tasks/166-lyric-window-integration.allow` の command に書いたものだけ(一字一句そのまま)。

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、直した既存テスト、決めたこと(RULES.md 1)。
