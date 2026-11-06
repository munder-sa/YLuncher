最初に tasks/RULES.md を読む。

# Task215 保存番号のキャッシュに対応するテストfixture

あなたは実装担当。6項目統合の全Release試験でHomeActivityTest等がNoSuchFieldException(lastSetFile)となった。Task207で保存境界をLastSetIndexStoreへ移したが、既存UIテストのfixtureが旧private fieldを直接参照している。司令塔は本体の単一writerとcache仕様を確認済み。今回はテストの準備・復元だけを修正し、期待値/検証/テスト名/実装は一切変更しない。

- ConfigRepositoryのprivate `file` は現存。そこから兄弟last_set_index.txtを求めるtest-only helper LastSetIndexFixtureを作る。private `lastSetIndexStore` の旧objectと実ファイルの存在/全bytesを退避し、fixture開始時に新しいLastSetIndexStoreを同ファイルへ結び付けてrepositoryへ反射で差し込む。終了時はファイルを存在状態まで正確に復元し、旧store objectも戻す。アプリ本体へtest専用API/毎回stat/旧private fieldを追加しない。
- 対象4クラスの旧lastSetFile反射とsavedIndex退避/直接復元をこのhelperに移す。HomeActivityTestのindexFileを使った **実ディスク文字列"4"のアサーション4箇所もそのまま維持**。getter/assert行は消さず、indexFileはhelperの実ファイルを参照するだけにする。fixture開始後の既存lastSetIndex代入は維持。SettingsActivityTestは該当testのtry/finallyで閉じる。
- helperはsrc/testのみ。例外を握りつぶさずcleanupを失敗として報告できる形にする。既存他のsetup/cleanup/アサーション/期待値/テスト列挙は変更不可。回避のためのテスト除外やproduction仕様の変更は禁止。
- 最初に4ファイル全体とLastSetIndexStore/ConfigRepositoryを読み、変更がsetup/cleanupだけであることをgit diffで照合する。ルール適合が判断できなければ止めて報告。

許可コマンド（対象4クラスをDebugとRelease各1回。修正を伴う失敗再実行のみ）:

Gradleの--testsは各タスク直後に4クラスを指定する:

`/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.HomeActivityTest' --tests 'com.gt8d.launcher.ui.home.HomeActivityUiTest' --tests 'com.gt8d.launcher.ui.panel.PanelActivityTest' --tests 'com.gt8d.launcher.settings.SettingsActivityTest' :app:testReleaseUnitTest --tests 'com.gt8d.launcher.ui.home.HomeActivityTest' --tests 'com.gt8d.launcher.ui.home.HomeActivityUiTest' --tests 'com.gt8d.launcher.ui.panel.PanelActivityTest' --tests 'com.gt8d.launcher.settings.SettingsActivityTest'`

Git/ADB/他ファイル変更なし。件数/失敗/全決定事項と未確認を報告。
