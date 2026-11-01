最初に tasks/RULES.md を読み、従う。

# Task 114: 必要なときだけ Vendor 分割前に純正ランチャーを温める

## 結論・目的

Vendor 分割前の温めは、次のどちらかの場合だけ行う。

1. 現在のブートでは、まだ温めていない。
2. 直前の Vendor 分割が、監視可能で「形跡なし」のまま時間切れになった。

それ以外は従来どおり即座に openSplit 相当の launcher.open を呼ぶ。
Vendor の3秒後の呼び直し、既存の進展判定、失敗処理は維持する。
温めた純正ランチャーの再開は、分割の進展として扱わない。
Boot でも温めるが、幕は追加しない。

## 根拠と未確認事項

現行コードの根拠:
- SetRunner.kt:245–265: openForBoot は useCurtain=false。
- SetRunner.kt:398–411: 操作受付時刻を記録し、通常操作では幕を表示。
- SetRunner.kt:458–498: モード選択後に launcher.open を呼ぶ。
- SetRunner.kt:551–556: 純正ランチャーの再開を含む進展判定。
- SetRunner.kt:607–629: 形跡がなければ3秒後に同じ要求を呼び直す。
- SetRunner.kt:740–746: Safety 時の進展の有無で失敗計上を判断。
- SetRunner.kt:140–163: キャンセル後の前面化監視。
- CurtainController.kt:55 以降: 表示から4秒の Safety と監視不能時の Timed。
- BootSequence.kt:36–65: 全体期限を予約して3秒後にセットを開始。
- BootSequence.kt:124–136: 全体期限で HOME に戻す。
- ForegroundWatcher.kt:35–45: イベント時刻は sinceMs 以上を対象とする。

パスの基準は app/src/main/java/com/gt8d/launcher/。
SetRunner.kt は sets/、CurtainController.kt は curtain/、
BootSequence.kt は boot/、ForegroundWatcher.kt は foreground/。

依頼元からの実機情報:
- 純正ランチャーのプロセスが存在していても、Activity が未作成だと
  Vendor 分割要求に反応しない場合がある。
- QuickstepLauncher を起動した後に分割が成立した。
- 冷えた E の分割成立に3.3〜3.6秒かかる場面がある。

この作業では上記の実機情報を再確認したとは報告しない。
受信者未登録が原因であること、LMK 等で受信者が消えることは推測。
Activity の再開と受信者の登録完了が同じことも未確認。

300ms、1500ms、幕のための200msは今回採用する調整用の値。
端末で最適と確認した値ではない。
これらの明示した未確認事項は実機確認に残し、実装を進めてよい。
それ以外の新しい不明点には tasks/RULES.md を適用する。

## 変更・作成ファイル（これで全部）

変更:
- app/src/main/java/com/gt8d/launcher/apps/StockLauncher.kt
- app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt
- app/src/main/java/com/gt8d/launcher/curtain/CurtainController.kt
- app/src/main/java/com/gt8d/launcher/AppGraph.kt
- app/src/test/java/com/gt8d/launcher/apps/StockLauncherTest.kt
- app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt
- app/src/test/java/com/gt8d/launcher/curtain/CurtainControllerTest.kt
- app/src/test/java/com/gt8d/launcher/boot/BootRunnerIntegrationTest.kt

作成:
- app/src/main/java/com/gt8d/launcher/split/StockLauncherWarmup.kt
- app/src/test/java/com/gt8d/launcher/split/StockLauncherWarmupTest.kt

温めの状態インターフェースと Android 用の永続化実装も
StockLauncherWarmup.kt に置き、ファイルを増やさない。

BootSequence.kt、foreground/、一覧・ドック、Manifest、
依存ライブラリ、既存設定画面は変更しない。

## 使ってよいコマンド

読み取り・調査は tasks/RULES.md に従う。
ビルド・テスト用に許可するコマンドは次の1本だけ。
引用符を含めて、そのまま実行する。

`/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.apps.*' --tests 'com.gt8d.launcher.split.*' --tests 'com.gt8d.launcher.sets.*' --tests 'com.gt8d.launcher.curtain.*' --tests 'com.gt8d.launcher.boot.*' --tests 'com.gt8d.launcher.ui.apps.*' --tests 'com.gt8d.launcher.ui.panel.*'`

