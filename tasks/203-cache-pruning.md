最初に tasks/RULES.md を読む。

# Task203 キャッシュ整理のJSON再読取り削減

あなたは実装担当。追加7候補の承認内。AMLL/LRCLIBの整理コストだけを変える。既存API/形式/上限/破損/TTL/削除順を維持。既存テスト変更、依存/版/Git/ADB操作禁止。

- AmllCache.pruneで件数300以下かつ合計32MiB以下ならソート前に返す。現在のサイズ集計やファイル絞りは維持する。
- 上限超過ではsavedAtを各ファイル1回だけ読むdecorate-sort方式とし、同時刻のname tie-break/破損Long.MIN_VALUEを保つ。削除失敗時のcount/bytes減算と次候補への継続を保つ。get/putの同期や1MiB entry limitはそのまま。
- LyricsCacheも上限超過時の比較関数内JSON読みを各1回へ。既存の同時刻順序は変更しない。不要なファイル全内容cacheは残さない。
- 必要最小限のinternal hookでsavedAt読取り回数を計測し、上限内0、超過時各1以下を検証。独立した既存試験に加えて、新規CachePruningCostTestで不正JSON/同時刻/300件・32MiB/削除順の同等性を確認。性能テストの壁時計閾値は使わない。
- 変更範囲はallowの2実装と新規テストのみ。実機のAMLL1件/LRCLIB46件なので、実機CPU改善の主張はしない。

許可コマンド:
`/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.AmllCacheTest' --tests 'com.gt8d.launcher.media.lyrics.LyricsCacheTest' --tests 'com.gt8d.launcher.media.lyrics.CachePruningCostTest'`

新規回帰→実装→対象試験、成功後再実行なし。差分・実行回数/件数・決めたことを日本語で報告する。
