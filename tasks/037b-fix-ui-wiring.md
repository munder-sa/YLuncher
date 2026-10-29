# Task 037b: 結合修正: ホームとパネルの接続

最初に `tasks/RULES.md` を読み、従うこと。

レビュー `docs/reviews/2026-10-29-codex-review.md` の C5 のホーム側・C6、および033〜036の結合後に残った一覧起動の記録漏れ・HomeActivityの起動判定テスト不足を対象とする。

仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §4.3・§4.4・§4.5・§4.6・§4.8 に従う。
tasks/033-fix-safety.md、tasks/034-fix-boot.md、tasks/035-fix-split.md、tasks/036-fix-ui.mdと、それらの現在の実装を先に読む。

033〜036をマージ済みのmainを基準とする。司令塔が用意した037b用worktreeで実行する。
037aとは別worktreeで同時実行する。担当外ファイルには触れない。
gitのコミット・リセット・ブランチ・worktree操作はしない。

パス接頭辞:
`M = app/src/main/java/com/gt8d/launcher`
`T = app/src/test/java/com/gt8d/launcher`

## 作成・変更するファイル(これで全部。これ以外のファイルは作らない・変えない)

新規作成:
- `T/ui/home/HomeActivityUiTest.kt`
- `T/ui/panel/PanelActivityTest.kt`

変更:
- `M/ui/home/HomeActivity.kt`
- `M/ui/panel/PanelActivity.kt`
- `M/ui/panel/PanelScreen.kt`
- `T/ui/home/HomeActivityTest.kt`
- `T/ui/panel/PanelScreenTest.kt`

既存テストには追加と準備処理の共通化だけを行う。
既存テストの名前・期待値・検証内容は変更しない。

## 使ってよいコマンド

読み取り・調査のコマンドはRULESの範囲で使用してよい。
ビルド・テストは次の完全な2行だけ。全テスト `test` は実行しない。

- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.ui.home.HomeActivityTest --tests com.gt8d.launcher.ui.home.HomeActivityUiTest --tests com.gt8d.launcher.ui.home.BootScreenTest --tests com.gt8d.launcher.ui.panel.PanelActivityTest --tests com.gt8d.launcher.ui.panel.PanelScreenTest --tests com.gt8d.launcher.ui.panel.PanelEnvironmentTest`
- `/home/munde/tools/gradle-run.sh assembleDebug`

Gradleはバックグラウンドに回さず、終了まで待つ。
テストのクラス指定を省略したり、独自のGradle引数を追加したりしない。

## 仕様

### 1. 指摘ごとに、修正前の再現を確認する

下記で指定したテストを先に追加し、修正前の製品動作で実行する。
失敗したアサーション・期待値・実測値を記録してから、その指摘を修正する。

- テストが修正前から通った場合、準備と検証条件が指摘に一致するか読み直す。
- 一致しても通る場合、その再現条件では指摘が誤り、または修正済みとして扱い、製品動作は変更しない。テストを残し、報告して次の項目へ進む。
- C6の操作テスト不足と起動判定テスト不足については、追加テストが通れば「製品不具合は再現せず、検証を追加した」と報告する。過去のテスト不足まで誤りだったとは報告しない。
- コンパイルエラー、存在しない新API、無条件失敗を、不具合の再現に数えない。
- 観測・注入の入口が必要な場合は、既存動作を維持する入口だけを先に追加する。その状態でコンパイル可能なテストを実行し、振る舞いの失敗を確認する。
- 成功させるためにテスト対象のコールバックをテストから直接呼ばない。操作テストでは実際の画面のボタン・一覧項目を押す。
- 実時間sleep、@Ignore、例外の握りつぶし、タイミング依存の反復試行は禁止。

本Taskには、RULESの「テストを弱めない」の例外として司令塔が認めた既存期待値変更はない。
既存期待値と両立しない変更が必要になったら、変更せずRULESの3点セットで報告する。

### 2. C5のホーム側: 共通部品からインストール集合を読む

HomeActivityの通常画面のComposition内で、次を行う。

1. `val environment = rememberPanelEnvironment(AppGraph.catalog)` を呼ぶ。
2. `remember(config) { AppGraph.catalog.installed() }` を削除する。
3. `homeApps` は毎回
   `config.homeApps.map { AppGraph.catalog.info(it, environment.installedPkgs) }`
   で構築する。

共通部品のLifecycleOwnerはHomeActivityのものをそのまま使う。
HomeActivity独自のReceiver、LifecycleObserver、周期ポーリングは追加しない。

パッケージ追加・削除・変更、およびON_RESUMEによる再読込とアイコン失効は、036の共通部品に任せる。
設定Flowやメディア状態を変更しなくても、ホーム左側の表示とタップ可否が更新されること。

安全退避中のonCreateの早期returnより後に置く。
退避画面で共通部品・catalogを評価しない。

HomeActivityと右側PanelScreenで共通部品の購読が重複する現状は許容する。
共有化のためにPanelEnvironment.ktを変更しない。

### 3. 一覧起動をActivity側のコールバックへ接続する

PanelScreenの引数を次の順序にする。
既存の末尾ラムダがonLaunchAppのままになる順序を守る。

```kotlin
@Composable
fun PanelScreen(
    inSplit: Boolean,
    onLaunchComponent: ((ComponentName) -> Unit)? = null,
    onLaunchApp: (String) -> Unit,
)
```

通常の一覧項目を押したとき:

1. `val component = ComponentName(entry.pkg, entry.cls)`。
2. onLaunchComponentが非nullなら、そのコールバックを1回呼ぶ。
3. nullなら従来どおり
   `AndroidActivityLauncher(context).startComponent(component, adjacent = inSplit)`
   を1回呼ぶ。これは既存呼び出し・既存テストの互換経路とする。
4. `showAppList = false`。

既存のonLaunchAppを代わりに呼ばない。
代表Activityを再取得しない。
失敗時に別Activityやパッケージ起動へフォールバックしない。

STOCK_LAUNCHER_ENTRYは従来どおりstockLauncherIntentで開く。
onLaunchApp・onLaunchComponentへ渡さず、暖かい記録を追加しない。
一覧を閉じる動作と分割用フラグを付けない動作は維持する。

PanelActivityはPanelScreenへonLaunchComponentを明示的に渡す。
呼び出し順は次のとおり。

1. `activityLauncher.startComponent(component, adjacent = isInMultiWindowMode)`を1回呼び、Booleanを保存する。
2. `AppGraph.warm.recordAppLaunch(component.packageName, そのBoolean)`を1回呼ぶ。

成功・失敗の両方でrecordAppLaunchを呼び、成功判定はそのAPIに任せる。
開始前に記録しない。成功を推測してtrueを渡さない。

既存のonLaunchAppも、startAppの戻り値をrecordAppLaunchへ渡す現在の動作を維持する。

HomeActivity内のPanelScreenにもonLaunchComponentを明示的に渡し、
startComponentのadjacentはfalse、戻り値を同じrecordAppLaunchへ渡す。
同じPanelScreenのonLaunchAppも、startAppの戻り値をrecordAppLaunchへ渡す。
これは仕様§4.3の「パネルからの起動」に合わせる。
ホーム左側IconGridの起動処理には、この記録変更を広げない。

### 4. C6: Activityの画面操作から記録まで検証する

`PanelActivityTest`はRobolectric、sdk=35のCompose操作テストにする。
実際のPanelActivityを生成し、その画面の対象を押す。
PanelScreen単体の通知確認だけでC6完了としない。

起動結果を確実に制御するため、PanelActivityにインスタンス単位の次の入口を設けてよい。

```kotlin
internal var launchApp: (String, Boolean) -> Boolean =
    { pkg, adjacent -> activityLauncher.startApp(pkg, adjacent) }

internal var launchComponent: (ComponentName, Boolean) -> Boolean =
    { component, adjacent -> activityLauncher.startComponent(component, adjacent) }