ファイルの編集は編集機能で行う。
git のコミット・リセット・ブランチ操作は禁止。

## 1. 純正ランチャーの起動

StockLauncher.kt:
- stockLauncherIntent() の component、action、flags は変更しない。
- fun startStockLauncher(context: Context): Boolean を追加。
- context.startActivity(stockLauncherIntent()) が正常に戻れば true。
- Exception は DiagLog に記録して false。
- 設定画面の既存呼び出しは変更しない。

## 2. ブート識別と永続状態

StockLauncherWarmup.kt に次の状態インターフェースを置く。

interface StockLauncherWarmupState {
    val bootId: String
    var warmedBootId: String?
    var retryWarmupBootId: String?
}

Android 用実装:
- application context と専用 SharedPreferences を使う。
- SharedPreferences 名は stock_launcher_warmup。
- キーは warmed_boot_id と retry_warmup_boot_id。
- bootId はインスタンス生成時に一度だけ求め、操作ごとに計算し直さない。
- Settings.Global.BOOT_COUNT を取得でき、非負なら count:<値>。
- 未提供・負値・取得時の Exception なら、
  System.currentTimeMillis() - SystemClock.elapsedRealtime() を
  最も近い秒に丸め、time:<秒> とする。
- 異なる方式の識別子は一致とみなさない。
- 保存には SharedPreferences.Editor.commit() を使い、結果を確認する。
- 保存失敗はログに残す。処理を中断せず、同じプロセス内の状態は保持する。
  保存失敗時のプロセス再起動をまたぐ保持は保証できないと報告する。

フォールバックの起動時刻は壁時計の補正で変化し得る。
その場合は別ブート扱いとなり、余分な温めが入り得る。
これは実機確認事項として残す。

温めが必要な条件:
- warmedBootId != bootId、または
- retryWarmupBootId == bootId。

前回ブートの retryWarmupBootId は今回の再温め予約に使わない。

「温め済み」は受信者の生存保証ではなく、起動試行済みの記録。
startStockLauncher が true を返し、温めが取消なしで完了した場合だけ、
warmedBootId=bootId、retryWarmupBootId=null を同じ保存操作で記録する。
再開を観測できず1500msで完了した場合も、起動が true なら記録する。
起動 false または取消では、温め済み記録・予約解除を行わない。

## 3. StockLauncherWarmup の待ち処理

待ち処理自体は Context に依存させない。
既存の Clock、Scheduler、Cancellable、ForegroundWatcher を使う。

依存:
- Clock
- Scheduler
- ForegroundWatcher
- StockLauncherWarmupState
- startStockLauncher: () -> Boolean

公開 API:
- fun needsWarmup(): Boolean
- fun start(coverDelayMs: Long, onFinished: () -> Unit): Cancellable
- fun requestRewarm()

requestRewarm() は retryWarmupBootId=bootId を記録するだけ。
その場で起動や再試行をしない。

時間:
- POLL_MS = 100L
- SETTLE_MS = 300L
- LIMIT_MS = 1500L
- 通常・リンクで温める場合の coverDelayMs = 200L
- Boot の coverDelayMs = 0L

start の時刻を preparationStartMs とする。
coverDelayMs も1500msの上限に含める。

手順:
1. 同一インスタンスで前の待ち処理があれば取り消す。
2. Scheduler 経由で coverDelayMs 後に純正ランチャーを1回起動。
   coverDelayMs=0 でも起動・完了を同期呼び出ししない。
3. 起動直前の Clock 時刻を launchAttemptMs とする。
4. 起動 true で監視可能なら、最初は100ms後、その後100msごとに
   foregroundPkgsSince(launchAttemptMs) を問い合わせる。
5. com.android.launcher3 の再開を初めて観測した時刻から300ms後に完了。
6. ただし、観測後の300ms待ちも含め、
   preparationStartMs+1500ms を超えない。
7. 再開なし、起動 false、権限なし、問い合わせ失敗でも、
   preparationStartMs+1500ms で完了して分割へ進む。
