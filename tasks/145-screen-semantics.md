最初に tasks/RULES.md を読み、従う。

# Task 145: ホーム・パネル・幕の画面全体を、ユーザー補助には「パネル」1つとして見せる

## 背景
- 実機(1.7.1-beta2)の perfetto で、ユーザー補助向けの `checkForSemanticsChanges`+`sendAccessibilitySemanticsStructureChangeEvents` が 1回 約 17ms、12 秒に 30 回。端末ではユーザー補助サービス(carsyso と本アプリの PiP の見張り)が常に有効なので、Compose が画面の semantics の差分を毎回調べる。
- ユーザーの判断(2026-11-03): 「読み上げは今のところ使うところはないので、パネル全体で『パネル』としていい」「デバッグ版では全体を見て、リリースではパネルとする」。
- 確認済み: 実機の場面テスト(`scripts/device-scenarios.sh`)は `input tap` の座標と `dumpsys` を使い、ユーザー補助の画面情報(uiautomator)は使っていない。`PipWatcherService` が操作するのは他のアプリの PiP の窓だけ。

## 変えるファイル(これ以外は変えない)
- 新規 `app/src/main/java/com/gt8d/launcher/ui/theme/ScreenSemantics.kt`
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeActivity.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelActivity.kt`
- `app/src/main/java/com/gt8d/launcher/edge/CurtainWindow.kt`
- `app/src/main/java/com/gt8d/launcher/LauncherApp.kt`
- `app/src/test/java/com/gt8d/launcher/TestApp.kt`
- `app/src/test/java/com/gt8d/launcher/LauncherAppTest.kt`(注意の場合だけ)
- 新規 `app/src/test/java/com/gt8d/launcher/ui/theme/ScreenSemanticsTest.kt`

## 変更
1. `ScreenSemantics.kt`:
   ```kotlin
   object ScreenSemantics {
       // テスト(Robolectric)では true にして、細かい semantics(testTag など)を残す
       @Volatile var detailed: Boolean = false
   }
   fun Modifier.screenSemantics(label: String = "パネル"): Modifier =
       if (ScreenSemantics.detailed) this else this.clearAndSetSemantics { contentDescription = label }
   ```
2. `HomeActivity`・`PanelActivity` の `setContent { LauncherTheme { … } }` の中身(安全モードの画面・起動画面も含め、各 `setContent` の一番外側)を、`Box(Modifier.fillMaxSize().screenSemantics()) { … }` で包む。見た目・大きさ・押したときの動きは変えない(Box は内容をそのまま重ねるだけ)。`CurtainWindow` の `setContent` の中身も同様に包む(label は「パネル」のまま)。
3. `SettingsActivity` は変えない(設定画面の semantics は残す)。
4. **デバッグ版は細かい semantics を残し、リリース版だけ「パネル」1つにする**: `LauncherApp.onCreate` の最初で `ScreenSemantics.detailed = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0` にする(BuildConfig は有効にしない)。
5. `TestApp.onCreate` の最初で `ScreenSemantics.detailed = true` にする(既存の UI テストは今のまま通ること)。

## テスト
- `ScreenSemanticsTest`(Robolectric の Compose テスト):
  - `clearsChildrenWhenNotDetailed`: `ScreenSemantics.detailed = false` にして、`Box(Modifier.screenSemantics()) { Text("中の文字", Modifier.testTag("inner")) }` を出すと、`onNodeWithTag("inner", useUnmergedTree = true)` が存在せず、contentDescription「パネル」の節が1つある。テストの終わりで `detailed = true` に戻す
  - `keepsChildrenWhenDetailed`: `detailed = true` で `inner` が見つかる
- 既存のテストは変えない(TestApp の設定で今のまま通ること)。

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.*' --tests 'com.gt8d.launcher.edge.*'`
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.LauncherAppTest'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、決めたこと(RULES.md 1)。

## 注意
- `LauncherAppTest` などで `LauncherApp.onCreate` を直接呼ぶテストがあると、`ScreenSemantics.detailed` が false になり、同じ JVM の後のテストに影響しうる。そうしたテストがあれば、その後で `ScreenSemantics.detailed = true` に戻す(`@After`)。直したら報告する。
