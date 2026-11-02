最初に tasks/RULES.md を読み、従う。

# Task 122: 中継(panelhost)が、パネルが消えたときは閉じずにパネルを開き直す

## 背景(実機のログで確認済み、2026-11-02)
- 中継 `PanelHostActivity`(パッケージ com.gt8d.launcher.panel)は、onCreate で本体の `PanelActivity` を自分の上(同じタスク)に開き、2 回目の onResume で「パネルが閉じられた」とみなして自分も finish する。
- 本体(com.gt8d.launcher)を入れ直すと、本体のプロセスと一緒に、中継のタスクの上にあった PanelActivity が消える。中継のタスクは残る。
- その後の最初のセットで、Vendor の分割がその古い中継のタスクを右に置く → 中継が前に出て 2 回目の onResume → finish → タスクが空 → SystemUI が分割を解く(`prepareExitSplitScreen` `remove all side stage tasks`)。セットは失敗(Safety)、2 回目は新しい中継のタスクができるので成功する。
- 本体が強制停止・メモリ不足で落ちたときも同じ形になる。

## 方針
「パネルが自分の意思で閉じた」と「パネルが消された(プロセスが落ちた)」を、Activity の結果で見分ける。
- 本体の PanelActivity は onCreate で `setResult(Activity.RESULT_OK)` を呼ぶ。自分で finish したときは RESULT_OK が中継に返る。プロセスごと消えたときは、システムが RESULT_CANCELED を返す。
- 中継は `startActivityForResult` でパネルを開き、RESULT_CANCELED が返ってきたら、次の onResume で finish せずにパネルを開き直す。

## 変更
### panelhost/src/main/java/com/gt8d/launcher/panelhost/PanelHostActivity.kt
- パネルを開く処理を `launchPanel()` にまとめ、`startActivityForResult(intent, REQUEST_PANEL)` で開く(Intent は今と同じ。フラグは付けない)。例外なら finish(今と同じ)。
- `onActivityResult(requestCode, resultCode, data)`: REQUEST_PANEL で resultCode が RESULT_OK 以外なら `relaunchOnResume = true`。
- `onResume`: `panelLaunched && hasResumed` のとき、`relaunchOnResume` が true かつ開き直した回数が `MAX_RELAUNCH = 3` 未満なら、`relaunchOnResume = false`、回数を 1 増やして `launchPanel()`。そうでなければ今と同じく finish。
- `relaunchOnResume` と回数も onSaveInstanceState で保存・復元する。
- ログは `android.util.Log.d("GT8D", "[PanelHost] relaunch panel count=$n")`(中継は本体の DiagLog を使えないため)。

### app/src/main/java/com/gt8d/launcher/ui/panel/PanelActivity.kt
- `onCreate` の `super.onCreate` の直後に `setResult(RESULT_OK)` を 1 行。理由のコメントを 1 行(中継が、自分で閉じたのかプロセスごと消えたのかを見分けるため)。ほかは変えない。

### テスト
- panelhost に単体テストの仕組みを足す: `panelhost/build.gradle.kts` に `testImplementation(libs.junit)`・`testImplementation(libs.robolectric)` と、`testOptions { unitTests.isIncludeAndroidResources = true }`(app/build.gradle.kts にならう。必要な設定が他にもあれば app にならい、報告に並べる)。
- `panelhost/src/test/java/com/gt8d/launcher/panelhost/PanelHostActivityTest.kt`(新規、Robolectric):
  - `onCreate_startsPanelForResult`: 開いた Intent の component が PanelActivity、forResult(requestCode)で開いている。
  - `panelFinishedOk_hostFinishesOnNextResume`: RESULT_OK が返ったあと onResume で finish する。
  - `panelKilled_hostRelaunchesPanelOnNextResume`: RESULT_CANCELED のあと onResume で finish せず、もう一度 PanelActivity を開く。
  - `relaunch_limitedToThree`: CANCELED と onResume を 4 回くり返すと、4 回目は finish。
- 本体のテストは、PanelActivity が RESULT_OK を設定していることを確かめる 1 件を既存の PanelActivity のテスト(`app/src/test/java/com/gt8d/launcher/ui/panel/PanelActivityTest.kt`)に足す: `onCreate_setsResultOk`。

## 作るファイル・変えるファイル(これで全部)
- write panelhost/src/main/java/com/gt8d/launcher/panelhost/PanelHostActivity.kt
- write panelhost/build.gradle.kts
- create panelhost/src/test/java/com/gt8d/launcher/panelhost/PanelHostActivityTest.kt
- write app/src/main/java/com/gt8d/launcher/ui/panel/PanelActivity.kt
- write app/src/test/java/com/gt8d/launcher/ui/panel/PanelActivityTest.kt

## 使ってよいコマンド
- `/home/munde/tools/gradle-run.sh :panelhost:testDebugUnitTest :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.panel.*'`
- `/home/munde/tools/gradle-run.sh :panelhost:testDebugUnitTest`