8. 監視不能になったら、その温め中の問い合わせは止める。
9. launchAttemptMs より前の履歴は使わない。
10. 完了・取消時には全タイマーを取り消す。
    世代番号と完了済み状態でも古いコールバックを無効化する。
11. onFinished は Scheduler から最大1回。
    cancel は複数回呼んでも安全にする。
12. 温め済み状態の更新は、有効な完了処理内で onFinished より前に行う。

開始、起動結果、再開観測、完了理由、所要時間、取消、
永続化失敗をログに残す。
完了理由は resumed / timeout / startFailed / unavailable を区別する。

温め失敗そのものを SplitModeSelector の失敗に数えない。
このクラスから Vendor、HOME、幕を操作しない。

## 4. SetRunner への組み込み

SetRunnerDeps に必須の stockLauncherWarmup: StockLauncherWarmup を追加。
省略用の既定値や製品用の温め無効化フラグを追加しない。

AppGraph:
- application context の Android 用状態実装を生成。
- 既存の watcher、Clock、SetRunner と同じ Scheduler を渡す。
- startStockLauncher(app) を呼ぶラムダを渡す。
- runner を作り直しても永続状態を読む構成とする。

SetRunner:
- 通常セット、openForLink、openForBoot の共通 Vendor 経路に組み込む。
- インストール確認、分割対応確認、モード選択を通過した後に判定。
- needsWarmup() が true のときだけ温める。
- false なら遅延を追加せず、従来どおり即 launcher.open。
- HOME、LeftOnly、AppPair、アプリ不足・分割非対応による退避は温めない。
- 同一操作の3秒後の呼び直しでは温め判定・温めを行わない。

操作受付時に opening を設定し、温め中も保持する。
Opening.startedAtMs と lastOpenedAtMs は操作受付時刻のまま。
温めだけで readyCount、lastReadyAtMs、成功通知を更新しない。

既存の pending とリンク受付規則を維持する。
温め中の openForLink は false。
温めや Vendor 要求を並列に進めない。

## 5. 分割要求から計時し、温めを進展に混ぜない

初回 Vendor の launcher.open 直前に splitStartMs を取り直す。
温めを省略した場合も同じ位置を起点にする。

- ownScreenSeen、panelResumed、bothProgress を初期化。
- 分割後の再開イベント検索は splitStartMs+1L から。
  ForegroundWatcher の >= 判定により、同じミリ秒の温めイベントも除外する。
- waitBoth の成功判定と進展判定に同じ検索開始時刻を渡す。
- 温め中は ownScreenResumed / panelResumedInSplit を
  分割の進展として蓄積しない。
- タイマーと経過時間の計算には splitStartMs を使う。
  検索用の +1L を待ち時間へ加えない。
- 監視不能時の残り時間計算も splitStartMs を基準にする。
- 3秒後の呼び直しは splitStartMs+3000ms。
- 通常・リンクの Safety は splitStartMs+4000ms。
- 呼び直しで splitStartMs や Safety を更新しない。

分割要求後の純正ランチャー再開、片側再開、
ownScreenSeen、panelResumed は、既存どおり進展に含める。
パネルホストの再開だけでは進展にならない既存仕様も維持する。

launcher.open が同期的に時間を消費しても期限が後ろへずれないよう、
残り時間を求めて予約する。
期限を過ぎていた場合は残り0msを Scheduler に予約する。
初回要求前には分割待ちの状態を整え、
要求の戻り後は同じ操作が有効か再確認する。

AppPair の計時・再試行・進展判定は変更しない。

## 6. 3秒後の呼び直しと再温め予約

Vendor の既存の呼び直しを維持する。

- 分割要求から3秒後、未成立かつ形跡なし、監視可能なら1回呼び直す。
- すでに形跡があれば呼び直さない。
- 呼び直し時も左右・比率・redirect の既存動作を維持。
- EntryMissing / Exception、通知、全画面退避、
  SplitModeSelector の扱いも既存どおり。
- 同一操作の再要求は既存の最大1回を超えない。

再温めを予約するのは、現在の操作が Vendor の分割待ちで、
監視可能なまま「形跡なし」で時間切れになった場合だけ。

