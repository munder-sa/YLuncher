# Task 055(実験): パネル用の第2のアプリ(中継の画面だけ)を作る

最初に `tasks/RULES.md` を読み、従うこと。**これは実験。実機での確認は司令塔が行う。**

## 背景(司令塔が実機で確かめた事実)
メーカーの分割の入口(E)は、右にパッケージ名しか渡せない。純正ランチャーは、そのパッケージの**既存のタスクのどれか**を分割の右に入れる(所属 taskAffinity ではなくパッケージで選ぶ)。
`com.gt8d.launcher` にはパネル・ホーム・全画面ブラウザのタスクがあり得るため、ホームが分割の右に入ったり、YLuncher が既定のホームのときは SystemUI が異常終了したりする。
そこで、分割の右に渡す専用のパッケージ `com.gt8d.launcher.panel` を作り、その中の「中継の画面」が、自分のタスクの中に本体の `com.gt8d.launcher/.ui.panel.PanelActivity` を開く方式を試す。

## 作成・変更するファイル(これで全部)
- `settings.gradle.kts`(`include(":panelhost")` を足すだけ)
- 新規 `panelhost/build.gradle.kts`
- 新規 `panelhost/src/main/AndroidManifest.xml`
- 新規 `panelhost/src/main/java/com/gt8d/launcher/panelhost/PanelHostActivity.kt`
- 新規 `panelhost/src/main/res/values/strings.xml`(アプリ名「GT8D パネル」)
- 新規 `panelhost/src/main/res/values/themes.xml`(下の透明なテーマ)

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh :panelhost:assembleDebug`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様
1. モジュール `:panelhost`: Android アプリ。`namespace`・`applicationId` は `com.gt8d.launcher.panel`。minSdk・targetSdk・compileSdk 35、JDK 17。**Compose や AndroidX の依存は使わない**(素の `android.app.Activity` だけ)。Kotlin は `:app` と同じプラグイン(`libs.plugins.kotlin.android`)。署名は `:app` と同じ考え方(`keystore.properties` があれば同じ release の設定、無ければ debug)。`:app` の `build.gradle.kts` の該当部分を読み、同じ書き方にする。
2. `PanelHostActivity`(`android.app.Activity`):
   - マニフェスト: `exported="true"`、MAIN/LAUNCHER、`android:resizeableActivity="true"`、`android:label="@string/app_name"`、テーマは透明・タイトルなし(`@android:style/Theme.Translucent.NoTitleBar` を親にした `themes.xml` の `Theme.PanelHost`)。`launchMode` は指定しない。
   - `onCreate`(`savedInstanceState == null` のときだけ): `Intent().setComponent(ComponentName("com.gt8d.launcher", "com.gt8d.launcher.ui.panel.PanelActivity"))` を、**`FLAG_ACTIVITY_NEW_TASK` を付けずに**起動する(同じタスクの中に開く)。起動に失敗したら(例外)`finish()`。起動したことをフィールドに覚え、`onSaveInstanceState` で保存する。
   - `onResume`: 本体のパネルを起動した後に自分が再び一番上に来た(= 本体のパネルが閉じた)なら `finish()` する。初回の `onResume`(起動直後)では finish しない。
   - 画面には何も描かない(`setContentView` しない)。
3. `:app` は変えない。

## 確かめること
- `:panelhost:assembleDebug` と `assembleDebug` が通ること。テストは無し(実験のため)。

## 報告
作成・変更したファイル、実行したコマンドと結果、**決めたこと**。
