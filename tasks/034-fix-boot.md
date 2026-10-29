# Task 034: 修正: 起動時の手順と設定の読み込み

最初に `tasks/RULES.md` を読み、従うこと。

仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §4.8・§5.3 の読み込みの規則・§7.2 に従う。
対象レビューは `docs/reviews/2026-10-29-codex-review.md`。

Task 17b (`tasks/032-link-browser.md`) がコミットされた状態から始める。
ほかの修正 Task と別の git worktree で同時に実行する。担当外のファイルは変更しない。
git のコミット・リセット・ブランチ操作はしない。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

## 作成・変更するファイル(これで全部。これ以外のファイルは作らない・変えない)

新規作成:
- `T/LauncherAppTest.kt`

変更:
- `M/boot/BootSequence.kt`
- `M/AppGraph.kt`
- `M/ui/home/HomeActivity.kt`
- `T/boot/BootSequenceTest.kt`
- `T/config/ConfigCodecTest.kt`
- `T/config/ConfigRepositoryTest.kt`
- `T/ui/home/HomeActivityTest.kt`

`M/LauncherApp.kt`・`M/config/` の製品コードは、この下書きでは変更しない。
E5・E6 は不足している検証を追加する。既存動作が正しければ製品コードを変更しない。

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.boot.BootSequenceTest --tests com.gt8d.launcher.config.ConfigCodecTest --tests com.gt8d.launcher.config.ConfigRepositoryTest --tests com.gt8d.launcher.ui.home.HomeActivityTest --tests com.gt8d.launcher.LauncherAppTest`
- `/home/munde/tools/gradle-run.sh assembleDebug`
- ファイルを読む・調べるコマンドは `tasks/RULES.md` の範囲で使ってよい。
- Gradle はバックグラウンドに回さず、終了まで待つ。
- 全テスト `test` は実行しない。全テストと Task 035 との結合確認は司令塔が行う。

## 仕様

### 1. 再現を先に行う

指摘ごとに、下記で指定したテストを製品コードの変更前に追加して実行する。
失敗した assert と実測値を記録し、その失敗を直す製品コードだけを変更する。

追加テストが修正前から通る場合は、その指摘に対する製品コードの修正をしない。
報告に「修正前から通ったテスト名・検証した条件・今回の条件では指摘を再現できなかったこと」を書いて次へ進む。
通った回帰テストは残す。

E5・E6 は「テスト不足」の指摘なので、追加テストが通っても過去のテスト不足が誤りだったとは断定しない。
「製品動作の不具合は再現せず、検証を追加した」と報告する。
既存コードを故意に壊して赤くすることは禁止する。

コンパイル失敗を赤の確認に使った場合は、コンパイル可能になった後、製品コードの修正前に振る舞いの assert が失敗することも確認する。
それができなければ、動作の再現を確認できていないと報告する。

### 2. 起動全体の期限(D3/E3)

`BootSequence` の公開コンストラクターと定数値は変えない。

- `COUNTDOWN_MS = 3000L`
- `BOOT_LIMIT_MS = 10000L`
- `POLL_MS = 200L`

期限の起点を、安全退避ではない `start()` が受理された時点にする。
カウントダウンを含む合計10000msで終了させる。

タイマーの保持を次の2種類に分ける。
- `private var limitTimer: Cancellable? = null`
- カウントダウンとポーリングを保持する既存の `activeTimers`

呼び出し順:
1. `Idle` 以外の `start()` は無視する。
2. 安全退避中なら既存どおり記録して `StayedHome` にし、タイマーを作らない。
3. `Countdown`・残り3秒にする。
4. `BOOT_LIMIT_MS` 後に `onTimeout()` を呼ぶ `limitTimer` を登録する。
5. 1000ms・2000ms・3000msのカウントダウンを登録する。

`performOpen()` と `openNow()` は `activeTimers` だけを取り消す。
`limitTimer` を取り消したり、登録し直したりしない。
`performOpen()` にある「開いてから10000ms」のタイマー登録を削除する。

成功・カウントダウン中の取り消し・時間切れの終端処理では、
`activeTimers` と `limitTimer` の両方を取り消し、`limitTimer = null` にする。
終端後のポーリング・再実行・ホーム復帰の重複を起こさない。

### 3. 時間切れのホーム復帰要求(A3/D1、BootSequence の責務)

`onTimeout()` は終端以外からだけ実行する。
次の順に処理する。
1. すべてのタイマーを取り消す。
2. 取り消し済みでなければ `record("boot_timeout")`。
3. `callGoHomeOnce()`。
4. 取り消し済みなら `StayedHome`、それ以外は `Done`。

取り消し済みの場合は、既存の `boot_cancel` 以外の記録を増やさない。
`SplitModeSelector` を持たせず、失敗回数を更新しない。
ホーム復帰コールバックは1回だけ呼ぶ。

この節で保証するのは BootSequence からの復帰要求まで。
AppGraph の既存 `goHome = { runner.openForBoot(SetNavigator.HOME) }` は、この Task では変更しない。
SetRunner 実行中の要求は保留されるため、実際のホーム復帰完了は Task 035 との結合確認が必要。

### 4. HomeActivity の起動判定を設定読込後にする(C2/D2 の起動経路)

設定未読込時に HomeActivity の起動判定を確定しない。

HomeActivity に次を追加する。
- `private var bootConfigJob: Job? = null`
- `private fun awaitConfigAndHandleBoot()`

`awaitConfigAndHandleBoot()`:
- `AppGraph.bootHandled` が true、または `bootConfigJob` が実行中なら何もしない。
- `lifecycleScope.launch` で `AppGraph.latestConfig.filterNotNull().first()` を待つ。
- 待っている間は `bootHandled` を true にしない。`AppGraph.boot`・`AppGraph.runner`・`AppGraph.config.lastSetIndex` に触れない。
- 読込後、Activity が RESUMED でなければ判定しない。
- 安全退避中なら起動判定しない。
- 改めて `bootHandled` を確認し、false のときだけ true にする。
- `SystemClock.elapsedRealtime() < BOOT_WINDOW_MS` かつ保存番号が0以外のときだけ `AppGraph.boot.start()`。
- `finally` で `bootConfigJob = null`。

`onResume` は Task 17b の `AppGraph.homeResumed = true` を維持する。
安全退避判定、`EdgeStripService.ensureRunning(this)` の順序も維持し、その後で `awaitConfigAndHandleBoot()` を呼ぶ。
従来の即時の起動判定を置き換える。

`onPause` では `bootConfigJob?.cancel()` を呼び、`bootConfigJob = null` にする。
Task 17b の `AppGraph.homeResumed = false` と既存の `super.onPause()` を維持する。
再開時は最新の設定から待ち直す。

AppGraph の `runner` に渡す設定関数や、Panel・リンク・帯からの操作経路は変更しない。
この修正で C2/D2 全体が解決したと報告しない。

HomeActivityTest で読込を制御するため、AppGraph に次を追加する。
- `internal var configFlowForHomeTest: StateFlow<LauncherConfig?>? = null`
- `internal val configForHome: StateFlow<LauncherConfig?> get() = configFlowForHomeTest ?: latestConfig`

HomeActivity の設定購読と、この節の待機には `configForHome` を使う。
製品実行時は従来の `latestConfig` を使う。
テストでは MutableStateFlow を差し込み、終了時に必ず null に戻す。
既存の `homeResumed`・`panelInSplit`・`pendingUrl` は維持する。

### 5. v1移行の検証(E5)

製品の ConfigCodec.encode でv1入力を生成しない。
以下の固定JSONを、それぞれのテスト内に文字列として置く。

```json
{"version":1,"screenSets":[{"id":"s1","left":{"pkg":"a"},"right":{"type":"com.gt8d.launcher.config.Slot.Panel"}},{"id":"s2","left":{"pkg":"b"},"right":{"type":"com.gt8d.launcher.config.Slot.App","pkg":"c"}}],"homeApps":["c","a"],"panelApps":["b","c"],"dockApps":["c","b"],"idleApp":"b","link":{"mode":"Chrome","chromeDomains":["example.org","example.net"]},"gesture":{"stripWidthPx":12,"glow":false,"longPressMs":800}}
```

期待値は LauncherConfig 全体で比較する。
- version: 2
- セット: s1 = a｜Panel、s2 = b｜c、入力順を維持
- 両セットの ratio: R5_5
- 両セットの appPairLabel: null
- homeApps: c、a
- panelApps: b、c
- dockApps: c、b
- idleApp: b
- link: Chrome、例外ドメイン example.org、example.net
- gesture: 12、false、800
- boot: BootConfig(waitForCar = false)

Repository テストは installed を `setOf("a", "b", "c")` にする。
v1ファイルを読み、全体比較する。
`update { it }` を完了させ、別の ConfigRepository で同じファイルを再読込する。
再読込でも全体比較し、ディスクの JSON の version が2であること、
`config.bak.json` が存在しないことを検証する。

### 6. LauncherApp の接続の検証(E6)(司令塔が書き直した版)

`T/LauncherAppTest.kt` は Robolectric、sdk 35、LooperMode.PAUSED。
全体の `robolectric.properties` と TestApp は変更しない。
このプロジェクトの Robolectric 4.14.1 には `Robolectric.buildApplication` が無い。代わりに、テストクラスに
`@Config(sdk = [35], application = LauncherApp::class)` を付ける。Robolectric は各テストの前(`@Before` より前)に
`LauncherApp` を作って `onCreate` を1回呼ぶ。テストからは `ApplicationProvider.getApplicationContext<LauncherApp>()` で取る。
`onCreate` をテストから再度呼ばない。

順序の扱い:
- 前任のハンドラー: `companion object` の `@BeforeClass @JvmStatic` で、元の既定ハンドラーを保存し、テスト用の前任ハンドラー
  (受け取った Thread と Throwable を記録するだけのもの)を既定に設定する。`@After` で既定を、このテスト用の前任ハンドラーに戻す
  (次のテストの `LauncherApp` が、前のテストの LauncherApp のハンドラーを包まないように)。記録はテストごとに `@Before` で消す。
  `@AfterClass @JvmStatic` で元の既定ハンドラーに戻す。
- 安全装置の状態: `onCreate` が `@Before` より前に開始時刻を記録するので、`@Before` では消さない。各テストの `@After` で、
  安全装置のファイルと退避の状態を消す(次のテストに引き継がない)。
- main looper の残ったコールバックは `@After` で除去する。

安全装置への接続を確かめるため、安全装置のファイルを JSON として読み、
startElapsed・crashes・fallback を検証してよい。
SafetyGuard の private API を呼ぶためのリフレクションは使わない。
捕捉したハンドラー(`Thread.getDefaultUncaughtExceptionHandler()`)を直接呼び、実際のスレッドをクラッシュさせない。

検証対象:
- onCreate が開始時刻を記録する。
- 開始9999msではクラッシュ履歴を消さず、10000msで消す(main looper の時間を進める)。
- 登録された例外ハンドラーがクラッシュを記録する。
- 同一の Thread と Throwable を前任ハンドラーへ1回渡す。

### 7. 既存テストの期待値変更

次だけを、RULES の「テストを弱めない」の例外として司令塔が認めた変更とする。
根拠は仕様 §4.8 の3・4と §7.2。旧 tasks/029-boot.md の「開くから10秒」より仕様を優先する。

`BootSequenceTest.stayHomeAfterOpen_goesHomeAt10000`:
- start → 3000ms → stayHome の後の待ちを `9999L` から `6999L` に変更する。
- 続く1msでホーム復帰1回・StayedHome・記録 boot_cancel を期待する。
- テスト名は変えない。

`BootSequenceTest.bootTimeout_recordsAndStops_withoutFailureCount`:
- start → 3000ms の後の待ちを `9999L` から `6999L` に変更する。
- 続く1msで boot_timeout・Done を期待する。
- homed の期待値を0から1に変更する。
- テスト名は変えない。

それ以外の既存テストの期待値は変えない。

## 手順(TDD)

1. 製品コードを変更する前に、指摘ごとに次のテストを追加する。

   `BootSequenceTest`:
   - A3/D1: `timeout_withoutCancel_requestsHomeOnce`
     - start → openNow → 10000msで homed 1、boot_timeout 1件、Done。
     - さらに20000ms進めても homed 1、追加記録なし。
   - D3/E3: `countdown_timeoutAt10000FromStart`
     - start → 3000msで opened 1。
     - startから9999msでOpening、10000msでDone・homed 1。
   - D3/E3: `openNow_doesNotResetStartDeadline`
     - start → 500ms → openNow。
     - startから9999msでOpening、10000msでDone。
   - D3/E3: `cancelDuringOpening_usesOriginalDeadline`
     - start → 3000ms → stayHome。
     - startから9999msでhomed 0、10000msでhomed 1・StayedHome。
     - 記録は boot_cancel だけ。

   `HomeActivityTest`:
   - C2/D2: `homeActivity_loadingConfig_doesNotHandleBootOrOverwriteIndex`
     - 差し込む設定Flowをnullにする。
     - last_set_index.txt に4を保存し、bootHandledをfalseにする。
     - Activityをresumeしてmain looperを進める。
     - 設定待ちの間はbootHandledがfalse、保存番号が4のまま。
   - C2/D2: `homeActivity_loadedConfig_handlesBootOnlyOnce`
     - 未読込でresumeした後に、4セットを持つ設定をFlowへ送る。
     - 起動窓内でbootHandledがtrueになり、Countdownへ進む。
     - pause/resumeを繰り返しても起動処理を二重に開始しない。
   - C2/D2: `homeActivity_pausedBeforeConfig_doesNotStartBoot`
     - 未読込でresume → pause → 設定送信。
     - pause中はbootHandledがfalseのまま。
     - resume後に一度だけ判定する。
   - 各テストで差し込んだFlow、bootHandled、安全装置、保存ファイルを片付ける。
     BootSequenceを開始したテストはstayHomeでタイマーを終了する。

   `ConfigCodecTest`:
   - E5: `migrate_v1_nonDefaultSettings_preservesWholeConfig`

   `ConfigRepositoryTest`:
   - E5: `load_v1_nonDefaultSettings_persistsV2WithoutBackup`

   `LauncherAppTest`:
   - E6: `onCreate_recordsStart`
   - E6: `onCreate_marksStableAt10000Not9999`
   - E6: `uncaughtHandler_recordsCrashAndDelegatesSameArguments`

2. 指定した testDebugUnitTest コマンドを実行する。
   指摘ごとに修正前の結果を残す。
   修正前から通るものは「仕様 1」に従い、製品コードを変更せず次へ進む。

3. 再現できた指摘だけを実装する。
   「仕様 7」で認めた既存テストの変更を行う。

4. 同じ testDebugUnitTest コマンドを実行する。
   修正後の再実行は最大3回。通らなければ失敗したテスト名とエラー先頭30行を報告して止まる。

5. 通ったら assembleDebug を実行する。
   失敗したらエラー先頭30行を報告して止まる。

## この Task でしないこと

- SetRunner.kt、SetRunnerTest.kt は変更しない。
- A3/D1 の実際のホーム復帰完了、実行中の起動待ちの終了、
  遅れて来る成功通知・再試行・保留要求の無効化は実装しない。
  現在の openForBoot(HOME) は実行中なら保留されるため、Task 035 の担当。
  BootSequence のコールバック検証だけでホーム復帰全体を確認したとは報告しない。
- C2/D2 の帯・Panel・LinkEntryActivity からの読込前操作は変更しない。
  全入口での拒否・保留と、保留要求の順序は司令塔の判断および別の実装が必要。
- 設定未読込時の runner の既定設定フォールバックは、この Task では変更しない。
  上記の入口が残ったまま requireNotNull 等へ変更して、新たなクラッシュを作らない。
- D4 の読込失敗・バックアップ失敗に対する復旧動作は変更しない。
  退避できない原本の上書き可否と、I/O失敗時の表示・再試行方針が未決定。
  単に例外を握りつぶして既定設定で操作を続行する変更はしない。
- 確度「要実機確認」の A6 は対象外。
  その他、GT8D上の左右表示、メーカー入口の遅延、実際の前面画面、
  安全退避時の端末動作は実機でしか保証できないため変更・確認済み扱いにしない。
- Task 17b のリンク処理、homeResumed・panelInSplit・pendingUrl は削除・変更しない。
- BootScreen.kt、PanelActivity.kt、LinkEntryActivity.kt、Service、Receiver、
  Manifest、ビルド設定、テスト全体のApplication設定は変更しない。
- tasks/029-boot.md を含む過去の指示書は変更しない。
- 担当外の変更が必要と判明したら変更せず、必要なファイル・理由を報告する。

## 報告

日本語で次を報告する。

- 指摘ごとの再現結果、修正前から通ったテスト、修正した範囲。
- A3/D1・C2/D2 が部分対応であることと、残っている接続・入口。
- E5・E6 で製品動作の不具合が再現したか、検証追加だけか。
- 実行した完全なコマンドと結果。
  テストの実行件数・失敗件数、assembleDebug の結果。
- 作成・変更したファイル。
- 実行していない確認。
- 決めたこと。なければ「決めたこと: なし」。
