最初に tasks/RULES.md を読み、従う。

# Task 272: マップの自動フリードライブをやめ、Drive Mode Launcher を GPT Live と同じ「分割の行き先」で開く

## 背景(実機、2026-11-18)
- Task 264 で、Google マップ(`com.google.android.apps.maps`)は、ホーム・パネル・セットのどこから開いても常に `google.navigation:/?free=1&mode=d&entry=fnls`(フリードライブ)で開くようにした。
- ユーザーが Play の「Driving Mode Launcher」(`com.gatolabo.drivemodelauncher`、2.4)を入れた。APK を調べた結果: 画面を持たない `LauncherActivity`(MAIN/LAUNCHER)だけのアプリで、起動すると `ACTION_VIEW google.navigation:/?free=1&mode=d&entry=fnls` を `com.google.android.apps.maps` に送る(ライセンス確認あり)。manifest に YLuncher の `com.gt8d.launcher.split.*` の meta-data は無い。
- これは Task 112 の GPT Live(すぐ消えて ChatGPT に VIEW を送る中継アプリ)と同じ形。
- ユーザーの決定:
  1. 普通の Google マップは、Task 264 以前どおり普通に開く(フリードライブにしない)。
  2. Drive Mode Launcher は、GPT Live と同じ扱いで分割に入れる。分割に置くのはマップ、分割がそろったらフリードライブの URI をマップに送る。

## 作る/変えるファイル(これ以外は触らない)
- write: `app/src/main/java/com/gt8d/launcher/apps/MapsFreeDrive.kt`
- write: `app/src/main/java/com/gt8d/launcher/split/ActivityLauncher.kt`
- write: `app/src/main/java/com/gt8d/launcher/AppGraph.kt`(redirects を作る所だけ)
- write: `app/src/main/java/com/gt8d/launcher/ui/panel/PanelActivity.kt`(redirects を作る所だけ)
- write: `app/src/test/java/com/gt8d/launcher/apps/SplitRedirectTest.kt`
- write: `app/src/test/java/com/gt8d/launcher/split/AndroidActivityLauncherTest.kt`
- write: `app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt`
- `SetRunner.kt` は変えない(下の「残すもの」を参照)。もし変えないと仕様を満たせないと分かったら、変えずに止まって報告する。

## 仕様
1. `ActivityLauncher.kt`(`AndroidActivityLauncher`): Task 264 で足した `tryMapsFreeDrive` と、その `startComponent`・`startApp` からの呼び出しを外す。マップも他のアプリと同じく、ランチャーの Intent で開く。`launchFlags` の共通化は残してよい(動きは Task 264 以前と同じ)。`MapsFreeDrive` の import が要らなくなれば消す。
2. `MapsFreeDrive.kt`:
   - `MapsFreeDrive.URI`・`isMaps` は残す(`SetRunner` の冷えたマップの判定が使う)。`intent()` は使う所が無くなれば消す。
   - `const val DRIVE_MODE_LAUNCHER = "com.gatolabo.drivemodelauncher"` を足す。
   - `MapsFreeDriveRedirects(base, isInstalled: (String) -> Boolean)`: まず `base.forPackage(pkg)` を返す(meta-data を持つアプリが優先。今どおり)。無ければ、`pkg == DRIVE_MODE_LAUNCHER` かつ `isInstalled(Defaults.PKG_MAPS)` のときだけ `SplitRedirect(Defaults.PKG_MAPS, null, Uri.parse(URI))`。**`pkg` がマップ自身のときは null**(普通に開く)。マップが入っていなければ null(今までどおり元のアプリで開く。Task 112 と同じ決まり)。
   - `isInstalled` の実装は AppGraph・PanelActivity で、`AndroidSplitRedirects` と同じやり方(`MAIN`+`LAUNCHER`+`setPackage` の `queryIntentActivities` が空でない)にする。重複がいやなら `SplitRedirect.kt` 側ではなく `MapsFreeDrive.kt` に小さな関数を置いてよい(`SplitRedirect.kt` は変えない)。
3. `AppGraph.kt`・`PanelActivity.kt`: 新しい `MapsFreeDriveRedirects` の作り方に合わせる。それ以外は変えない。
4. 結果として期待する動き(Task 112 の仕組みそのまま。コードの追加は要らないはず):
   - セット「X | Drive Mode Launcher」: E に `X | maps` が渡り、分割がそろったらフリードライブの URI をマップへ1回だけ送る。マップが冷えていれば Task 270/271 の遅らせた送信(ready から 2.5 秒・パネル位置を最大 3 秒待つ)がそのまま効く。
   - 分割できず全画面に落ちるときは、元のアプリ(Drive Mode Launcher)を開く(それ自身がフリードライブで開く)。
   - 分割の中のパネルから Drive Mode Launcher を押すと、`startRedirect(…, adjacent = true)` でマップの隣にフリードライブ。分割の外では Drive Mode Launcher をそのまま開く。
   - 入れ替え保存が設定に書くのは `com.gatolabo.drivemodelauncher`(元のパッケージ)。
   - 普通の Google マップは、ホーム・パネル・セットのどこでも、フリードライブの URI を送らない。

## 残すもの(戻さない)
- Task 266(Safety 後の遅れた redirect)、Task 268(セットを開く前の PiP 解除)、Task 270/271(冷えたマップへの遅らせた送信)。これらは redirect の行き先がマップのとき一般に効く仕組みなので、Drive Mode Launcher で使う。

## テスト(既存は弱めない。前提が変わったものは書き換え、理由を報告する)
- `AndroidActivityLauncherTest`: Task 264 の「マップはフリードライブで開く」テストを、「マップはランチャーの Intent(ACTION_MAIN、data なし)で開く」に書き換える(`startApp` と `startComponent`、隣に開く場合も)。
- `SplitRedirectTest`: `MapsFreeDriveRedirects` について — Drive Mode Launcher でマップが入っている → マップ+URI、component null。マップが入っていない → null。マップ自身 → null。base が返すものがあればそれが優先。その他のアプリ → null。
- `SetRunnerTest`: 今 `MapsFreeDriveRedirects` やマップへの redirect を使っているテストは、**左右に置くアプリを Drive Mode Launcher にする**形へ直す(E に渡るのはマップ、送る redirect は今と同じ)。冷えた・温かいマップの送信の期待は変えない。「普通のマップのセットでは redirect を送らない」テストを1つ足す。
- 流すテスト(これだけ):
  `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.apps.*' --tests 'com.gt8d.launcher.sets.*' --tests 'com.gt8d.launcher.split.*' --tests 'com.gt8d.launcher.ui.panel.*'`

## この Task でしないこと
- 設定データの形・セットの編集画面・リンクの分割の変更。版名の変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、**決めたこと**、書き換えたテストとその理由。
