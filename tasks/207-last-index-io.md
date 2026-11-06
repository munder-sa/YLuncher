最初に tasks/RULES.md を読む。

# Task207 最終セット番号の重複IO抑制

ユーザー承認済み7候補。ConfigRepository.lastSetIndexの繰返しread/writeを減らす。永続化は同期のまま保存成功後にcacheを確定し、非同期保存へは変えない。ファイルフォーマットと初期値0は維持。

- 初回読取りをlazy cacheし、同一repositoryでの繰返しgetをメモリから返す。新しいrepositoryはディスクを再読取する。存在しない/不正ファイルから0を返す場合と、実際に0が永続化済みの場合を区別する。
- 最後に正常に読めた/書けた永続値と同じsetだけ書込みを省略。不正/不存在を単に0として省略しない。書込失敗時はcacheを成功扱いに更新せず例外を従来どおり伝え、同じ値で再試行できる。スレッドアクセスの一貫性を保つ。外部writerが存在するか読み取り検索で確認し、複数同時writerがあれば推測でcacheを入れず報告する。
- 新規LastSetIndexStore.ktへ小さなhelperとテスト可能なIO境界を作ってよい。ConfigRepositoryのconstructorの既存呼出し互換を維持。設定DataStore/移行処理を変えない。
- 新規LastSetIndexStoreTestで、初回read1回/連続get、同値set100回で書込み増加なし、値変更とrepository再生成、初期不存在/不正、write失敗後同値再試行、保存順序を確認。既存テストは無変更。

許可コマンド:
`/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.config.ConfigRepositoryTest' --tests 'com.gt8d.launcher.config.LastSetIndexStoreTest'`

allow外/依存/版/ADB/Git変更禁止。実行回数・件数・決めたことを報告。
