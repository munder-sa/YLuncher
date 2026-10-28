# YLuncher (GT8D カスタムランチャー)

- Claude は司令塔: 設計、指示書作成、差分レビュー、テスト実行。実装は `agy-run tasks/NNN-xxx.md` で agy に委譲する(共通ルールは `~/.claude/CLAUDE.md`)。
- 許可は `tasks/NNN-xxx.allow`、検査設定は `.agy.conf`。委譲後は `agy-verify tasks/NNN-xxx.md` と git diff・テストで検証し、agy の報告文だけを信用しない。コミットは承認を得てから。
- 計画: `docs/superpowers/plans/2026-10-28-gt8d-launcher.md`、仕様: `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md`。計画の Global Constraints と Review Focus を指示書に反映する。
- 実機(GT8D)での確認が要る Task(1・7・8・12・13)は、agy に任せきりにせず、人が確認する。
- 指示書の共通ルールは `tasks/RULES.md`(決めたことを全部並べる、推測で進めない、調べ物で許可外コマンドを使わない、テストを弱めない)。各指示書の冒頭に「最初に `tasks/RULES.md` を読み、従う」と書く。agy の報告の「決めたこと」は、レビューで仕様と照らし合わせる。
