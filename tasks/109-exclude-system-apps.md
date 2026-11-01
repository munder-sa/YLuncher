# Task 109: 純正ランチャーと分割アプリを、ドックとアプリ一覧の対象から外す

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
ユーザーの決定: 純正ランチャー(`com.android.launcher3`)と分割アプリ(`com.sd.factoryconfig`、`VendorBridge.VENDOR_PKG`。端末では「スプリットスクリーン」の名前でランチャーに出る)は、**ドック・アプリ一覧(登録・すべて・編集)**のどれにも出さない。ホーム下段の段はアプリ一覧の登録から作るので、そこからも消える。純正ランチャーへは今どおり設定の「純正ランチャーに戻す」(`stockLauncherIntent`)で戻れる(ここは変えない)。

## 変更・作成するファイル(これで全部)
- create `app/src/main/java/com/gt8d/launcher/apps/HiddenApps.kt`
- `app/src/main/java/com/gt8d/launcher/ui/apps/AppList.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt`
- `app/src/main/java/com/gt8d/launcher/AppGraph.kt`(よく使うアプリの除外の所だけ)
- `app/src/main/java/com/gt8d/launcher/settings/ListsScreen.kt`(ドックの所だけ)
- テスト: `app/src/test/java/com/gt8d/launcher/ui/apps/AppListScreenTest.kt`、`app/src/test/java/com/gt8d/launcher/ui/apps/AppListTest.kt`、`app/src/test/java/com/gt8d/launcher/apps/AppCatalogTest.kt`、`app/src/test/java/com/gt8d/launcher/ui/panel/PanelScreenTest.kt`、`app/src/test/java/com/gt8d/launcher/settings/DailyScreensTest.kt`、`app/src/test/java/com/gt8d/launcher/apps/FrequentAppsTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.apps.*' --tests 'com.gt8d.launcher.ui.apps.*' --tests 'com.gt8d.launcher.ui.panel.*' --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.settings.*'`

## 仕様
1. `HiddenApps.kt`: `object HiddenApps { val packages: Set<String> = setOf(SetRunner.STOCK_LAUNCHER_PKG か "com.android.launcher3", VendorBridge.VENDOR_PKG); fun isHidden(pkg: String): Boolean }`。自分(`OwnPackages`)は今どおり別に除く。
2. アプリ一覧: `buildAppList` は `HiddenApps.packages` のパッケージ(どの activity も)を除き、先頭に `STOCK_LAUNCHER_ENTRY` を足すのをやめる(並びは名前順だけ)。これで登録・すべて・編集・ホームの段のどれにも出ない。保存済みの `appListPkgs` に入っていても出ない(保存の中身は消さなくてよい)。`seedAppList` も `HiddenApps` を入れない。
3. `PanelScreen` の `launchEntry` の純正ランチャーの分岐は、一覧に出なくなるので消してよい(`STOCK_LAUNCHER_ENTRY` の定義を他で使っていなければ消してもよい。使っていれば残す)。最初の登録の「最近使ったアプリ」から除く所は `HiddenApps.packages` を使う。
4. ドック: 4 つの枠(Auto の順位・Auto の埋め・Manual)のどれにも `HiddenApps` を入れない(`dockAppsFor` の `exclude` に足す。設定アプリの除外は今のまま)。`AppGraph.refreshFrequentApps` の除外も `HiddenApps.packages` を使う。保存済みの `dockApps` に入っていても出ない。
5. 設定「パネルとホーム」のドック: Manual の選択の候補から `HiddenApps` を除く。自動の行にも出ない(4 と同じ関数で決まる)。パネルのアイコン・停止中のアプリの候補は変えない。

## テスト(足す。既存は弱めない。純正ランチャーを一覧の先頭に出す・一覧から純正ランチャーを開く、を確かめていた既存のテストは「一覧に出ない」を確かめる形に置き換えて報告する)
- `AppListTest` / `AppListScreenTest` / `AppCatalogTest`: 純正ランチャー(別の activity を含む)と分割アプリが `buildAppList` に出ない、名前順だけ、登録済みでも出ない。
- `PanelScreenTest`: 一覧(登録・すべて)とホームの段に出ない、ドック(Auto・Manual で保存済みに入っていても)に出ない。
- `FrequentAppsTest`: `dockAppsFor` が `exclude` の分割アプリ・純正ランチャーを Manual・埋めでも除く。
- `DailyScreensTest`: ドックの選択の候補に出ない、パネルのアイコンの候補には今どおり出る。

## この Task でしないこと
- 設定の「純正ランチャーに戻す」・分割の仕組み(`VendorBridge`・`SetRunner`)・リンクの変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、置き換えた既存テスト、**決めたこと**。
