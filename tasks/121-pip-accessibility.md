最初に tasks/RULES.md を読み、従う。

# Task 121: PiP 中のアプリは、パネルから開くとき全画面で戻す(ユーザー補助で PiP を判定)

## 背景(実機で確認済み、2026-11-02)
- 分割の中のパネルからアプリを開くと、`NEW_TASK | LAUNCH_ADJACENT | MULTIPLE_TASK` で起動する。この端末では LAUNCH_ADJACENT を付けると(MULTIPLE_TASK の有無にかかわらず)必ず新しいタスクができる。
- そのため PiP 中のアプリ(例 Morphe = app.morphe.android.youtube)のアイコンを押すと、別のタスクが分割に出て、PiP は黒いまま残る(表示が崩れる)。
- `NEW_TASK` だけ(隣の指定なし)で起動すると、PiP のタスクがそのまま全画面に戻る(新しいタスクはできない)。
- ユーザーの決定: ユーザー補助(AccessibilityService)の許可を YLuncher にもらい、PiP 中のアプリを見分ける。PiP 中のアプリだけ `NEW_TASK` だけで開いて全画面で戻し(分割は解けてよい)、それ以外は今までどおり隣に開く。許可が無いときは今までどおり。

## 変更
### 1. ユーザー補助のサービス(新規)
- `app/src/main/java/com/gt8d/launcher/pip/PipWatcherService.kt`: `AccessibilityService` を継承。
  - `onServiceConnected` で `companion object` の `@Volatile var instance: PipWatcherService?` に自分を入れ、`onUnbind`・`onDestroy` で自分なら null に戻す。`onAccessibilityEvent`・`onInterrupt` は何もしない。
  - `fun pipPackages(): Set<String>`: `windows`(`getWindows()`)のうち `isInPictureInPictureMode` が true のものについて、`root?.packageName?.toString()` を集める。例外は握って空集合を返す(DiagLog に 1 行)。
- `app/src/main/java/com/gt8d/launcher/pip/PipDetector.kt`:
  - `fun interface PipDetector { fun isInPip(pkg: String): Boolean }`
  - `object AndroidPipDetector : PipDetector` — `PipWatcherService.instance?.pipPackages()?.contains(pkg) == true`。
  - `fun pipWatcherEnabled(context: Context): Boolean` — `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` を `:` で分け、`ComponentName.unflattenFromString` で読み、`ComponentName(context, PipWatcherService::class.java)` と一致するものがあれば true。
- `app/src/main/res/xml/pip_watcher.xml`(新規): `accessibility-service` に `android:accessibilityEventTypes="typeWindowsChanged"`、`android:accessibilityFeedbackType="feedbackGeneric"`、`android:accessibilityFlags="flagRetrieveInteractiveWindows"`、`android:canRetrieveWindowContent="true"`、`android:notificationTimeout="100"`、`android:description="@string/pip_watcher_description"`。
- `app/src/main/res/values/strings.xml`(新規): `pip_watcher_description` = 「PiP(小窓)で再生中のアプリを見分けます。パネルからそのアプリを開くとき、小窓を全画面に戻すために使います。画面の内容は読み取りません。」
- `app/src/main/AndroidManifest.xml`: `<service android:name=".pip.PipWatcherService" android:exported="true" android:label="YLuncher(PiP の判定)" android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">` に intent-filter `android.accessibilityservice.AccessibilityService` と meta-data `android.accessibilityservice` → `@xml/pip_watcher`。

### 2. 起動の分岐
- `app/src/main/java/com/gt8d/launcher/split/ActivityLauncher.kt`: `AndroidActivityLauncher(context, pipDetector: PipDetector = AndroidPipDetector)`。
  - `startApp(pkg, adjacent)`・`startComponent(component, adjacent)`・`startRedirect(redirect, adjacent)` で、`adjacent` が true かつ `pipDetector.isInPip(対象のパッケージ)` なら、隣の指定(LAUNCH_ADJACENT・MULTIPLE_TASK)を付けずに起動する(startApp・startComponent は `NEW_TASK` だけ、startRedirect は `NEW_TASK | SINGLE_TOP` だけ)。対象のパッケージは startApp が pkg、startComponent が component.packageName、startRedirect が redirect.targetPkg。そのとき DiagLog `"Launch", "pip pkg=$pkg -> fullscreen"` を出す。
  - PiP でないとき・adjacent=false のときのフラグは今と同じ(MULTIPLE_TASK も今のまま残す)。
  - 呼び出し側(PanelActivity・PanelScreen・HomeActivity・AppGraph)は変えない(既定の引数で効く)。

