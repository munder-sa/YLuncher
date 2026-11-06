最初に tasks/RULES.md を読む。

# Task205 waitBothの周期照会をmainから分離

ユーザーは追加7候補すべてを承認。あなたは実装担当。Task204のRESUMED絞込み後を基点にする。先行相談の docs/performance/2026-11-06-runtime-cost.md「SetRunnerを次段階へ分ける判断」と現行コードを読む。

## 変更の境界

- 非同期にするのはSetRunner.waitBothの200ms pollだけ。初回監視probe、retry 3秒、Safety 4秒/rewarm、waitSingle、cancel guard、StockLauncherWarmup/LinkEntryの同期判断と期限を維持する。全query非同期化/古いpoll cacheによるSafety判定への置換は禁止。
- ForegroundWatcherとUsageStatsForegroundEventsに、mainで照会条件を準備→backgroundで副作用のない読取り/解析→mainで状態確定、の境界を設ける。snapshotはsince/window、wall/elapsed、OwnResumeClock目印、既存skew等を不変で持つ。backgroundでskew/loggedSkew/lastQuerySucceeded/OwnResumeClockを更新しない。成功空一覧と照会失敗を区別する。
- 全同期照会と非同期照会で共有の単調増加request番号をmainで採番し、同期retry/Safety/warmup/Linkの新しい照会を古いpollで巻き戻さない。同期APIの戻り値/既存Fakeの互換を維持し、同期経路も同じ確定境界を使う。availableの権限確認だけでrequestを無意味に更新しない。
- poll結果の確定前にoperationGeneration/同じopening/WaitKind.Both/monitoringAvailableとrequest順序を確認。失効なら成功状態もskewもログも変更しない。明示cancel/ホーム/次セット/timeoutでin-flightを無効化する。Binderはcancelしても戻り得るので反映時の防御を必須とする。
- pollは同時1件、完了または古い結果棄却後200msで次を予約する。未完了中はretry/Safetyのmain判断をブロックしない。旧操作のbackgroundが残る間に新操作が来ても無制限の照会を溜めない。
- AppGraphの実配線でbackground実行とmain反映が有効になることを必須にする。SetRunnerDepsに注入可能なpoll実行境界を追加してよい。既存テスト用の同期Fake互換は維持するが、productionが同期fallbackにならない配線試験を追加する。既存applicationScopeを用い、Main/IOの責務を明記する。
- 通常経路への高頻度ログ追加、依存/版変更はしない。

## 検証

- 既存テストは変更不可。新規AsyncForegroundReadTest/SetRunnerAsyncPollTest/UsageStatsSnapshotTestで遅延・順序逆転を制御し、保留中キャンセル/別セット、旧成功/旧失敗/旧skew、同期retryが先に確定、Safety跨ぎ、権限失効/回復、同時1件、mainとworkerの責務を検証。UI/世代だけでなく共有stateが変わっていないことまで確認する。
- 独立した旧挙動を期待値に使う。テスト内容の差替え/閾値緩和/skipは不可。環境や仕様の不一致は報告して止まる。

許可コマンド:
`/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.sets.SetRunnerTest' --tests 'com.gt8d.launcher.sets.SetRunnerAsyncPollTest' --tests 'com.gt8d.launcher.foreground.*' --tests 'com.gt8d.launcher.split.StockLauncherWarmupTest' --tests 'com.gt8d.launcher.link.LinkEntryActivityTest'`

allow外/ADB/Git変更禁止。先に競合回帰を追加し実装後に対象検証。成功後再実行なし。実コマンド・件数・決めたこと/未確認を報告する。
