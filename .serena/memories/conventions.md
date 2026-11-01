# Conventions
## 作業の流れ(このプロジェクト固有)
- Claude は司令塔(設計・指示書・レビュー・テスト)、実装は codex に委譲: 指示書 `tasks/NNN-xxx.md` + 許可 `tasks/NNN-xxx.allow`(`write`/`create`/`command` を1行ずつ。ディレクトリ・glob 不可)。番号は連番で、最新番号の次を使う。
- 指示書の冒頭に「最初に `tasks/RULES.md` を読み、従う」。RULES: 決めたことを全部並べる / 推測で進めない(3点セットで止まる)/ 許可外コマンド禁止 / テストを弱めない / テストを無意味に広げない。codex 向け常設指示は AGENTS.md。
- 流れ: 指示書 → `codex-ask` で点検 → `codex-run` → 差分と報告の「決めたこと」を仕様と照合 → テスト → コミット → 実機。
- 分割・リンク・タスクに触ったら実機の場面テスト(scripts/device-scenarios.sh)を全部流す。
- 報告・コミットメッセージ・コメント・UI 文言は日本語。コミットは conventional 風の接頭辞(`fix:`, `docs(install):`, `test(device):`)+日本語、指示書番号を入れることがある(`fix: 113 ...`)。
- リリースは版ブランチ(例 `v1.6.1`)→ main に取り込み → タグ `vX.Y.Z-release` 等(ブランチ名と衝突しない名前)。push はユーザーの判断。

## コード
- Kotlin official style。パッケージは機能ごと(上の `mem:core`)。DI は `AppGraph` の手動配線、テスト用の差し替えは `*ForTest` 関数や deps のデータクラス(例 `SetRunnerDeps`)で注入。
- Android の API への依存はインターフェイス/ファサードで包み(例 `WindowManagerFacade`, `ActivityLauncher`, `Messenger`, `LastIndexStore`)、ロジックは Robolectric/JVM でテストする。
- 時間を扱うロジックは時計を注入する(`time/`)。
- Robolectric で Activity 内の Compose 状態を書き換えるテストは `Snapshot.sendApplyNotifications()` を呼ぶ。
- 見た目は Material 3 / iOS HIG に寄せる。崩れ(はみ出し・切れ)は許容しない。ホームとパネルの強調色は白、設定・幕・起動画面は緑。