### 3. 許可のチェック
- `app/src/main/java/com/gt8d/launcher/settings/Permissions.kt`:
  - `PermissionItem.Accessibility` のラベルを「ユーザー補助(PiP の判定)」にする。
  - `permissionItems(mode)` は、どの方式でも Accessibility を含める(並びは今の enum の順)。
  - `AndroidPermissionChecker` の Accessibility は `pipWatcherEnabled(context)`。
  - 開く先は今のまま `Settings.ACTION_ACCESSIBILITY_SETTINGS`。
- `PermissionsScreen.kt` は、Accessibility が未許可でも今の「使用状況へのアクセス…」のような注意書きは出さない(任意の許可のため)。変更が要らなければ触らない。

### 4. テスト(仕様の変更に合わせる。既存テストの期待値の変更は下に挙げたものだけ)
- `app/src/test/java/com/gt8d/launcher/settings/PermissionsTest.kt`: Vendor・LeftOnly・AppPair のどれでも Accessibility を含む、に改める。
- `app/src/test/java/com/gt8d/launcher/settings/PermissionsScreenTest.kt`: `splitStatus_showsMode_andRestoreButton` の `perm_row_Accessibility` の `assertDoesNotExist()` を `assertExists()` に改める(LeftOnly でも出る)。ほかは変えない。
- `app/src/test/java/com/gt8d/launcher/split/AndroidActivityLauncherTest.kt` に追加:
  - `startApp_adjacent_pipTarget_launchesFullscreenWithoutAdjacent`、`startComponent_adjacent_pipTarget_launchesFullscreenWithoutAdjacent`、`startRedirect_adjacent_pipTarget_launchesWithoutAdjacent`: 偽の PipDetector(対象だけ true)で、LAUNCH_ADJACENT・MULTIPLE_TASK が無く NEW_TASK がある。
  - `startApp_adjacent_otherAppInPip_keepsAdjacentFlags`: PiP なのが別のパッケージなら今のフラグのまま。
  - `startApp_notAdjacent_pipTarget_unchanged`: adjacent=false は今と同じ。
- `app/src/test/java/com/gt8d/launcher/pip/PipDetectorTest.kt`(新規): `pipWatcherEnabled` が、Settings.Secure の値に自分のサービスがあるとき true、無い・空・null・別のサービスだけのとき false(Robolectric で Settings.Secure に書く)。`AndroidPipDetector` は instance が null のとき false。
- PanelScreenTest など、今のフラグを確かめる既存のテストは、PiP でない場合なので変えずに通るはず。通らなければ止まって報告する。

## 作るファイル・変えるファイル(これで全部)
- create app/src/main/java/com/gt8d/launcher/pip/PipWatcherService.kt
- create app/src/main/java/com/gt8d/launcher/pip/PipDetector.kt
- create app/src/main/res/xml/pip_watcher.xml
- create app/src/main/res/values/strings.xml
- create app/src/test/java/com/gt8d/launcher/pip/PipDetectorTest.kt
- write app/src/main/AndroidManifest.xml
- write app/src/main/java/com/gt8d/launcher/split/ActivityLauncher.kt
- write app/src/main/java/com/gt8d/launcher/settings/Permissions.kt
- write app/src/main/java/com/gt8d/launcher/settings/PermissionsScreen.kt(必要なときだけ)
- write app/src/test/java/com/gt8d/launcher/settings/PermissionsTest.kt
- write app/src/test/java/com/gt8d/launcher/settings/PermissionsScreenTest.kt
- write app/src/test/java/com/gt8d/launcher/split/AndroidActivityLauncherTest.kt

## 使ってよいコマンド
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.split.*' --tests 'com.gt8d.launcher.settings.*' --tests 'com.gt8d.launcher.pip.*' --tests 'com.gt8d.launcher.ui.panel.*'`

## 報告
- 変えた箇所、決めたこと、実行したコマンドと結果(件数・失敗数)。

