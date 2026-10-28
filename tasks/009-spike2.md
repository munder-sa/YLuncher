# Task 1b: 分割を作る方法の追加実験コード(debug 専用の捨てコード)

前回(`tasks/008-spike.md`)で、全画面からの `FLAG_ACTIVITY_LAUNCH_ADJACENT` では分割にならないことが分かった。分割を作る別の方法を試すための実験コードを足す。**コードを書くところまで**。端末への導入・実行は別の担当が行うので、`adb` を実行しない。

## 作る・変更するファイル(これで全部。許可外の書込みは拒否され、そこで停止する)

- 変更: `app/src/debug/AndroidManifest.xml`、`app/src/debug/java/com/gt8d/launcher/spike/SpikeActions.kt`
- 新規: `app/src/debug/java/com/gt8d/launcher/spike/SpikeAccessibilityService.kt`
- 新規: `app/src/debug/res/xml/spike_accessibility.xml`

パッケージは `com.gt8d.launcher.spike`。ログは `Log.i("GT8D-SPIKE", ...)`(失敗は `Log.w`)。既存の `SpikeActions` の関数・ログの書式は変えない。

## 仕様

### SpikeAccessibilityService.kt / spike_accessibility.xml / Manifest

- `class SpikeAccessibilityService : AccessibilityService()`。`onServiceConnected` で `instance = this` を保持し `Log.i("... service connected")`、`onUnbind`/`onDestroy` で `instance = null`。`onAccessibilityEvent` と `onInterrupt` は空。`companion object { @Volatile var instance: SpikeAccessibilityService? = null }`
- `spike_accessibility.xml`(`<accessibility-service>`): `accessibilityEventTypes` は指定しない(`typeNone`)、`accessibilityFeedbackType="feedbackGeneric"`、`canRetrieveWindowContent="false"`、**タッチ探索は有効にしない**(`flagRequestTouchExplorationMode` を付けない)、`description` は文字列リテラル不可なら `@string` を作らずに `android:description` を省く(`description` が必須で省略できないときは、debug 側に作らず、報告して止まる)
- Manifest(debug)に `<service android:name=".spike.SpikeAccessibilityService" android:exported="true" android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">`、`intent-filter` は `android.accessibilityservice.AccessibilityService`、`<meta-data android:name="android.accessibilityservice" android:resource="@xml/spike_accessibility" />` を追加する

### SpikeActions に足す step

- `toggle_split`: `SpikeAccessibilityService.instance` が null なら `FAIL toggle_split service-not-enabled`。あれば `performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)` を呼び、戻り値を `toggle_split result=<true|false>` としてログ
- `info`: 次を 1 行ずつログ: `Settings.Global.getInt(contentResolver, "enable_freeform_support", 0)`、`Settings.Global.getInt(contentResolver, "force_resizable_activities", 0)`、`packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)`、`hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)`、`getSystemService(WindowManager::class.java).currentWindowMetrics.bounds`
- `freeform`: extras `left`、`right`(どちらもパッケージ名)、`ratio`(左の幅の割合、整数%、既定 50)、`delay`(ms、既定 300)。`WindowManager.currentWindowMetrics.bounds` の幅 W・高さ H から、左 = `Rect(0, 0, W*ratio/100, H)`、右 = `Rect(W*ratio/100, 0, W, H)`。左を `getLaunchIntentForPackage(left)` + `FLAG_ACTIVITY_NEW_TASK` + `ActivityOptions.makeBasic().setLaunchBounds(左)` で起動し、`delay` ms 後に右を `NEW_TASK or MULTIPLE_TASK` + `setLaunchBounds(右)` で起動。`startActivity(intent, options.toBundle())`。成否は既存の `startSafe` と同じ形式で `START ... -> ok/FAIL` とログ(`startSafe` を使うか、`options` を渡せる版を足す)。起動後 1 秒待って、左右の起動結果とは別に `freeform bounds requested left=<Rect> right=<Rect>` をログ

## 手順

1. 上記のファイルを作る・変更する
2. `/home/munde/tools/gradle-run.sh assembleDebug` を実行し、`BUILD SUCCESSFUL` になるまで直す(最大3回。通らなければ、エラーの先頭30行を報告して止まる)
3. `/home/munde/tools/gradle-run.sh test` を実行し、既存のテストが全部通ることを確認する

## 守ること

- 捨てコードでも、`ctx` は `applicationContext`、`startActivity` の失敗は必ずログ、は守る
- 走行状態による分岐、`com.android.launcher3` への操作を入れない
- 最後に、`assembleDebug` と `test` の結果、変更・作成したファイル名を報告する。実機で動くことは確認していないので「確認した」と書かない
