最初に tasks/RULES.md を読み、従う。

# Task198 よく使うアプリの7日集計を再利用

実装担当。ユーザーは中優先の負荷改善を試し適用することを承認。ホーム復帰のたびの全期間集計を減らす。7日の期間、rankFrequentの連続重複除外/同数順位、除外package、UIを変更しない。

## 変更可能ファイル

- app/src/main/java/com/gt8d/launcher/AppGraph.kt（frequent更新とその初期化/テスト境界だけ）
- 新規 app/src/main/java/com/gt8d/launcher/apps/FrequentAppsRefresher.kt
- 新規 app/src/test/java/com/gt8d/launcher/apps/FrequentAppsRefresherTest.kt

## 要件

1. 現在はIOでlaunchesSinceを7日分照会してrankする。成功結果を保持し、前回成功完了からelapsedRealtimeで**60秒**は全期間照会を省く。初回は即時、期限後の呼出で再集計。常駐タイマーは追加しない。差分集計は今回は不要。
2. 使用状況権限の確認は呼出時にIOで行い、権限失効なら古いランキングを表示せずnull/available=false、再許可後はTTL内でも即集計。通常の失敗では既存の手動fallback表示契約を維持し、失敗を成功として60秒キャッシュしない。連続失敗の再照会は完了から**5秒**以上に抑える。権限変化はこの待ち時間を解除する。
3. 同時呼出は1つの照会へまとめる。CoroutineScopeを呼出ごとに作らず、アプリscope/保持helperを使う。appContext変更・テストoverride/resetの旧jobは新しい結果を上書きできないよう世代管理し、キャンセルを飲み込まない。壁時計の変化にTTLは影響されない。
4. Android読取とrankはIOで、StateFlowの公開とテストoverrideとの整合を維持。既存頻出アプリ画面/ホーム/パネルは変更しない。従来のFrequentログは実集計時だけ維持し、skip毎のログ不要。
5. 制御時計と保留queryで実際の呼出数を検証。10回のホーム復帰に相当する要求が60秒内にあってもquery1回、60秒境界、重複呼出、権限失効/再許可、null/例外/キャンセルと5秒再試行、遅い旧世代、保持/初回、AppGraphへの配線を検証。既存テストの変更禁止。結果の鮮度は最大約60秒＋次の呼出までとなる旨を報告。

## コマンド

- 状態を変えない読み取り。
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.apps.FrequentAppsRefresherTest' --tests 'com.gt8d.launcher.apps.FrequentAppsTest' --tests 'com.gt8d.launcher.apps.UsageHistoryTest' --tests 'com.gt8d.launcher.ui.panel.PanelScreenTest'`

成功した試験の再実行、ビルド、ADB、依存変更、Git更新は行わない。結果・件数・コール数・決めたこと・未確認を報告。