## 追記1: 全画面で戻した後、直前の分割を組み直す(ユーザーの追加の要望、実機で成立を確認済み)
- 実機の確認: PiP の Morphe を `NEW_TASK` だけで全画面に戻し、約 1.5 秒後に Vendor の分割(パネル | Morphe)を開くと、0.6 秒で ready になり、元の PiP のタスク(#2411)がそのまま分割に入った(タスクは増えない)。PiP のままで Vendor の分割を開くと失敗する(Safety)。
- `SetRunner` に `fun reopenWithApp(pkg: String, panelSide: Side): Boolean` を足す。`openForLink` にならい、
  - `stopCancelGuard()`・`cancelLinkCallbacks()`、設定が無い・`_opening.value != null` なら false。
  - 比率: 今のセット(`currentIndex` のセット)が `panelSide` 側にパネルを持つならその比率。そうでなければ、パネルが右なら `R6_4`、左なら `R4_6`。
  - セット: `ScreenSet("pip", …)`。パネルを `panelSide` に、`Slot.App(pkg)` を反対側に置く。幕の名前は「PiP から戻す」、`SlotPreview` は openForLink と同じ作り(パネルの側はパネルの SlotPreview を使う。既存の `targetFor` の作り方にならう)。`useCurtain = true`。`currentIndex` は変えない(openForLink と同じ)。
  - DiagLog `"SetRunner", "reopenWithApp pkg=$pkg panelSide=$panelSide ratio=$ratio"`。
- `PanelActivity` の `onLaunchApp(pkg)` と `onLaunchComponent` の経路(リダイレクトの経路は除く)で、`isInMultiWindowMode` かつ `pipDetector.isInPip(対象のパッケージ)` のとき:
  1. 隣の指定なし(`launchApp(pkg, false)` / `launchComponent(component, false)`)で開く(PiP が全画面に戻る)。
  2. パネルの位置 `AppGraph.panelPlacement.value` が Left か Right なら、`PIP_RESPLIT_DELAY_MS = 1200L` 後に `AppGraph.runner.reopenWithApp(対象, その側)` を呼ぶ(Handler(Looper.getMainLooper()).postDelayed で可。Activity が終わっていても runner の呼び出しは行ってよい)。Full・null なら組み直さない。
  3. この経路では `runner.panelLaunched` は呼ばない(組み直しで lastOpenedPair が更新されるため)。`AppGraph.warm.recordAppLaunch` は今と同じく呼ぶ。
  4. DiagLog `"Panel", "pip relaunch pkg=… resplit=${side ?: "none"}"`。
- `PanelActivity` の PiP 判定は差し替えられるように `internal var pipDetector: PipDetector = AndroidPipDetector` とする(テスト用)。`AndroidActivityLauncher` 側の PiP の分岐(変更 2)は、PanelScreen が直接 `AndroidActivityLauncher` を使う経路の安全策として残す。
- テストを足す:
  - SetRunnerTest `reopenWithApp_buildsPanelAndAppPair_usesCurrentSetRatioOrDefault`: パネル右・左それぞれで Vendor に渡る左右と比率。今のセットが同じ側にパネルを持つときはその比率、そうでなければ既定。
  - SetRunnerTest `reopenWithApp_returnsFalseWhileOpening_andKeepsCurrentIndex`。
  - PanelActivity のテスト(既存の PanelActivity のテストファイルにならう。無ければ新規 `app/src/test/java/com/gt8d/launcher/ui/panel/PanelActivityPipTest.kt`)`pipTarget_inSplit_launchesFullscreen_thenReopensAfterDelay`: 偽の pipDetector・launchApp で、adjacent=false で開き、1200ms 後に reopenWithApp(Robolectric の main looper を進める)。PiP でないときは今のまま adjacent=true で、reopenWithApp は呼ばれない。runner を差し替えにくい場合は、呼び出しを `internal var reopen: (String, Side) -> Boolean` に分けて差し替える。
- 追加で変えてよいファイル:
  - write app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt
  - write app/src/main/java/com/gt8d/launcher/ui/panel/PanelActivity.kt
  - write app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt
  - create app/src/test/java/com/gt8d/launcher/ui/panel/PanelActivityPipTest.kt(既存のファイルに足すならそのファイルを報告し、止まって確認を求める)
- 使ってよいコマンドに `--tests 'com.gt8d.launcher.sets.*'` を足したもの:
  `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.split.*' --tests 'com.gt8d.launcher.settings.*' --tests 'com.gt8d.launcher.pip.*' --tests 'com.gt8d.launcher.ui.panel.*' --tests 'com.gt8d.launcher.sets.*'`
