# Task 037a: 結合修正: 起動の時間切れと設定の読み込み

最初に `tasks/RULES.md` を読み、従うこと。

レビュー `docs/reviews/2026-10-29-codex-review.md` の A3/D1・C2/D2・D4 と、
034・035 で残した結合上の問題を修正する。
仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の
§4.8(3・4)・「読み込みの規則」・§7.2、および本指示書の司令塔方針に従う。

033〜036 をマージ済みの現在の main から開始する。
037b と別の git worktree で同時実行する。
worktree の準備とマージは司令塔が行う。
実装者は git のコミット・リセット・ブランチ・worktree 操作をしない。

パス接頭辞:
`M = app/src/main/java/com/gt8d/launcher`
`T = app/src/test/java/com/gt8d/launcher`

## 作成・変更するファイル(これで全部。これ以外のファイルは作らない・変えない)

新規作成:
- `T/boot/BootRunnerIntegrationTest.kt`
- `T/config/AppGraphConfigTest.kt`

変更:
- `M/sets/SetRunner.kt`
- `M/config/ConfigRepository.kt`(D4 の原本保護が再現テストで破れている場合だけ)
- `M/AppGraph.kt`
- `T/sets/SetRunnerTest.kt`
- `T/config/ConfigRepositoryTest.kt`

BootSequence の合計10秒の実装と既存単体テストは変更しない。
この Task の起動修正は SetRunner と AppGraph の接続で行う。

## 使ってよいコマンド

読み取り・調査のコマンドは RULES の範囲で使用してよい。
ビルド・テストは次の2行だけ。全テスト `test` は実行しない。

- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.sets.SetRunnerTest --tests com.gt8d.launcher.boot.BootSequenceTest --tests com.gt8d.launcher.boot.BootRunnerIntegrationTest --tests com.gt8d.launcher.config.ConfigRepositoryTest --tests com.gt8d.launcher.config.AppGraphConfigTest`
- `/home/munde/tools/gradle-run.sh assembleDebug`

Gradle はバックグラウンドに回さず、終了まで待つ。

## 仕様

### 1. 指摘ごとに修正前の失敗を確認する

各指摘について、まずテストを書き、製品の振る舞いを変更する前に実行する。
失敗した assert・期待値・実測値を記録する。

コンパイルエラーは不具合の再現に数えない。
新しい API やテスト用入口が必要なら、既存の振る舞いを変えない形で
入口だけを先に追加し、コンパイル可能な状態で assert の失敗を確認する。

追加したテストが修正前から通る場合は、準備が指摘の条件と一致するか確認する。
一致しても通るなら、その条件では当該指摘は誤りとして扱い、
その部分の製品コードは直さず、報告に書いて次へ進む。
通った回帰テストは残す。別条件まで正しいとは断定しない。

D4 は「原本の保護」と「AppGraph での例外処理」を別々に判定する。
原本保護のテストだけが通っても、購読側の修正を省略しない。

既存テストの名前・期待値・検証は変更しない。
この Task に RULES の「テストを弱めない」の例外はない。
034 で承認された BootSequenceTest の合計10000ms・homed=1 の期待値を維持する。
根拠は仕様 §4.8(3・4)・§7.2。

### 2. 起動中断の専用入口

SetRunner に `fun abortBootToHome()` を追加する。

現在の経路を識別するため `private var openingForBoot = false` を持つ。
`openSet` で `_opening` を非nullにするより前に、
`openingForBoot = !useCurtain` を設定する。
通常経路・リンク経路は false、起動経路は true になる。
通常の終了処理では `_opening` をnullにするより前に false に戻す。

`abortBootToHome()` は次の順で処理する。

1. `_opening` が非null、かつ openingForBoot が false なら即 return。
   通常経路・リンク経路の操作を優先する。
   タイマー・保留・幕・番号・記録を一切変更しない。
2. `cancelTimers()` で SetRunner のポーリング・再試行・BOOT_CLEAR の予約を全部取り消す。
3. `pending = null`、`linkFallback = null`、`waitKind = WaitKind.None`、
   `panelResumed = false`、`openingForBoot = false` にする。
   保留は通常要求・起動要求のどちらでも捨てる。
   `runPendingIfNeeded()` と `finishWithoutCurtain()` は呼ばない。
4. `currentIndex` と `d.lastIndex.lastSetIndex` を `SetNavigator.HOME` にする。
   `lastOpenedPair` と `lastOpenedAtMs` をnullにする。
5. `_opening.value = null` にする。
6. `d.startHome()` をその場で1回呼ぶ。

幕の show/update/ready/fail は呼ばない。
readyCount、WarmTracker、SplitModeSelector の成功・失敗記録を更新しない。
Messenger も呼ばない。

待ちが既に終了して `_opening` がnullの場合も、
取り消し後の成功検出からホームへ戻れるよう、上記2〜6を実行する。
この入口は BootSequence 専用とし、通常操作には接続しない。
呼び出し回数の上限は既存の BootSequence.callGoHomeOnce が保証する。

AppGraph の BootSequence の接続を
`goHome = { runner.abortBootToHome() }`
に変更する。
openLast・splitAppeared・record と BootSequence の期限管理は変更しない。

中断後に届く panelResumedInSplit や前面化イベントによって、
終了した待ちを再開したり、readyCount・暖かい記録・成功記録を増やしたりしない。
その後に利用者が開始した新しい操作は通常どおり動くこと。

保証する時刻は、開始から10000msで startHome を呼ぶ時刻。
Android が画面を描画し終わる時刻までは単体・結合テストの保証に含めない。

### 3. 設定未読込時の操作拒否

`SetRunnerDeps.config` を `() -> LauncherConfig?` に変更する。
AppGraph は `config = { latestConfig.value }` を渡す。
未読込時に Defaults を作るフォールバックを削除する。

SetRunner 内に
`private fun configForOperation(): LauncherConfig? = d.config()`
を置き、操作入口の設定取得をここに集める。

open・next・prev・home・openForBoot・openForLink は、
最初に設定を取得し、nullなら何もせず終了する。
openForLink だけは false を返し、onNotSplit は呼ばない。

設定がnullの間に以下をしてはいけない。
- アプリ・ホームの起動
- 幕の表示・更新
- 保留要求の登録・上書き
- currentIndex・保存番号・lastOpenedPair・lastOpenedAtMs の変更
- readyCount・暖かい記録・方式の成功失敗記録の更新
- タイマーの登録
- 読込後の自動再実行

home() も未読込なら断る(司令塔の決定。今は保存番号を0にしてしまうため)。
また nullable 化によって既存の home/targetFor が例外を投げないよう、
targetFor の設定未読込時は、既存のホーム用 CurtainTarget と同じ値を返す。
count は1、position は0とする。Defaults は作らない。

SetRunner の生成時、設定がnullなら currentIndex は保存番号をそのまま保持し、
保存番号を書き戻さない。
設定が非nullなら従来どおり SetNavigator.clamp を使う。

受理した操作では取得した非nullの設定を引数で内部処理へ渡し、
セット選択・件数・その操作の target の構築で使い回す。
内部処理で `d.config()!!` や `requireNotNull` を使わない。
next/prev の基準番号の正規化は既存の SetNavigator に任せる。

保留要求を実行するときも同じ公開入口を通し、
その時点で未読込なら捨てる。新しい保留機構は作らない。

abortBootToHome は通常の設定依存操作ではなく、
既に受理された起動処理の終了入口なので、このnull判定を適用しない。

Panel・リンク・帯側は変更しない。
openForLink=false の場合は既存17bの全画面ブラウザへの処理に任せる。

### 4. D4: 読込失敗と退避失敗

#### Repository の原本保護

壊れた設定・未来versionを既定値へ戻すのは、
`config.bak.json` へのコピーが成功した場合だけとする。

コピーが失敗した場合は、その失敗を購読側へ伝播させる。
原本の削除・切り詰め・既定値による上書きをしない。
例外を握りつぶして Defaults を返してはいけない。

現実装の copyTo の失敗伝播でこの条件が満たされているなら、
ConfigRepository の製品コードは変更しない。

#### AppGraph の購読

次を追加する。

- `private val _configError = MutableStateFlow(false)`
- `val configError: StateFlow<Boolean> = _configError.asStateFlow()`

本番とテストが同じ購読処理を通るよう、
AppGraph.kt 内に次の internal suspend 関数を置く。

`internal suspend fun collectConfig(source: () -> Flow<LauncherConfig>)`

既存の latestConfig の IO CoroutineScope から
`collectConfig { config.config }` を1回呼ぶ。
source の評価も例外処理の内側で行い、
Repository の生成時の I/O エラーも同じ経路で受ける。

購読成功時:
1. 既存どおり _latestConfig を更新する。
2. 既存どおり EdgeStripService.gestureConfig を更新する。

購読失敗時:
1. CancellationException は再送出する。
2. IOException を受ける(CorruptionException を含む)。
3. `DiagLog.d("Config", "load failed: $e")` を1回呼ぶ。
4. `_configError.value = true` にする。
5. 初回読込に失敗した場合、_latestConfig はnullのままにする。
6. 正常設定を発行せず、購読を終了する。再試行しない。

IOException 以外のプログラム上の例外を一括して握りつぶさない。
成功後に購読が I/O エラーで終わった場合は、
最後の正常設定を保持し、configError=true にする。
初回失敗時のnullを保証するために、正常設定までnullへ戻さない。

テストの状態復元用に
`internal fun resetConfigStateForTest()`
を追加する。
内容は _latestConfig=null、_configError=false のみ。
本番から呼ばず、実際の latestConfig の常駐購読が走るテストとも混在させない。

次回のプロセス起動では従来どおり読み直す。
永続的な失敗フラグ、同一プロセス内の再試行、既定設定での操作継続は追加しない。

HomeActivity の非null設定待ちは変更しない。
初回失敗時には設定が発行されないため、自動起動を開始しない。

## 手順(TDD)

1. 033〜036 の指示書、特に「この Task でしないこと」と、
   対応する現在の実装・テストを読む。

2. A3/D1 の再現テストを `BootRunnerIntegrationTest` に追加する。
   BootSequence・SetRunner・CurtainController・ForegroundWatcher・WarmTracker は実物を使う。
   SplitLauncher・ActivityLauncher・保存先・ForegroundEvents・CurtainView はテスト内の偽物を使う。
   Scheduler と Clock は同じ既存 FakeScheduler を使う。
   startHome の呼出し時刻を一覧で記録する。
   共通テストファイルは変更しない。

   修正前の goHome は現在の AppGraph と同じ
   `{ runner.openForBoot(SetNavigator.HOME) }` にする。
   まず次のテストの assert が失敗することを確認する。
   修正後は `{ runner.abortBootToHome() }` に差し替える。

   - `timeout_startsHomeAt10000_andIgnoresLateSuccess`
     - 0msにstart、3000msに左右が別アプリのセットを開く。
     - 左だけのイベントをその発生時刻になってから投入する。
     - 9999msではホーム0回、BootPhase.Opening。
     - 10000msではホーム呼出し時刻が厳密に `[10000L]`、
       opening=null、currentIndex・保存番号=0、
       lastOpenedPair・lastOpenedAtMs=null、BootPhase.Done。
     - readyCount=0、左右ともisWarm=false、方式の失敗回数=0。
     - 10200msに右のイベントとpanel通知を投入し、30000msまで進める。
     - ホーム呼出し・readyCount・暖かい記録・方式状態が増えず、
       10000ms時点から分割呼出し回数も増えない。

   - `timeout_discardsPendingNormalAndBootRequests`
     - 同じ起動中に openForBoot(別セット)、続いて open(さらに別セット)を呼ぶ。
     - 10000msでホームを呼び、保留セットは開かない。
     - 30000msまで進めても保留操作が実行されない。

   - `cancelDuringOpening_startsHomeAtOriginalDeadline`
     - 3000msで開いた後にstayHome。
     - 9999msではホーム0回、10000msでホーム1回・StayedHome。
     - 記録はboot_cancelだけ、方式の失敗回数は増えない。

3. 起動中断入口の補助テストを SetRunnerTest に追加する。
   新入口の仮実装は、既存動作と同じ
   `openForBoot(SetNavigator.HOME)` 呼出しだけにして赤を確認してよい。

   - `abortBootToHome_duringNormalOpening_doesNothing`
     通常open中に呼ぶ。タイマー・保留・幕・番号・ホーム呼出しが不変。
     その後、通常操作が従来どおり成功する。
   - `abortBootToHome_duringLinkOpening_doesNothing`
     リンクopen中に呼ぶ。falseへの終了やfallback呼出しを起こさず、
     リンク操作が従来どおり完了する。
   - `abortBootToHome_afterBootReady_startsHomeWithoutCurtain`
     起動セットの成立と待ち終了後に呼び、ホーム1回、幕なしを確認する。
     既に記録されたreadyCount・暖かい記録は増減しない。
   - `abortBootToHome_cancelsSingleWait`
     起動経路をwaitSingleに入れて中断。
     遅れて前面化イベントを与えてもreadyCountが増えない。
     中断前の直接起動成功による暖かい記録を取り消す検証はしない。
   - `abortBootToHome_thenNormalOpen_canComplete`
     中断後に新しい通常openを開始し、成功できる。

4. C2/D2 のテストを SetRunnerTest に追加する。
   TestEnv の既定動作は維持し、テストごとに設定供給をnullへ切り替えられるようにする。
   nullable API の導入前でも動作再現できるよう、
   必要な入口だけを先に追加し、拒否処理を入れる前に赤を確認する。

   - `loadingConfig_open_doesNothing`
   - `loadingConfig_next_doesNothing`
   - `loadingConfig_prev_doesNothing`
   - `loadingConfig_home_doesNothing`(ホームを起動せず、保存番号・currentIndex を変えない)
   - `loadingConfig_openForBoot_doesNothing`
   - `loadingConfig_openForLink_returnsFalseWithoutCallback`
   - `loadingConfig_doesNotReplacePendingRequest`
   - `loadingConfig_construction_preservesSavedIndex`
   - `loadedConfig_rejectedOperationsAreNotReplayed`

   共通の検証:
   - 保存番号4、設定未読込から始める。
   - openは1と0、openForBootも1と0をそれぞれ独立した環境で検証する。
   - 分割・直接アプリ・ホーム・幕・fallbackの呼出しは0回。
   - 保存番号とcurrentIndexは4のまま。
   - 20000ms進めても副作用なし。
   - 読込後に拒否済み操作を再生しない。
   - 読込後に明示的にopenを呼ぶと、既定設定ではなく供給した設定を開く。
   - pending検証は、読込済みで操作と保留を作ってから供給をnullにし、
     新しい要求が既存保留を上書きしないことを確認する。
     設定を戻して現在の操作を完了すると元の保留だけが実行される。

5. D4 の原本保護テストを ConfigRepositoryTest に追加する。

   - `backupFailure_preservesOriginalAndDoesNotEmitDefaults`
     - 壊れたconfig.jsonを作る。
     - config.bak.jsonは空でないディレクトリにし、コピーを確実に失敗させる。
       OSの書込み権限変更は使わない。
     - config.first()が失敗し、既定設定を返さない。
     - 原本のバイト列とバックアップ先ディレクトリ内の内容が不変。
     - 修正前から通るなら、原本保護の製品コードは変更しない。
   - `nextRepositoryAfterBackupFailure_readsAgain`
     - 前項の失敗後、テスト内で障害となるディレクトリを除去する。
     - 別のRepositoryを生成して読み直す。
     - 原本がconfig.bak.jsonへ退避され、既定設定が読める。
     - 次回の読直しの検証として残す。通るものを故意に赤くしない。

6. D4 の購読テストを AppGraphConfigTest に追加する。
   runTestを使い、実際のcollectConfigを呼ぶ。
   テスト前後にresetConfigStateForTestを呼ぶ。
   常駐latestConfig購読を開始しない。
   DiagLogは既存のdump/clearForTestで検証し、他の状態を復元する。

   collectConfig の切出しだけを先に行い、
   catch追加前の例外伝播をassert失敗として確認する。
   最終的なテストでは例外を握りつぶして成功扱いにしない。

   - `initialReadIoFailure_setsConfigErrorWithoutEscaping`
     sourceのFlowがIOException("read failed")を投げる。
     collectConfigが正常に戻り、latest設定の内部StateFlowはnull、
     configError=true、Configの失敗ログ1件。
   - `sourceCreationIoFailure_setsConfigErrorWithoutEscaping`
     source関数自体がIOExceptionを投げても同じ結果。
   - `backupFailure_setsConfigErrorAndPreservesOriginal`
     前項と同じ実ファイルによる退避失敗を、
     `collectConfig { repo.config }` に通す。
     例外が外へ出ず、設定null・configError=true・ログ1件・原本不変。
   - `successfulLoad_publishesConfigAndGesture`
     正常な設定をemitし、設定とgestureの両方が反映され、
     configError=false。
   - `cancellation_isRethrownWithoutConfigError`
     CancellationExceptionが同一例外として再送出され、
     configError=false、失敗ログなし。
   - `readFailure_doesNotRetry`
     source評価回数と収集回数を数え、失敗後も各1回。
   - `ioFailureAfterSuccess_keepsLastGoodConfig`
     設定emit後にIOException。最後の設定を保持しconfigError=true。

   内部StateFlowの読取りには、常駐購読を開始しない
   internal val configSnapshotForTest: LauncherConfig?
   get() = _latestConfig.value
   をAppGraphに設けてよい。リフレクションは使わない。

7. 再現した指摘だけを仕様どおりに修正する。
   既存テストの期待値は変更しない。

8. 指定のtestDebugUnitTestコマンドで全対象クラスが通るまで直す。
   修正後の再実行は最大3回。
   通らなければ、失敗テスト名とエラー先頭30行を報告して停止する。

9. 指定のassembleDebugを実行する。
   失敗したらエラー先頭30行を報告して停止する。

## この Task でしないこと

- HomeActivity.kt・PanelActivity.kt・ui/・link/・edge/・curtain/・foreground/・split/の変更。
- configErrorを使った画面表示・通知・手動再試行。
- パネル・リンク・帯の拒否時動作の変更。
- 設定未読込時の操作の予約や読込後の自動再生。
- 起動時の入口消失・呼出し例外を数える既存035の規則の変更。
- 中断前に確定した直接起動成功・前面化成功の記録の取消し。
- メーカー側で既に始まった処理そのものの取消し。
  遅いメーカー処理による実画面の再前面化は、このテストで保証しない。
- A5の過去イベントと現在の分割状態の区別、A7のisResizeable本実装。
  035で対象外とされた端末確認・方針決定を引き継がない。
- 確度「要実機確認」のA6。
  Resume通知が省略される条件や、パネル通知だけでの成立判断は変更しない。
- GT8Dでの実画面・描画完了・タッチ・メーカー処理の遅延の保証。
- 033〜036で修正済みの項目の再設計。
- Manifest・ビルド設定・依存関係・共有テスト設定・既存指示書・レビュー文書の変更。
- 担当外ファイルが必要になった場合の変更。
  変更せず、必要なファイルと理由を報告する。
- 全テスト、実機コマンド、gitのコミット・リセット・ブランチ・worktree操作。

## 報告

日本語で次を報告する。

- 指摘別のテスト名、修正前の失敗assert・期待値・実測値、修正後の結果。
- 修正前から通った指摘と、そのため製品コードを変更しなかった範囲。
- D4の原本保護と購読エラー処理を分けた結果。
- 開始10000msのstartHome呼出しを確認したことと、実画面は未確認であること。
- 実行した完全なコマンド、テスト実行件数・失敗件数、assembleDebugの結果。
- 作成・変更したファイル。
- 担当外に残る変更先・理由、実行していない確認。
- **決めたこと**。なければ「決めたこと: なし」。
