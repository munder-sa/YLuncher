最初に tasks/RULES.md を読む。

# Task211 Task204の時計fixtureを明示的なclockへ

あなたは実装担当。Task204はAPI未対応ではなく新規比較試験の時計設定で停止した。120000msの期待値/イベント/アサーションは維持し、既存26試験は変更しない。司令塔は例外的な期待値変更を承認していない。

- UsageHistoryにinternal var wallClock: () -> Long = System::currentTimeMillisを追加し、launchesSinceのend取得だけをwallClock()にする。Foreground側の既存wallClockと同じパターン。公開constructor/query注入の互換を保ち、productionの時計/窓/フィルタは変わらない。
- 新規UsageEventFilterTestのhistory比較でこのclockを120000へ固定し、その同じclockからlegacy比較のendを取得する。assertEquals(120000,end)、全イベントと期待一覧、境界/順序/種別/パッケージ/スキューの検証を全て維持する。fixtureのSystemClock/ShadowSystemClock依存と不要なinstrumentedPackagesは除去可能（検証を削る意味ではない）。新しい実装を期待値生成に使わず、旧queryEvents(begin,end)を参照として残す。
- Task204の他のファイルは変更不可。版/依存/ADB/Git禁止。成功後再実行なし。29件を指定コマンドで検証し、失敗の修正内容と結果を報告。

許可コマンド:
`/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.apps.UsageHistoryTest' --tests 'com.gt8d.launcher.apps.UsageEventFilterTest' --tests 'com.gt8d.launcher.foreground.*'`