通常・リンク:
- Safety において進展を問い合わせる。
- 問い合わせ後も監視可能で進展なしなら requestRewarm()。
- 113 の既存の失敗計上と Slow 通知は維持。
- 進展判定結果を共有し、再温め予約と失敗計上を食い違わせない。

Boot:
- BootSequence の全体期限から呼ばれる abortBootToHome() と、
  SetRunner 内の既存 Boot 待ち期限で、状態破棄前に同条件を判定。
- 再温め予約だけ行い、失敗数増加・Slow 通知は追加しない。
- Boot の中断・取消・成功を「形跡なしの時間切れ」に分類しない。
  既存呼び出し元で区別不能な場合は、
  abortBootToHome に既定値付き引数を加え、
  期限による呼び出しであることを明示する。
  既存の BootSequence/AppGraph の接続で期限と取消を区別できるか確認し、
  許可ファイル内で区別できなければ RULES の3点セットで停止する。

監視不能、Timed、起動失敗、ユーザーキャンセル、単独起動への退避、
AppPair の時間切れでは予約しない。
「監視不能」を「形跡なし」と推定しない。

時間切れ後、その場で温め直したり Vendor を要求したりしない。
次に実際の Vendor 要求経路へ入った操作で温める。
その間に HOME や AppPair を使っても予約は消さない。

## 7. 幕

温めが必要な通常・リンクだけ、幕を準備段階から開始する。

CurtainController に追加:
- fun showPreparing(target: CurtainTarget)
- fun beginWaiting(expectedMs: Long, watcherAvailable: Boolean)

showPreparing:
- 幕を表示する。
- 表示から5500msで準備段階の Safety。
- Timed は予約しない。
- progress は最初の表示から800ms。
- 表示中なら target 更新のみ。期限を延長しない。

beginWaiting:
- 準備段階から分割待ちへ1回だけ移行。
- 準備段階の Safety を取り消す。
- 呼び出しから4000msで Safety。
- watcherAvailable=false なら expectedMs 後に Timed。
- view.hide / view.show / onHidden を呼ばない。
- progress の起点は最初の表示のまま。重複表示しない。
- 非表示・移行済みなら何もしない。

cancel/ready/fail で両段階のタイマーを取り消す。
表示世代で古いタイマーも無効化する。
既存 show/update/ready/fail/cancel の仕様は維持する。

SetRunner の順序:
- 温めあり: showPreparing → 200ms後に純正起動
  → 温め完了 → beginWaiting → Vendor 要求。
- 温めなし: 既存 show → 即 Vendor 要求。
- 温めありの beginWaiting と splitStartMs は
  初回 Vendor 要求直前の同じ処理で設定する。

現在は幕をモード判定より前に表示しているため、
温め対象 Vendor では通常 show のタイマーを先に走らせないよう整理する。
非 Vendor・各退避の既存表示・通知・完了動作は維持する。

温め中に幕が取り消し・終了されたら温めを取り消し、
遅れて Vendor を要求しない。
pending の target 更新で期限を延長しない。

最大予定待ちは温め1500ms+分割待ち4000ms。
監視不能時の800ms/3500msの Timed は分割要求から数える。

200msは既存フェードインへの余裕であり、表示完了保証ではない。
幕の接続がない場合や表示失敗でも既存どおり処理を続ける。
純正ホームが絶対に見えないとは報告しない。

## 8. Boot

Boot は useCurtain=false のまま。幕の追加は範囲外。
openingForBoot と useCurtain の既存の対応も変更しない。

- 必要な Vendor 経路だけ温める。
- 幕待ちの200msは付けない。
- 再開観測後300ms、温め開始から最大1500ms。
- BootSequence の3秒カウントダウンと開始から10秒の期限を変更しない。
- 全体期限を温め開始・完了・Vendor 要求で延長しない。
- abortBootToHome は温め、分割待ち、pending、
  リンクの両コールバックを破棄して HOME に戻す。
- 成功時の readyCount、stayHome の既存挙動を維持。
- 全体期限後の温め完了や遅い成功で追加要求・成功通知を出さない。

