最初に tasks/RULES.md を読み、従う。

# Task 116: 純正ランチャーを「ホームの種類」で開く(CATEGORY_HOME を付ける)

## 背景(実機で確認済み)
- `am start -n com.android.launcher3/.uioverrides.QuickstepLauncher`(カテゴリなし)で開くと、純正ランチャーは type=standard のタスクとして残る。この状態だと、左パネルのセット(左 パネル | 右 YouTube)の分割が、成立の直後に解ける(場面テスト set_leftPanel_cold が再現性ありで不合格)。
- `am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.android.launcher3/.uioverrides.QuickstepLauncher` で開くと type=home のタスクになり、分割の broadcast の受信者も登録され、左パネルの分割も解けない。
- 114 の温め(startStockLauncher)と、ホーム・パネルの「純正ランチャー」ボタン(stockLauncherIntent を使う)は、どちらもカテゴリなしで開いている。

## 変更
- app/src/main/java/com/gt8d/launcher/apps/StockLauncher.kt の `stockLauncherIntent()` に `addCategory(Intent.CATEGORY_HOME)` を加える。action(MAIN)・component・FLAG_ACTIVITY_NEW_TASK は変えない。呼び出し側(HomeActivity、PanelActivity、startStockLauncher)は変えない。
- app/src/test/java/com/gt8d/launcher/apps/StockLauncherTest.kt: `stockLauncherIntent()` が CATEGORY_HOME を含むことを確かめるテスト `stockLauncherIntent_hasHomeCategory` を追加。`startStockLauncher_successUsesExistingIntent` に、渡された Intent のカテゴリが stockLauncherIntent() と同じであることの確認を追加(既存の確認は残す)。

## 作るファイル・変えるファイル(これで全部)
- write app/src/main/java/com/gt8d/launcher/apps/StockLauncher.kt
- write app/src/test/java/com/gt8d/launcher/apps/StockLauncherTest.kt

## 使ってよいコマンド
`/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.apps.*'`

## 報告
変更点、実行したコマンドと件数、決めたこと。
