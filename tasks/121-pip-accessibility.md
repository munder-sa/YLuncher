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
