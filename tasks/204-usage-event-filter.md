最初に tasks/RULES.md を読む。

# Task204 利用履歴を取得時点でRESUMEDへ限定

ユーザー承認済み7候補。あなたは実装担当。min/compileSdk35。UsageHistoryとUsageStatsForegroundEventsの実OS照会をUsageEventsQuery.Builder(begin,end).setEventTypes(UsageEvents.Event.ACTIVITY_RESUMED).build()へ変更する。公式: https://developer.android.com/reference/android/app/usage/UsageEventsQuery.Builder

- 既存begin/end計算、package範囲、OwnResumeClockの目印/skew計算、失敗/権限時挙動、返却順序/時刻フィルタと注入queryを維持。新しいpackage絞込みやasync化はここで混ぜない。
- API呼出しの共通internal helperを必要ならUsageResumedQuery.ktへ作る。異常時に黙って全イベント取得へフォールバックしない。RobolectricでAPI未実装の場合も期待値や試験対象を変更せず原因を報告する。
- 新規UsageEventFilterTestで実際のQuery builderの種別/時刻/パッケージ未限定を検証し、混合イベントの旧フィルタと新経路の出力を比較する。既存注入テストだけで実OS対応を確認済みにしない。実機照合は司令塔が別プローブで担当する。
- allow以外の変更・既存テスト変更・ADB/Git/依存/版変更禁止。

許可コマンド:
`/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.apps.UsageHistoryTest' --tests 'com.gt8d.launcher.apps.UsageEventFilterTest' --tests 'com.gt8d.launcher.foreground.*'`

成功後の再試験なし。件数/実行回数/変更と決定を報告。