通常カウントダウンなら、Vendor 初回要求は遅くとも4500ms。
分割要求後3秒で形跡なしなら、Boot でも従来どおり呼び直す。
したがって「3.6秒後に成立すれば必ず要求1回」とは規定しない。

Boot の温めで純正ホームが一瞬見えることは許容し、
実機確認事項に記載する。

## 9. 取消・終了

- cancelTimers に温めの Cancellable も含める。
- cancelOpening、abortBootToHome、幕の終了、操作終了時に
  操作世代を無効化する。
- 同時刻の取消と温め完了でも、取消後に Vendor を要求しない。
- 古い callback は新操作の opening、readyCount、pending、
  リンク通知、温め済み記録に影響しない。
- 通常操作の cancel guard に STOCK_LAUNCHER_PKG を追加。
- 5秒期限、HOME の追加起動最大2回、
  次の操作で監視停止という既存仕様は維持する。
- 同じインデックスの連打も既存の pending 消化規則を維持する。

## 10. テスト

既存 FakeScheduler、JVM / Robolectric を使う。
Thread.sleep、実時間待ち、実機受信者の存在に依存しない。
起動時刻、要求回数、イベント時刻、永続状態を検証する。

StockLauncherTest:
- 既存の Intent 検証を維持。
- startStockLauncher_successUsesExistingIntent
- startStockLauncher_exceptionReturnsFalse

StockLauncherWarmupTest:
- bootId_usesBootCount
- bootId_unavailableFallsBackToRoundedBootTime
  未提供・負値・Exception を検証。
- state_survivesRecreationWithinBoot
- state_differentBootRequiresWarmup
- state_previousBootRetryDoesNotForceWarmup
- state_commitFailureKeepsProcessState
- needsWarmup_falseOnlyWhenWarmedAndNoRetry
- startsOnceAfterCoverDelay
  199msでは起動なし、200msで1回。
- boot_startsWithoutCoverDelay
  start 自体からは同期起動しないことも検証。
- freshResume_waits300ms
- oldResume_doesNotCompleteWarmup
- lateResume_doesNotExtend1500msLimit
- noResume_finishesAt1500ms
- successfulStartWithoutResume_recordsWarmed
- failedStart_keepsWarmupRequired
- unavailableOrQueryFailure_finishesAtLimit
- cancel_doesNotCompleteOrRecordWarmed
  起動前・起動後・settle中を検証。
- restart_invalidatesPreviousCompletion
- deadlineAndPoll_finishOnlyOnce
- requestRewarm_survivesRecreationAndClearsOnWarmup
- failedOrCancelledWarmup_keepsRewarmRequest

CurtainControllerTest:
- preparing_hasNoTimedHide
- preparing_safetyAt5500
- beginWaiting_startsSafetyFromTransition
  1500msで移行し、5499msで表示中、5500msで Safety。
- beginWaiting_startsTimedFromTransition
  1500msで移行、expectedMs=800なら2300msで Timed。
- beginWaiting_keepsViewAndProgress
- beginWaiting_twiceDoesNotExtendDeadline
- cancelPreparation_invalidatesOldTimers

SetRunnerTest:
- vendor_firstRequestInBootWarms_allEntryPoints
  通常・リンク・Boot をそれぞれ独立した状態で検証。
  通常・リンクは幕→純正起動→Vendor。Boot は幕なし。
- vendor_sameBootNextRequestOpensImmediately
- vendor_recreatedRunnerSkipsWarmupWithinBoot
- vendor_newBootWarmsAgain
- vendor_noProgressTimeoutRewarmsNextRequest
- vendor_progressTimeoutDoesNotRequestRewarm
- vendor_monitoringUnavailableDoesNotRequestRewarm
- vendor_cancelOrLaunchFailureDoesNotRequestRewarm
- vendor_retryAt3000FromSplitRequest
  温め1500msなら4499msで要求1回、4500msで2回。
- vendor_postSplitProgressSkipsRetry
- vendor_warmupProgressDoesNotSkipRetryOrFailureCount
  splitStartMs と同じミリ秒の温めイベントも除外。