```

onLaunchApp内の起動だけをlaunchApp経由にする。
onLaunchComponent内の起動だけをlaunchComponent経由にする。
recordAppLaunchは差し替えず、実際のAppGraph.warmを使う。

テストはcreateEmptyComposeRuleとRobolectricのActivityControllerを使う。
ControllerからActivityを取得し、起動ラムダを設定してからcreate/start/resume/visibleする。
終了時はCompositionを破棄し、Activityをpause/stop/destroyする。

修正前の一覧経路を検証する段階では、追加したonLaunchComponent引数はまだ呼ばず、従来の直接起動を残す。
この状態で一覧成功時の暖かい記録が欠けることを再現してから接続を修正する。

各テストで使用状況アクセスを拒否状態に設定し、available=falseを先にassertする。
テストごとに異なる外部パッケージを使い、操作前にisWarm(pkg)=falseをassertする。
事前のmarkForeground・recordAppLaunchは禁止。

テスト中の設定は実際の設定購読に反映させ、画面への反映を待つ。
設定、権限、登録したActivity、ActivityControllerを後始末する。
暖かい記録の後始末にはテスト内のリフレクションで対象pkgだけを集合から除去してよい。
WarmTracker・AppGraphにリセットAPIを追加しない。

次のテストを追加する。

- `panelActivity_panelIcon_success_recordsWarmWithoutUsageAccess`
  - パネルアイコンを押す。launchAppはtrue。
  - 対象pkgとadjacentの引数、呼出回数1回、isWarm(pkg)=trueを検証。
- `panelActivity_panelIcon_failure_keepsColdWithoutUsageAccess`
  - 同じ操作でlaunchAppはfalse。
  - 起動試行1回、isWarm(pkg)=false。
- `panelActivity_appList_success_recordsSelectedPackageWithoutUsageAccess`
  - 同一pkgの入口A・Bを登録し、一覧からBを押す。
  - launchComponentはtrue。
  - 選択componentがB、呼出回数1回、launchAppは0回、isWarm(pkg)=true。
- `panelActivity_appList_failure_keepsColdWithoutUsageAccess`
  - 一覧からBを押し、launchComponentはfalse。
  - Bの試行1回、launchAppは0回、isWarm(pkg)=false、一覧は閉じる。

一覧の成功・失敗は別テストの冷えたpkgで検証する。
成功したpkgをそのまま失敗テストに使わない。
失敗が過去の成功記録を消す仕様には変更しない。

`PanelScreenTest`にも次を追加する。

- `panelScreen_appListSelection_notifiesComponentCallbackOnce`
  - A・Bを表示し、Bを押す。
  - onLaunchComponentの記録がBだけ、onLaunchAppは0回。
  - 直接起動Intentは増えず、一覧が閉じる。
- `panelScreen_stockLauncher_doesNotNotifyComponentCallback`
  - コールバックを渡して純正ランチャーを押す。
  - 両コールバック0回、純正ランチャーIntentが1件。

既存のコールバック未指定テストは残す。
選択component・分割用フラグ・純正ランチャーの既存期待値を変更しない。

### 5. HomeActivityの画面操作テスト

`HomeActivityUiTest`はRobolectric、sdk=35のCompose操作テストにする。
実際のHomeActivityの画面を使う。

設定はconfigFlowForHomeTestへ固定設定を差し込み、
homeAppsにテスト対象pkgを1個だけ、panelApps・dockAppsにはそのpkgを入れない。
idleAppにも別pkgを使い、ホーム左側のノードを一意にする。
起動判定はbootHandled=trueで抑止する。

設定Flow・メディア状態・Activityインスタンスを変えずに検証する。

- `homeActivity_packageAdded_enablesHomeAppWithoutConfigChange`
  - 未導入状態で表示しmissingタグを確認。
  - PackageManagerへ起動可能Activityを追加し、packageスキーム付きACTION_PACKAGE_ADDEDを送る。
  - iconタグ・アプリ名へ変わり、押すと対象pkgの起動Intentが1件。
- `homeActivity_packageRemoved_disablesHomeAppWithoutConfigChange`
  - 導入済みで表示後、PackageManagerから削除しACTION_PACKAGE_REMOVEDを送る。
  - missingタグと「アプリが見つかりません」を確認。
  - 押しても起動Intentが増えない。
- `homeActivity_packageChanged_refreshesHomeAppWithoutConfigChange`
  - 同じpkgの表示ラベルを変更しACTION_PACKAGE_CHANGEDを送る。
  - 新ラベルになる。対象pkgと設定の同一性も確認する。
- `homeActivity_resume_refreshesHomeAppWithoutPackageBroadcast`
  - 未導入表示→pause→PackageManagerに追加、通知を送らずresume。
  - 同じActivityの左側が導入済みになり、押すと起動できる。

HomeActivityが保持するbootHandled、configFlowForHomeTest、
安全装置、設定番号、権限、PackageManager登録は前後で保存・復元する。
Composeホストや共有TestApp、ビルド設定は変更しない。

### 6. HomeActivityの起動判定回数を検証する

BootSequence.startは重複呼出を抑止するため、phaseだけを見て「判定1回」としない。

HomeActivityに次の観測入口を追加してよい。

```kotlin
internal var onBootDecisionForTest: () -> Unit = {}
```

呼ぶ位置はwithResumedの中で、
安全退避判定とbootHandledの再確認を通過した後、
`AppGraph.bootHandled = true`の直前。
判定を実行するたび1回呼ぶ。既定の空ラムダ以外の製品動作を持たせない。

`HomeActivityTest`でカウンターへ差し替える。
既存のconfigFlowForHomeTest・resetBootForTestを使い、
AppGraphやBootSequenceへ観測入口を追加しない。

withResumed待ちを再現するテストでは、
Activityをcreate/startしたSTARTED状態で、
テスト内のリフレクションからawaitConfigAndHandleBootを1回呼ぶ。
設定を読み込み済みにしてmain looperを処理し、
Jobがactive、LifecycleがSTARTED、判定0回であることを先にassertする。
その後、実際のControllerのresume/pauseで状態を進める。
リフレクションは待機開始とJob状態の観測だけに使い、
判定処理そのものを直接呼ばない。

次を追加する。

- `homeActivity_loadedConfig_waitingForResume_rapidPauseResume_decidesOnce`
  - 上記のwithResumed待ちを作る。
  - resume/pause/resume/pause/resumeを、各呼出の間で追加のidleをせず実行。
  - その後main looperを処理し、判定1回、bootHandled=true。
  - さらにlooperを処理しても判定1回。
- `homeActivity_loadedConfig_repeatedResumeWithoutBootCancel_decidesOnce`
  - 設定読み込み済みでresumeし、判定1回。
  - BootSequence.stayHome/openNow、画面の取り消しボタン、resetBootForTestを呼ばずにpause/resumeを3回。
  - 判定1回、Countdownのまま。
  - このテスト中はカウントダウンの時間を進めず、stayHomeは後始末だけで呼ぶ。
- `homeActivity_destroyedWhileLoading_neverDecidesAfterConfigArrives`
  - null設定でAをresume→pause/stop/destroy。
  - 設定を送信してlooperを処理。
  - Aの判定0回、bootHandled=false、BootPhase.Idle。
- `homeActivity_recreatedAfterDestroyWhileLoading_onlyNewActivityDecides`
  - null設定でAをresume→pause/stop/destroy。
  - Bを同じプロセスで生成してresume。ここでbootHandledをリセットしない。
  - 設定を送信してlooperを処理。
  - Aは0回、Bは1回、bootHandled=true。
  - Bのpause/resumeを追加してもAは0回、Bは1回。
- `homeActivity_destroyedWhileWaitingForResume_neverDecides`
  - 設定読み込み済みのwithResumed待ちをSTARTED状態で作る。
  - resumeせずstop/destroy→looper処理。
  - 判定0回、bootHandled=false、BootPhase.Idle。

時間はRobolectricの仮想時計だけで制御し、BOOT_WINDOW_MS未満を先にassertする。
Activity AとBのControllerを別々に保持し、両方を確実に後始末する。

これらが現状で通れば、観測入口とテスト追加だけにとどめ、起動判定の製品動作を変更しない。

Jobの競合がアサーションで再現した場合に限り、次の所有権管理へ変更する。

- Job生成はCoroutineStart.LAZYを使う。
- ローカルの`val job: Job`へ生成したJobを保存する。
- `bootConfigJob = job`としてから`job.start()`する。
- finallyでは`bootConfigJob === coroutineContext[Job]`の場合だけnullにする。
- onPauseでは現在のJobをローカルへ取り出し、bootConfigJobをnullにしてから、そのJobをcancelする。
- 古いJobのfinallyが新しいJobの参照を消さない。
- 設定待ち→withResumed→安全退避再確認→bootHandled再確認→判定の順序は変えない。
- lifecycleScopeのdestroy時キャンセルと、プロセス共通bootHandledによる二重判定防止を維持する。

この変更で説明できない失敗は、推測で別の起動方式へ変更せずRULESの3点セットで報告する。

## 手順(TDD)

1. 033〜036、レビュー、担当実装を読む。許可ファイルとworktreeを確認する。
2. 指摘ごとに指定テストを追加する。
   必要な観測・注入入口は、既存動作を変えない状態で先に追加する。
3. 指定の完全なtestDebugUnitTestコマンドを実行する。
   修正前の失敗アサーション、または修正前から通った結果を記録する。
4. 再現した指摘だけを上記仕様どおり修正する。
   通った項目は製品動作を変えず、次へ進む。
5. 同じtestDebugUnitTestコマンドで全対象クラスを確認する。
   修正後の再実行は最大3回。
   通らなければ失敗テスト名とエラー先頭30行を報告して停止する。
6. 通ったらassembleDebugを実行する。
   失敗したらエラー先頭30行を報告して停止する。
7. git diffで担当外変更・既存テストの期待値変更・削除がないことを確認する。

## この Task でしないこと

- AppGraph.kt、SetRunner.kt、boot/、config/の変更。037aの担当。
- SetRunnerDeps.configの型や未読込時の扱いへの依存を追加すること。
  runnerは既存の公開APIだけを使う。
  037aとの結合に担当外変更が必要なら変更せず、対象ファイルと理由を報告する。
- 設定読込失敗時の復旧・再試行、帯・リンクからの未読込操作の制御。
- BootSequenceの期限・キャンセル・ホーム復帰仕様の変更。
- PanelEnvironment.kt、AppCatalog.kt、ActivityLauncher.kt、WarmTrackerの変更。
- 共通部品の購読重複の最適化。
- ホーム左側IconGridからの起動への暖かい記録の追加。
- PanelForwarder、Service、Receiver、Manifest、BrowserActivity、
  PendingUrlとリンク受け渡しの変更。
  panelInSplit・homeResumedの更新、PendingUrl消費の順序は維持する。
- 成功した起動要求を実際の前面化完了と同一視する仕様変更。
  本Taskは司令塔の方針どおりstartApp/startComponentのBooleanを使う。
- レビューA6の「維持された側のResume通知省略」など、確度「要実機確認」の指摘への対策。
- GT8Dでの隣接起動先、実際の通知配送順序、タスク配置、
  実機の描画・タッチ・前面化の保証。Robolectricの結果と区別する。
- 既存指示書、仕様、レビュー、.allow、共有TestApp、テスト共通設定、
  ビルド設定、依存関係の変更。
- 全テストtest、実機コマンド、gitのコミット・リセット・ブランチ・worktree操作。
- 担当外ファイルを変えなければ成立しない修正。
  必要になっても変更せず、対象ファイルと理由を報告する。

## 報告

日本語で次を記載する。

- 指摘別のテスト名、修正前の結果、修正内容、修正後の結果。
- 修正前から通った項目と、そのため製品動作を変えなかったこと。
- C6と起動判定について、製品不具合の修正か検証追加だけか。
- 実行した完全なコマンド、実行件数・失敗件数、assembleDebugの結果。
- 作成・変更したファイル。
- 操作テストで通した経路と、注入した起動処理の範囲。
- 既存テストの期待値変更がないこと。
- 担当外変更が必要になった点と、実機未確認事項。
- 決めたこと。なければ「決めたこと: なし」。
