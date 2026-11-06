最初に tasks/RULES.md を読む。

# Task202 表示中だけの監視とUI更新

ユーザーは追加7候補すべての実装・検証を承認。あなたは実装担当。基点b76b707。別worktreeで作業し、Git/ADB/依存/版変更はしない。

## 要件

- PanelEnvironmentの両overloadでNetworkCallbackと電波receiverをSTARTED以上だけ登録し、STOPとdisposeで対になる解除を行う。START時は最新値、RESUME時は権限とcatalogを再取得する。PAUSEDでもSTARTEDなら分割画面に見えるので動作を維持する。キュー済み旧callbackは登録世代を確認して無視する。まず非表示側の重複登録を除く最小変更とし、アプリ全体singletonへの拡張はしない。
- 電波イベントではラジオ状態だけ更新し、通知/overlay/UsageStats権限を毎回読まない。権限表示は初回/START/RESUMEで最新へ同期。package receiver/cached iconsの動作を維持。登録失敗時の解除例外や二重登録を防ぐ。既存のWifiReaderテスト境界を保つ。
- PanelScreen/HomeActivityのUI向けFlow収集、500ms位置、LyricIndexの200ms行検索、LyricsPaneのintro200ms/position frame更新、HomeClockHeader/PanelContentの時計をSTARTEDで動かしSTOPで休止、再STARTで即時同期する。既存依存のLifecycle APIと最小helperで実現し、新規依存は入れない。MediaWatcher/歌詞取得などアプリ全体のproducerを停止しない。描画/文字/色/周期/選曲/位置補間は変更しない。
- 不必要にhelperを全UIへ拡大しない。環境の既存catalog.snapshot collectは既存観測テストを尊重して維持可能。停止中のdelayとframe clock停止を区別する。
- 新規テストを追加し、STOPで電波登録解除/位置と行検索停止、STARTで最新値に復帰、STARTED未RESUMEDの分割更新、素早いSTOP/START/破棄と旧callback、電波イベントで権限照会が増えないことを検証する。既存テストは変更不可（テスト期待値を構造変更に合わせない）。既存テストが不一致なら原因を報告し停止する。

## 許可ファイル

allowの8実装ファイルと、新規VisibleUiLifecycle.kt/VisibleUiLifecycleTest.kt/PanelVisibilityTest.ktのみ。新規helper/testは必要なもののみ作る。

## 許可コマンド

`/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.panel.*' --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.ui.lifecycle.*'`

新規回帰を先に用意し、実装後に対象試験。成功後は再実行しない。実行回数・件数・決めたこと・未確認を日本語で報告。ログに実際の歌詞/認証値を残さない。
