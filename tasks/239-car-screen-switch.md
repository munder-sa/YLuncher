最初に `tasks/RULES.md` を読み、従う。

# Task 239: 純正画面へ戻る(`AT#CB`)— 小アプリ `carback` と `VendorBridge.switchToCarScreen()`

調査の記録(`docs/superpowers/spikes/meter-map-nav-bridge.md`)で、`com.suding.main.ACTION_EXECUTE_AT_CMD`(extra `extra_at_cmd` = `AT#CB`)を `com.carsyso.main` に送ると、AA 投影が車側(純正)の画面に切り替わることを確認した。これを次の2つで実装する。

## 仕様(確定。変えない)
- 送るもの: `Intent("com.suding.main.ACTION_EXECUTE_AT_CMD")` に `setPackage("com.carsyso.main")`、`putExtra("extra_at_cmd", "AT#CB")`。`sendBroadcast` で送る。応答は無い(送れたら成功)。
- 例外は握りつぶさず、失敗は false を返し、`DiagLog` に記録する(app 側)。

## A. 新モジュール `:carback`(画面なしの小アプリ。ランチャーのアイコンから押す)
- `settings.gradle.kts` に `include(":carback")` を足す。
- `carback/build.gradle.kts`: `panelhost/build.gradle.kts` を手本にする(plugins・compileSdk 35・minSdk 35・targetSdk 35・Java/Kotlin 17・テスト設定)。namespace/applicationId は `com.gt8d.launcher.carback`、versionCode 1、versionName "0.1.0"。署名の release 設定は panelhost と同じ書き方で足す。依存は `junit` と `robolectric` のテストだけ(panelhost と同じ)。
- `carback/src/main/AndroidManifest.xml`: ランチャーに出る Activity 1つ(`MAIN`/`LAUNCHER`、`exported=true`、`Theme.CarBack` = `@android:style/Theme.Translucent.NoTitleBar`、`excludeFromRecents=true`、`noHistory=true`)。`<queries>` は不要(`setPackage` の送信には要らない)。
- `CarBackActivity`(`carback/src/main/java/com/gt8d/launcher/carback/CarBackActivity.kt`): `onCreate` で `CarScreenSwitch.send(this)` を呼び、すぐ `finish()`。画面は出さない。
- `CarScreenSwitch`(同パッケージ): `fun buildIntent(): Intent` と `fun send(context: Context): Boolean`(`sendBroadcast`。例外なら false)。定数は `ACTION`・`TARGET_PKG`・`EXTRA_AT_CMD`・`AT_CMD_BACK`。
- リソース: `values/strings.xml`(`app_name` = 「純正画面へ」)、`values/themes.xml`、`mipmap-anydpi-v26/ic_launcher.xml` と `drawable/ic_launcher_*.xml` は panelhost から**コピー**してよい(中身はそのまま)。
- テスト `carback/src/test/java/com/gt8d/launcher/carback/CarScreenSwitchTest.kt`(Robolectric):
  1. `buildIntent` の action・package・extra が仕様どおり。
  2. `send` で、`ShadowApplication` に記録されたブロードキャストが仕様どおり(1件)。

## B. app の `VendorBridge`
- `ActivityLauncher` に、既定実装つきで次の2つを足す(既存のテスト用 Fake を壊さないため、既定は false):
  - `fun isInstalled(pkg: String): Boolean = false`
  - `fun sendBroadcast(action: String, pkg: String, extras: Map<String, String>): Boolean = false`
- `AndroidActivityLauncher` に実装する(`isInstalled` は `PackageManager`、`sendBroadcast` は `setPackage` 付きの `context.sendBroadcast`、例外は false)。
- `app/src/main/AndroidManifest.xml` の `<queries>` に `<package android:name="com.carsyso.main" />` を足す(`isInstalled` のため)。
- `VendorBridge`: `carHomeAvailable` = `launcher.isInstalled("com.carsyso.main")`。`switchToCarScreen()` = 上の仕様で送る。`DiagLog.d("Vendor", ...)` で記録する。定数 `CAR_PKG`・`ACTION_AT_CMD`・`EXTRA_AT_CMD`・`AT_CMD_BACK` を companion に置く。
- `VendorBridgeTest` の `carHome_unavailableByDefault` は、新しい仕様に合わせて**置き換える**(弱めない): Fake に `installed` と `broadcasts` を持たせ、(1) 入っていれば `carHomeAvailable` が true、`switchToCarScreen()` が true で、送った action・pkg・extras が仕様どおり、(2) 入っていなくても送信自体は試みる/試みない のどちらにするかは**仕様どおり「送る」**(`carHomeAvailable` は表示の出し分け用で、`switchToCarScreen()` は常に送る)、(3) `sendBroadcast` が false を返したら `switchToCarScreen()` も false、の3件にする。
- `AndroidActivityLauncherTest` に、`sendBroadcast` が仕様どおりのブロードキャストを1件出すテストを1件足す。

## 変えないもの
`panelhost`、他のモジュール、署名鍵、`docs/`、版(`app`・`panelhost` の版は触らない)。ADB・Git・release ビルドは実行しない。

## 使ってよいコマンド
    /home/munde/tools/gradle-run.sh :carback:testDebugUnitTest
    /home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.split.VendorBridgeTest' --tests 'com.gt8d.launcher.split.AndroidActivityLauncherTest'
    /home/munde/tools/gradle-run.sh :carback:assembleDebug

## 報告
- 変えたファイル、コマンドと結果(件数・時間)、APK のパス、決めたこと(RULES.md の 1)