- vendor_safetyAt4000FromSplitRequest
- vendor_retryDoesNotExtendSafety
- vendor_synchronousOpenTimeDoesNotExtendDeadlines
- vendor_warmupFailureStillRequestsSplit
- nonVendorAndFallback_doNotWarm
- monitoringUnavailable_keepsCurtainThroughWarmup
- cancelDuringWarmup_neverRequestsVendor
- pendingDuringWarmup_doesNotStartConcurrentWarmup
- oldWarmupCompletion_doesNotAffectNewOpening
- cancelGuard_includesStockLauncher
- link_callbacksAreNotTriggeredByWarmup

BootRunnerIntegrationTest:
- warmupAndSplit_completeBeforeOriginalBootDeadline
  3000msで開始、4500msで温め上限、分割後に片側、
  続いて両側の再開を入れる。
  形跡により3秒後の呼び直しを省略し、
  8300msまでに完了、HOMEなし、幕なしを検証。
- abortDuringWarmup_cancelsLateVendorRequest
- timeout_keepsOriginal10000DeadlineWithoutCurtain
  9999msではHOMEなし、10000msでHOME1回。
  失敗数増加・Slowなし。遅延callback・pendingを無視。
- noProgressBootTimeout_requestsRewarmForNextVendor
- progressOrUnavailableBootTimeout_doesNotRequestRewarm
- bootRetry_remains3000msAfterSplitRequest

既存テスト:
- 温めを扱う新規テスト以外の fixture は、同一ブート温め済みの
  明示的な状態 fake を渡して、従来の即時要求経路を検証する。
- BootRunnerIntegrationTest の既存 fixture も同様。
- Vendor 呼び直しの既存テスト名・期待値・失敗経路を維持。
- AppPair、113の失敗計上、redirect、左右・比率、
  リンク、pending、Boot の幕なし・期限の検証を維持。
- BootSequenceTest は変更せず通す。
- 一覧・ドックの109の除外テストも変更せず通す。
- 製品コードへテスト専用の温めスキップを追加しない。
- 既存期待値の変更、テスト削除・改名・置換は行わない。
  食い違いがあれば RULES の3点セットで停止する。

## 範囲外

- 毎回の温め、3秒後の呼び直し廃止。
- Boot の幕追加。
- ForegroundWatcher、UsageStats の取得・時刻補正の変更。
- プロセス一覧・受信者数・shell・隠しAPIによる製品内判定。
- VendorBridge の broadcast、extra、ratio の変更。
- SplitModeSelector の失敗基準の変更。
- BootSequence のカウントダウン・期限・stayHome の変更。
- 純正ランチャーのタスク削除、最近のタスク一覧の制御。
- HiddenApps、一覧・ドックの除外変更。
- Manifest、権限、依存ライブラリの追加。

## 実機確認事項

この作業では実施済みと報告しない。

- 再起動直後、通常・リンク・Boot の最初の Vendor が成立する。
- 同じブートの次のセット切り替えでは純正ホームを挟まない。
- アプリプロセス再起動後も温め済み識別子が保持される。
- 「形跡なし」の時間切れ後、次の Vendor だけ温め直す。
- LMK 等の後に、時間切れ→次回温めで回復するか。
  LMK を直接検知する実装ではないため、直後の成功は保証しない。
- 通常・リンクで幕によって純正ホームの露出が抑えられる。
- Boot では純正ホームの一瞬の表示を許容し、10秒期限を守る。
- 300ms待ち、1500ms上限、200msの幕待ちが実機に適する。
- 3秒後の呼び直しは維持される。
  分割後に形跡がないまま冷起動が長引く場合の解除リスクは残る。
- BOOT_COUNT の取得可否と、フォールバック時の壁時計補正の影響。
- 純正ランチャーが YLuncher の一覧・ドックに出ない。
- 温め済み記録や再開観測は、受信者の生存を保証しない。

## 報告

日本語で結論から書く。

- 変更点と根拠のファイル・行。
- 実際に実行したコマンド。
- テスト件数・失敗件数。未実行なら明記。
- 未実施の実機確認事項と残る制約。
- 指示書外で決めたことを「決めたこと」にすべて列挙。
  なければ「決めたこと: なし」。
