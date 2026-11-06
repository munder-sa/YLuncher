最初に `tasks/RULES.md` を読み、従う。

# Task 225: 設定画面テストの偽時計を単調時計にも渡す

Task223 で `AppleMusicSession` の再試行の待ちを `monotonicClock`(既定 `SystemClock::elapsedRealtime`)で測るようにした。`app/src/test/java/com/gt8d/launcher/settings/AppleMusicLyricsScreenTest.kt` の `PersistentFixture` は `clock = { now }` だけを渡して `now` を進めているため、`savedRestoreRequiresValidationAndRetryKeepsSessionCooldown` が ComposeTimeoutException で失敗する(全テストで判明。Task223 の指定範囲に settings パッケージが入っていなかった)。

## やること
- `PersistentFixture` の `AppleMusicSession(...)` に `monotonicClock = { now }` を足す。**組み立て部分だけ**の変更で、テスト本体・期待値・assert・名前は変えない(Task223 で `AppleMusicRetentionTest` の Fixture に行ったのと同じ扱い)。
- 同じファイルの他の組み立てや `AppleMusicWebLoginTest.kt` は、時計を進めていないなら変えない。
- アプリ本体は変えない。

## 変えるファイル
- `app/src/test/java/com/gt8d/launcher/settings/AppleMusicLyricsScreenTest.kt`

## 使ってよいコマンド
    /home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.settings.AppleMusic*'

## 報告
- 変更行、実行結果(件数・失敗件数)、決めたこと
