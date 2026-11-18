最初に tasks/RULES.md を読み、従う。

# Task 264: Google マップを常にフリードライブ(free=1)で開く

## 背景(実機で確認済み、2026-11-18)
- GT8D の Google マップ(26.46.00)は、次の URI を `ACTION_VIEW` で受けると、車用の地図画面(上に「目的地設定」)を開く。
  - `google.navigation:/?free=1&mode=d&entry=fnls`
  - `cmd package resolve-activity` の結果は `com.google.android.apps.maps/com.google.android.maps.MapsActivity`。
  - マップがすでに前面にあるときも、同じ画面に届く(`am start` の結果は "delivered to currently running top-most instance")。
- ユーザーの決定: ホーム・パネル・セット(分割)の**どこから開いても、毎回** free=1 で開く。案内中に押すと案内が切り替わるおそれがあるが、それは許容する。

## 作る/変えるファイル(これ以外は触らない)
- create: `app/src/main/java/com/gt8d/launcher/apps/MapsFreeDrive.kt`
- write: `app/src/main/java/com/gt8d/launcher/split/ActivityLauncher.kt`
- write: `app/src/main/java/com/gt8d/launcher/AppGraph.kt`
- write: `app/src/main/java/com/gt8d/launcher/ui/panel/PanelActivity.kt`
- write: `app/src/test/java/com/gt8d/launcher/split/AndroidActivityLauncherTest.kt`
- write: `app/src/test/java/com/gt8d/launcher/apps/SplitRedirectTest.kt`

## 仕様

### 1. `MapsFreeDrive.kt`(新規、パッケージ `com.gt8d.launcher.apps`)
- `object MapsFreeDrive`:
  - `const val URI = "google.navigation:/?free=1&mode=d&entry=fnls"`
  - `fun isMaps(pkg: String): Boolean = pkg == Defaults.PKG_MAPS`
  - `fun intent(): Intent`: `Intent(Intent.ACTION_VIEW, Uri.parse(URI)).setPackage(Defaults.PKG_MAPS)` を返す。component は指定しない。
- `class MapsFreeDriveRedirects(private val base: SplitRedirects) : SplitRedirects`:
  - `forPackage(pkg)` では、まず `base.forPackage(pkg)` を呼ぶ。結果が null でなければそれを返す。
  - 結果が null で `isMaps(pkg)` のときは `SplitRedirect(Defaults.PKG_MAPS, null, Uri.parse(URI))` を返す。それ以外は null。
  - 狙い: 既存の「分割が整ったあとに `startRedirect` で行き先の画面へ URI を渡す」仕組み(`SetRunner` の waitBoth ready、`PanelActivity.onLaunchApp`)にそのまま乗せる。`targetPkg` がマップ自身なので、起動するパッケージは変わらない。

### 2. `ActivityLauncher.kt` の `AndroidActivityLauncher`
- `startApp(pkg, adjacent)`: `MapsFreeDrive.isMaps(pkg)` のときは、`MapsFreeDrive.intent()` に既存と同じフラグの決め方(`launchAdjacent` を使った NEW_TASK / LAUNCH_ADJACENT / MULTIPLE_TASK)を付けて `startActivity` する。
  - 成功したら true。
  - 例外(`ActivityNotFoundException` など)のときは、`DiagLog.d("Launch", ...)` で記録し、既存の `getLaunchIntentForPackage` の経路に落とす。
- `startComponent(component, adjacent)`: `component.packageName` がマップのときも同じ。free=1 の intent は component を付けず、パッケージだけを指定する(ランチャーの component が VIEW を受けない別名の場合に備える)。失敗時は既存の経路に落とす。
- `startRedirect` は変えない(URI があれば VIEW + SINGLE_TOP の既存の動きで足りる)。
- マップ以外のパッケージの動きは一切変えない。

### 3. 配線
- `AppGraph.kt`: `redirects = com.gt8d.launcher.apps.AndroidSplitRedirects(app.packageManager)` を `MapsFreeDriveRedirects(AndroidSplitRedirects(app.packageManager))` で包む。
- `PanelActivity.kt` の `onCreate`: `redirects = AndroidSplitRedirects(packageManager)` を同じように包む。

### 4. テスト(この範囲だけ。関係のない場所へ広げない)
- `AndroidActivityLauncherTest`:
  - `startApp("com.google.android.apps.maps", adjacent = false)` で、次に開かれる intent が ACTION_VIEW、data が `MapsFreeDrive.URI`、package がマップ、フルスクリーンのフラグであること。
  - `startComponent(マップの component, adjacent = true)`(PiP ではない)で、ACTION_VIEW と free=1 の URI、LAUNCH_ADJACENT が付き、component が null であること。
  - VIEW を受ける画面が無く `startActivity` が失敗するとき、既存の起動 intent に落ちること(Robolectric で書けない場合は、書けない理由を報告し、このケースは省く)。
  - マップ以外のパッケージは、従来どおり ACTION_VIEW にならないこと(既存テストで足りていれば追加しない)。
- `SplitRedirectTest`:
  - `MapsFreeDriveRedirects` が、マップに対して `SplitRedirect(maps, null, URI)` を返すこと。
  - base が null 以外を返すときは、base の結果をそのまま返すこと。
  - マップ以外で base が null のときは null を返すこと。

## 変えないこと
- `SetRunner.kt`・`PanelScreen.kt`・`HomeActivity.kt`(上の配線と `AndroidActivityLauncher` の変更で、全経路に効く)
- `SplitRedirect` / `AndroidSplitRedirects` の既存の動き
- 版番号、ほかのアプリの起動の仕方

## 検証
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.split.AndroidActivityLauncherTest' --tests 'com.gt8d.launcher.apps.SplitRedirectTest' --tests 'com.gt8d.launcher.sets.SetRunnerTest'`

## 報告
- 変えたファイルと要点
- テストの結果(件数・失敗)
- RULES.md の 1「決めたこと」
