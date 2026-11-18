最初に tasks/RULES.md を読み、従う。

# Task 269: 冷えたマップを含むセットは、先にマップをフリードライブで全画面起動してから分割する

## 背景(実機で確認済み、2026-11-18、2.3.9-beta2、マップの PiP は appops で ignore)
- マップが動いていないときにセットを開くと、ベンダーがマップを ACTION_MAIN で起動する。分割が整った直後(起動から約0.4秒後)に free=1 の VIEW(redirect)を送っている。
- 冷えたマップでの5回の結果:
  - 3回: フリードライブで分割できた。
  - 1回: マップが通常の探索画面のままになった。
  - 1回: マップがフリードライブになったが、分割から外れて全画面になった(VIEW が分割の途中に届いたと見られる)。
- 一方、全画面でフリードライブになっているマップを含むセットを開くと、分割でフリードライブが保たれる(毎回成功)。
- codex への相談の第一候補も「全画面で free=1 → 整ってから分割」。

## 作る/変えるファイル(これ以外は触らない)
- write: `app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt`
- write: `app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt`

## 仕様
- `openSet` で、Task 268 の PiP の待ちのあと(PiP の待ちが無ければ即)、`launchSet` の前に次を行う:
  - 条件(すべて満たすとき): セットの `Slot.App` に `MapsFreeDrive.isMaps(pkg)` のものがある。リンク用(`forLink`)ではない。`d.warm.isWarm(マップ)` が false。
  - 処理:
    1. `d.apps.startApp(マップ, false)` を呼ぶ(既存の `AndroidActivityLauncher.startApp` が free=1 の VIEW を全画面で送る)。`d.warm.recordAppLaunch` で結果を記録し、`DiagLog.d("SetRunner", "maps prelaunch started=...")` を残す。
    2. 起動に失敗したら、待たずに今までどおり `launchSet` へ進む。
    3. 成功したら `d.scheduler` で 100ms ごとに `d.watcher.foregroundPkgsSince(開始時刻)` を確かめる。マップが前面になった(含まれた)時刻から 1500ms(定数 `MAPS_SETTLE_MS`)たったら `launchSet` へ進む。開始から 6000ms(定数)たっても前面にならないときも進む。それぞれ `DiagLog` を残す(待った時間を含める)。
    4. この前起動をした開き方では、マップ向けの redirect(`MapsFreeDriveRedirects` の作る、`targetPkg` がマップで `component == null` のもの)を、ready 時・遅れた redirect のどちらでも送らない(分割の途中に VIEW が届いて分割から外れるのを防ぐ)。ほかの redirect は今までどおり送る。
  - 待ちのあいだは、Task 268 の PiP の待ちと同じ扱いにする。開いている途中として重ねて開かない。別の open・HOME・キャンセルで待ちを捨て、後の操作を優先する。幕・通知・失敗の数え方は今までと同じ。
  - 起動時の自動オープン(`openForBoot`)も同じ `openSet` を通るので、同じく前起動する(端末起動直後はマップが冷えているため、ここが主な対象)。
- マップが温まっている(`isWarm` が true)ときは、今までどおり(前起動なし、redirect あり)。

## テスト(SetRunnerTest に追加。範囲を広げない)
- 冷えたマップを含むセット: `startApp(マップ)` が先に呼ばれる。マップが前面になってから 1500ms 後に分割の起動が行われる。ready 時にマップの redirect が送られない。
- 温まったマップ: 前起動なし、従来どおり redirect が送られる。
- 前起動が失敗(`startApp` が false): 待たずに分割へ進む。
- 前面にならないまま 6000ms: 分割へ進む。
- 待ちの最中に別のセットまたは HOME: 古い待ちから分割を起動しない。
- マップを含まないセット: 動きが変わらない。
- 既存テストの期待値は変えない(必要が出たら変えずに止めて報告する)。

## 検証
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.sets.SetRunnerTest'`

## 報告
- 変えた点の要点、テスト結果(件数・失敗)、RULES.md の 1「決めたこと」
