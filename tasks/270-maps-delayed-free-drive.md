最初に tasks/RULES.md を読み、従う。

# Task 270: 冷えたマップへのフリードライブは、分割が落ち着いてから1回だけ送る(Task 269 の前起動をやめる)

## 背景(実機、2026-11-18、PiP は appops で ignore)
- Task 269(先に全画面でフリードライブ → 分割)は 4回中2回の成功だった。失敗は、分割が ready の約1秒後に解けて HOME に戻った回と、マップの枠が黒いままの回。全画面のフリードライブから分割に入る動きが不安定。
- 冷えたマップを分割に入れ、通常の地図で落ち着いた(約8秒後)ところへ VIEW(free=1、NEW_TASK|SINGLE_TOP)を1回送ると、分割のままフリードライブに切り替わった(Apple Music+マップ、2回中2回)。
- 分割 ready の直後(起動から約0.4秒)に送ると、ときどき通常の地図のまま、または分割から外れる。
- codex・Gemini とも、「初回の送信を遅らせて1回だけ」を次の候補に挙げた。

## 作る/変えるファイル(これ以外は触らない)
- write: `app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt`
- write: `app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt`

## 仕様
1. Task 269 の前起動(`prelaunchMaps` と、それに伴う「前起動した回は Maps の redirect を送らない」処理)を取り除く。Task 268 の PiP の待ちは残す。
2. マップの redirect(`MapsFreeDrive.isMaps(targetPkg)` かつ `component == null`)の扱いを、開いた時点でのマップの温度で分ける。温度は open の開始時点の `d.warm.isWarm(マップ)` で決める。
   - 温まっている: 今までどおり(ready 時に即送る。Safety 後の遅れた送信も今までどおり)。
   - 冷えている: ready(通常の ready、または Task 266 の遅れた ready)になっても即は送らない。その時刻から `MAPS_COLD_REDIRECT_DELAY_MS`(定数、2500ms)後に1回だけ送る。
     - 送る直前に、`d.watcher.foregroundPkgsSince(ready の時刻)` でマップがまだ前面に見えているかを確かめる(パネルのセットはパネルの placement も既存の方法で確かめられるなら確かめる)。見えていなければ送らずに捨て、`DiagLog` を残す。
     - 同じセットの操作で、マップ宛ては合計1回まで。通常の ready と遅れた ready が両方来ても、1回だけ送る。
     - 待ちのあいだに別のセット・HOME・キャンセル・`operationGeneration` の変化が起きたら送らない。
     - ほかのアプリの redirect は、今までどおり ready で即送る。
     - 幕(curtain)を閉じる時刻は変えない(今までどおり ready で閉じる)。
   - ログ: `DiagLog.d("SetRunner", "maps redirect delayed ms=2500")`、送ったら `"maps redirect target=... sent=..."`、捨てたら理由付きで残す。

## テスト(SetRunnerTest。範囲を広げない)
- Task 269 で足したテストのうち、前起動に関するものは、この仕様に合わせて置き換えてよい(Task 269 の仕様自体を取り下げるため)。それ以外の既存テストの期待値は変えない。
- 冷えたマップ: ready の時点では送らず、2500ms 後に1回だけ送る。幕は ready で閉じる。
- 冷えたマップ: 2500ms のあいだに HOME または別のセット → 送らない。
- 冷えたマップ: 送る直前にマップが前面に見えない → 送らない。
- 冷えたマップ: Safety の後の遅れた ready → 2500ms 後に1回だけ(重複しない)。
- 温まったマップ: 今までどおり ready で即送る。

## 検証
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.sets.SetRunnerTest'`

## 報告
- 変えた点の要点、テスト結果(件数・失敗)、RULES.md の 1「決めたこと」
