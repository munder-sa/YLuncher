# YLuncher (GT8D カスタムランチャー)

- Claude は司令塔: 設計、指示書作成、差分レビュー、テスト実行。実装は `codex-run tasks/NNN-xxx.md` で codex に委譲する(共通ルールは `~/.claude/CLAUDE.md`)。
- 許可は `tasks/NNN-xxx.allow`、検査設定は `.codex.conf`、codex 向けの常設の指示は `AGENTS.md`。委譲後は `codex-verify tasks/NNN-xxx.md` と git diff・テストで検証し、codex の報告文だけを信用しない。コミットは承認を得てから。
- 計画: `docs/superpowers/plans/2026-10-28-gt8d-launcher.md`、仕様: `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md`。計画の Global Constraints と Review Focus を指示書に反映する。
- 実機(GT8D)での確認が要る Task(1・7・8・12・13)は、codex に任せきりにせず、人が確認する。
- 指示書の共通ルールは `tasks/RULES.md`(決めたことを全部並べる、推測で進めない、調べ物で許可外コマンドを使わない、テストを弱めない)。各指示書の冒頭に「最初に `tasks/RULES.md` を読み、従う」と書く。codex の報告の「決めたこと」は、レビューで仕様と照らし合わせる。
- **テストの範囲は変更の影響に合わせて絞る(無意味に広げない)**: 影響が狭い変更(UI の一部・設定画面・単一の部品)は、関係するパッケージのテスト(+必要ならビルド)だけ流す。全テスト(`codex-verify` の `test assembleDebug`、約12分)は、分割・リンク・設定データなど広く効く変更のときか、main に入れる前に1回だけ。同じテストを codex と自分で重ねて流さない。実機の場面テストは、分割・リンク・タスクに触ったときだけ。
