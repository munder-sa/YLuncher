最初に tasks/RULES.md を読む。

# Task217 時計の計測再利用と分イベント更新

あなたは実装担当。ユーザーはbeta2後の6候補を進めることを承認。別worktreeで時計だけを変更する。Git/ADB/版/依存変更は禁止。既存テストは変更しない。

## 要件

- HomeClockHeaderの毎秒、PanelContentの10秒delayを、表示中のACTION_TIME_TICK/ACTION_TIME_CHANGED/ACTION_TIMEZONE_CHANGEDの動的receiverへ置換。既存VisibleUiEffectのSTARTED範囲を使い、開始時の読み取りは1回、STOP/disposeで解除、STARTで即時最新値。非表示の旧receiver/キュー済みcallbackで更新しない。INITIALの表示用readとSTART時の同期を区別する。システムbroadcastの登録flagは既存Android35構成に合わせる。不要な常駐timer/singletonは作らない。
- now注入はHomeClockHeaderの既存APIを維持。内部rememberMinuteTimeをui/lifecycle/MinuteClock.ktに作り、時計・日付の両方へ同じLocalDateTimeを渡す。テスト用clockを交換した場合にも古い値を参照しない。表示形式HH:mm/M月d日(E)、Locale.JAPAN、色/フォント/サイズ/日付配置は変えない。
- HomeClockHeaderとFitClockのサイズ探索は、制約・height・min/max・density/fontScale・layoutDirection・style・font resolverの実際に解決されたfontの変化と実際の文字列をキーに結果をrememberする。非関連の再compositionで再計測しない。同じgeometry内の「00:00」計測は候補ごとの有界cacheで再利用してよい（上限128、超過はcacheせず正しく計測）。文字サイズ探索の刻み2spとoverflow判定を維持し、二分探索へ変更しない。
- 親/子では制約が異なるので、計測結果の無条件共有はしない。文字列変更時の実際のグリフの幅/高さ検証は維持。フォント解決完了時やテーマ変更を含む無効化漏れを防ぐ。新helperは必要最小限。TextLayoutResultを時刻ごとに永久蓄積しない。
- 小さなinternal観測hook（必要ならLocalClockLayoutObserver）で本当にTextMeasurer.measureへ到達した回数を数え、無関係な再compositionで増えないこと、時刻/幅/height/fontScale/style変更で必要な計測と表示更新が起きることを新規ClockLayoutReuseTestで確認。既存の12条件FitClockTestと日付テストをそのまま通す。
- 新規MinuteClockTestで毎分/時刻補正/タイムゾーンイベント、日付跨ぎ、STOP中の受信なし・readなし、STARTED未RESUMEDの表示、再STARTで1回最新化、dispose/素早い切替/clock交換を確認。UIの実表示またはread観測を含め、helperの実装の写しだけのテストにしない。

## 変更範囲

allowの実装3ファイル、新規MinuteClock.kt/ClockLayoutCache.kt、テスト2ファイルだけ。新helperのうち不要なものは作らなくてよい。既存テストの期待値・assert・名前は維持。

## 許可コマンド

`/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.HomeClockHeaderTest' --tests 'com.gt8d.launcher.ui.layout.FitClockTest' --tests 'com.gt8d.launcher.ui.layout.ClockLayoutReuseTest' --tests 'com.gt8d.launcher.ui.lifecycle.*' --tests 'com.gt8d.launcher.ui.panel.PanelContentTest'`

対象試験のみ、成功後に理由なく再実行しない。失敗を修正する場合は原因と変更を記録。日本語で差分・試験件数/失敗・決めたこと・未検証を報告。
