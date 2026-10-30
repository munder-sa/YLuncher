# Task 056: 本体を中継アプリ(com.gt8d.launcher.panel)に合わせる

最初に `tasks/RULES.md` を読み、従うこと。仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` **§5.6(2026-10-31 追加)**・§5.1・§5.2・§4.7 に従う。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`、`H = panelhost/src/main`

## 背景(司令塔が実機で確かめた事実)
- 純正ランチャーの分割(E)は、右のパッケージの**既存のタスクのどれか**を選ぶ。本体 `com.gt8d.launcher` を右に渡すと、ホームのタスクが選ばれて右にホームが入ったり、YLuncher が既定のホームのとき SystemUI が異常終了したりした。
- Task 055 で中継アプリ `com.gt8d.launcher.panel`(`PanelHostActivity`。自分のタスクに本体の PanelActivity を開く)を作り、E の右に中継のパッケージを渡すと、ホームのタスクが残っていても、既定のホームが YLuncher でも、右に「中継のタスク(上に本体の PanelActivity)」が入り、`panelResumedInSplit` が届くことを確かめた。

## 作成・変更するファイル(これで全部)
新規:
- `M/apps/OwnPackages.kt`
- `T/apps/OwnPackagesTest.kt`
変更:
- `M/sets/SetRunner.kt`、`M/AppGraph.kt`(依存の受け渡しと `panelAlive` の追加だけ)、`M/foreground/ForegroundWatcher.kt`(暖かい判定だけ)
- `M/link/LinkRouter.kt`、`M/link/LinkEntryActivity.kt`
- `M/ui/apps/AppList.kt`、`M/settings/ListsScreen.kt`、`M/settings/SetsScreen.kt`(アプリの選択一覧から中継を除くだけ)
- `M/ui/panel/PanelActivity.kt`(`panelAlive` の更新だけ)
- `app/src/main/AndroidManifest.xml`、`H/AndroidManifest.xml`
- テスト: `T/sets/SetRunnerTest.kt`、`T/link/LinkRouterTest.kt`、`T/link/LinkEntryActivityTest.kt`、`T/ui/TaskAffinityTest.kt`、`T/apps/AppCatalogTest.kt`(または AppList のテストがあるならそれ)、`T/foreground/ForegroundWatcherTest.kt`
  - 既存のテストのうち、**パネルのセットで E に渡す右のパッケージを本体(`ownPkg` / `"com.gt8d.launcher"`)と期待している箇所**は、中継(`"com.gt8d.launcher.panel"`)を期待するように変えてよい(仕様の変更。司令塔が許可)。`lastOpenedPair` の右も同じ。
  - `TaskAffinityTest.launcherIntent_resolvesToPanel` は、本体のランチャーの入口をなくすため、`main_hasNoLauncherEntry`(本体の `getLaunchIntentForPackage` が null)に置き換えてよい(司令塔が許可)。
  - それ以外の既存のテストの期待値は変えない。変えた行は報告に全部並べる。

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.sets.* --tests com.gt8d.launcher.link.* --tests com.gt8d.launcher.apps.* --tests com.gt8d.launcher.foreground.* --tests com.gt8d.launcher.ui.TaskAffinityTest --tests com.gt8d.launcher.ui.panel.PanelActivityTest --tests com.gt8d.launcher.settings.*`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様
1. **OwnPackages.kt**(パッケージ `com.gt8d.launcher.apps`):
   ```kotlin
   object OwnPackages {
       const val MAIN = "com.gt8d.launcher"
       const val PANEL_HOST = "com.gt8d.launcher.panel"
       fun isOwn(pkg: String?): Boolean = pkg == MAIN || pkg == PANEL_HOST
   }
   ```
2. **SetRunner**:
   - セットの右が `Slot.Panel` のとき、E(と D)に渡す右のパッケージは `PANEL_HOST`(依存に `panelHostPkg: String` を足し、製品では `OwnPackages.PANEL_HOST`)。`Opening`・`lastOpenedPair`・呼び直しに渡す右も同じ値にする。
   - `Slot.Panel` で中継が入っていない(`PANEL_HOST !in d.installed()`)ときは、**E を呼ばず**、今の「右がアンインストールされている」ときと同じ扱い(左だけ全画面で開く。失敗として数えない)にする。本体のパッケージを E に渡す経路は作らない。DiagLog `"panel host missing"`。
   - 「自分のアプリか」を見ている所(ホームへの退避を待つ `waitSingle(d.ownPkg…)` など)は今の `ownPkg`(本体)のまま。
   - パネルの合図の判定(左の前面化+`panelResumedInSplit`)は今のまま。
3. **暖かい/冷えたの見分け**(ForegroundWatcher の WarmTracker): `PANEL_HOST` は「本体の PanelActivity の画面が生きている(`AppGraph.panelAlive`)」なら暖かい。`AppGraph.panelAlive` は `@Volatile var`、`PanelActivity.onCreate` で true、`onDestroy` で false(画面の作り直しのときは `isChangingConfigurations` を見て false にしない)。`ownPkg`(本体)の今の判定は変えない。
4. **LinkRouter.detectFront**: 「自分のアプリ」の判定を `OwnPackages.isOwn` にする(`lastPair` の左右、送り元)。ほかの判定の順と規則は変えない。
5. **LinkEntryActivity**:
   - 「別のアプリの前面化があったか」の判定で、自分のアプリの除外を `OwnPackages.isOwn` にする。
   - `LinkRoute.PanelTaskBrowser`(前面が「アプリ | パネル」)のときは、**本体の PanelActivity を起動しない**。`AppGraph.pendingUrl.set(url)` だけ行って `finish()` する(リンクの受け口が閉じると、下の PanelActivity が再開し、`consumePendingUrl` でブラウザを出す)。今の `NEW_TASK | CLEAR_TOP | SINGLE_TOP` での起動は消す(本体の新しいタスクで全画面のパネルができてしまうため)。
6. **アプリの一覧**: YLuncher のアプリ一覧(`buildAppList`)と、設定のアプリの選択一覧(`ListsScreen`・`SetsScreen`)から、中継(`PANEL_HOST`)も除く。インストールの確認(`installed()`)からは除かない。
7. **マニフェスト**:
   - 本体: `<permission android:name="com.gt8d.launcher.permission.OPEN_PANEL" android:protectionLevel="signature" />` を定義。PanelActivity は `exported="true"` のまま `android:permission="com.gt8d.launcher.permission.OPEN_PANEL"` を付け、**MAIN/LAUNCHER の intent-filter を消す**。`<queries>` に `<package android:name="com.gt8d.launcher.panel" />` を足す。
   - 中継(`H/AndroidManifest.xml`): `<uses-permission android:name="com.gt8d.launcher.permission.OPEN_PANEL" />` を足し、`<queries>` に `<package android:name="com.gt8d.launcher" />` を足す。ほかは変えない。

## テスト(先に書き、修正前に失敗することを確かめる)
- `OwnPackagesTest`: `isOwn` の4通り(本体・中継・他・null)。
- `SetRunnerTest`: `panelSet_passesPanelHostToVendor`(E の呼び出しの右が中継)、`panelSet_hostMissing_opensLeftOnly_withoutVendor`(E は呼ばれず、左だけ全画面、失敗の数は 0)、`panelSet_lastOpenedPair_rightIsHost`。
- `LinkRouterTest`: `lastPair_withHost_isNotAppApp`(右が中継のペアは「アプリ | アプリ」と判定しない)、`referrer_host_isIgnored`。
- `LinkEntryActivityTest`: `appPanelFront_setsPendingUrl_withoutStartingActivity`(前面が「アプリ | パネル」のとき、次に起動された Activity が無く、`pendingUrl` に URL がある)。
- `ForegroundWatcherTest`(または WarmTracker のテスト): `panelHost_warm_followsPanelAlive`。
- アプリ一覧のテスト: `buildAppList_excludesPanelHost`。
- `TaskAffinityTest`: 上で許した置き換えと、`panelActivity_requiresOpenPanelPermission`(PanelActivity の `permission` が `com.gt8d.launcher.permission.OPEN_PANEL`)。

## 手順(TDD)
1. 上のテストを書く。2. 対象テストを実行し、修正前に失敗することを確かめる(どのテストがどう失敗したかを報告に書く)。
3. 実装する。4. 対象テストが全部通るまで直す(最大3回)。通ったら `assembleDebug`(本体と中継の両方が作られる)。
- Robolectric で Activity の中の Compose の状態を書き換えて確かめるテストでは、確かめる前に `Snapshot.sendApplyNotifications()` を呼ぶ。

## この Task でしないこと
- 中継の `PanelHostActivity` のコードの変更。パネル・ホームの配置の変更。全テストの実行、実機の操作、git のコミット等。

## 報告
実行したコマンドと結果(件数・失敗件数、修正前の失敗の内容、assembleDebug)、作成・変更したファイル、変えた既存テストの行、**決めたこと**。
