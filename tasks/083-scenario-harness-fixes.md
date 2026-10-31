# Task 083: 場面テストの仕組みの不具合を直す(v1.5 の実機の結果から)

最初に `tasks/RULES.md` を読み、従うこと。**adb は実行しない(司令塔が実機で流す)。**

## 背景(司令塔が実機で流した結果: `build/device-scenarios/20261031-175458-474908/summary.md` と各場面のディレクトリ。読んでよい)
v1.5 の製品のコードは期待どおり動いている(左パネルの分割・ドックの左配置・ダブルタップの往復の保存は合格、画面でも確認)。失敗はテストの仕組みの側の原因:
1. **受け口の `open` が設定の読み込みを待たない**: 純正ホームで本体を force-stop した直後、受け口の合図でプロセスが起きると `[DebugPanel] open index=5` の後に `[SetRunner] open` が出ない(`build/device-scenarios/20261031-175458-474908/set_leftPanel_cold/logcat.txt`)。設定がまだ読めておらず runner が無視している。
2. **受け口の `launch` がパネルの画面から起動していない**: アプリの Context から隣に開く起動をすると、分割に入らず全画面になる(`build/device-scenarios/20261031-175458-474908/leftPanel_launchApp@ylhome`)。実機の実験で、PanelActivity から隣に開くと反対側に出ることは確認済み。
3. **位置のログは変わったときだけ出る**: すでに左にいるパネルを開き直すと `[Panel] placement=Left` が出ず、`set_leftPanel_warm@ylhome` が FAIL。
4. **左パネルの判定で「分割の枠が2つない」**: `build/device-scenarios/20261031-175458-474908/set_leftPanel_cold@prerequisite@prerequisite@ylhome`: 画面は正しい分割(左パネル・右 YouTube)、`slots.txt` は `RIGHT …YouTube`・`LEFT …PanelActivity` のほかに `UNKNOWN EMPTY` が2行(dumpsys に同じ階層が2回出る)。この判定が FAIL した理由をスクリプトのコードで特定して直す。
5. **後片付け `restore_panel_apps` が失敗**(`build/device-scenarios/20261031-175458-474908/set_panelCard_switch@ylhome`): セット1を開き直す遠回りの手順で失敗している。
6. 場面が FAIL すると、後ろの場面がそのたびに前提を作り直そうとして `@prerequisite@prerequisite…` が何重にも並ぶ。

## 変更するファイル(これで全部)
- `app/src/debug/java/com/gt8d/launcher/debug/DebugPanelReceiver.kt`
- `app/src/main/java/com/gt8d/launcher/AppGraph.kt`(パネルへの頼みの流れを足すだけ)
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelActivity.kt`(その頼みを受けて、いつもの起動の経路で開くだけ)
- `scripts/device-scenarios.sh`
- `app/src/test/java/com/gt8d/launcher/ui/panel/PanelActivityTest.kt`

使ってよいコマンド:
- `bash -n scripts/device-scenarios.sh`
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.ui.panel.*'`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様
1. 受け口の `open`: `goAsync()` で、`AppGraph.latestConfig` が null でなくなるのを最大 5 秒待ってから、今の判定(開いている最中なら無視)で `runner.open(N)`。待ちきれなければ `[DebugPanel] open failed: config not loaded`。
2. パネルへの頼み: `AppGraph` に `val panelLaunchRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)`(コメント「場面テストの受け口からパネルに起動を頼む」)。`PanelActivity` は STARTED の間これを collect し、受けたパッケージを、ドックのアイコンを押したときと同じ `onLaunchApp` の処理(`launchApp(pkg, isInMultiWindowMode)`・`warm.recordAppLaunch`・分割中なら `runner.panelLaunched`)で開く。受け口の `launch` はこれに `tryEmit` し、PanelActivity 側が開いたら `[Panel] launchRequest <pkg> ok|failed` をログ。受け口の今の直接起動はやめる。
   - テスト `PanelActivityTest.launchRequest_usesPanelLaunchPath`: 分割状態の PanelActivity に `panelLaunchRequests.tryEmit("maps")` → 既存のテストの偽の起動に、隣に開く(`adjacent = true`)で "maps" が渡る。
3. 受け口に `--es status 1` を足す: `[DebugPanel] status placement=<AppGraph.panelPlacement.value> view=<最新の設定の panelView>` をログ。スクリプトの `placement=…` の判定は、場面の操作の後に status を送り、この行で判定する(変化のログに頼らない)。
4. `parse_slots`・判定: `UNKNOWN EMPTY` の行は無視し、同じ行が重複したら1つに。4 の失敗の原因を特定して直し、報告する。
5. `restore_panel_apps`: セット1を開き直さず、`--es view apps` を送って `[DebugPanel] view=Apps saved` を確かめるだけにする。
6. 前提の作り直しは、同じ実行の中で1つの前提につき1回まで。前提が一度 FAIL したら、それに依存する場面は作り直さずに SKIP。
7. 場面 `link_cold_noTasks` は今の判定のまま変えない。

## この Task でしないこと
- adb の実行。製品の動き(起動・分割・リンク・保存)の変更。git のコミット等。

## 報告
変更点、4 の原因、`bash -n`・テスト・`assembleDebug` の結果、**決めたこと**。
