# Task completion
- 影響の狭い変更: 関係するパッケージだけ `~/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.<pkg>.*'`(+必要なら `assembleDebug`)。同じテストを codex と重ねて流さない。
- 広く効く変更(分割・リンク・設定データ・AppGraph)または main に入れる前: 全部 `~/tools/gradle-run.sh test assembleDebug`(= `.codex.conf` の VERIFY_CMDS。codex 委譲後は `codex-verify tasks/NNN-xxx.md` が実行する)。
- 分割・リンク・タスクに触ったら、debug 版を実機に入れて `bash scripts/device-scenarios.sh`(既定ホーム純正/`--with-ylauncher-home` の両モード)。
- linter/formatter/型検査の専用タスクは無い(コンパイルとテストが検査)。
- 設定のスキーマを変えたら CONFIG_VERSION と ConfigCodec の移行テストを確認。版を上げるときは app と panelhost の versionCode/versionName を両方。
