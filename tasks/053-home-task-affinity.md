# Task 053: ホームとパネルのタスクを分ける(分割の右にホームが入る問題)

最初に `tasks/RULES.md` を読み、従うこと。仕様 §5.2 の HomeActivity の行(2026-10-31 に書き換え済み)に従う。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

## 背景(司令塔が実機のログで確かめた事実)
E は分割の右に「com.gt8d.launcher」のパッケージを開く(ランチャーの入口 = PanelActivity)。HomeActivity と PanelActivity は同じ `taskAffinity`(既定 = パッケージ名)なので、
HomeActivity のタスクが既にあると、Android はそのタスクを前に出し、**分割の右に HomeActivity が入る**(ログ: `wm_relaunch_resume_activity … HomeActivity`、PanelActivity は作られない)。
パネルの合図(`panelResumedInSplit`)が来ないため、4 秒の安全タイマーで失敗になり、リンクは全画面のブラウザになる。YLuncher を既定のホームにすると毎回起きる。

## 変更するファイル(これで全部)
- `app/src/main/AndroidManifest.xml`(`.ui.home.HomeActivity` に `android:taskAffinity="com.gt8d.launcher.home"` を足すだけ)
- 新規 `T/ui/TaskAffinityTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.ui.TaskAffinityTest --tests com.gt8d.launcher.ui.home.* --tests com.gt8d.launcher.ui.panel.PanelActivityTest`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## テスト(先に書き、修正前に失敗することを確かめる)
`TaskAffinityTest`(Robolectric、sdk 35。`PackageManager.getActivityInfo` で読む):
- `home_hasOwnAffinity`(HomeActivity の taskAffinity が "com.gt8d.launcher.home")
- `panel_keepsPackageAffinity`(PanelActivity の taskAffinity がパッケージ名のまま。E がパッケージ名で開くため)
- `home_and_panel_differ`、`fullscreenBrowser_keepsOwnAffinity`(FullscreenBrowserActivity は今の "com.gt8d.launcher.browser" のまま)
- `launcherIntent_resolvesToPanel`(`getLaunchIntentForPackage` の行き先が PanelActivity)

## この Task でしないこと
- ほかの Activity・コードの変更。全テストの実行、実機の操作、git のコミット等。

## 報告
実行したコマンドと結果(件数・失敗件数、修正前の失敗の内容、assembleDebug)、変更したファイル、**決めたこと**。
