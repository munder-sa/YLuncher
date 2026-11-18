最初に tasks/RULES.md を読み、従う。

# Task 271: 冷えたマップへの遅らせた送信で、パネルの位置が一時的に外れていたら少し待つ

## 背景(実機、2026-11-18、2.3.9-beta4)
- Task 270 で、冷えたマップへのフリードライブは、分割の ready から 2500ms 後に1回だけ送るようにした。分割は6回とも保たれた。Apple Music+マップは3回とも成功した。
- マップ+パネル(パネルは右)では、3回中2回が `maps redirect discarded reason=panel placement=null` で送られなかった。ログでは ready の直後に `placement=null` になり、約3秒後(ready から 3.0 秒)に `placement=Right` へ戻っている。
  - 例: 19:27:29.286 ready → 29.673 null → 31.790 discarded → 32.339 Right

## 作る/変えるファイル(これ以外は触らない)
- write: `app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt`
- write: `app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt`

## 仕様
- `delayMapsRedirect` で、2500ms 後の確認でパネルの位置が Left/Right でないとき、すぐには捨てない。200ms ごとに確かめ、Left/Right になった時点で送る。
- 待つのは最大 3000ms(定数 `MAPS_PLACEMENT_WAIT_MS`)まで。超えたら今までどおり捨て、理由をログに残す。
- 待ちのあいだも、HOME・別のセット・キャンセル・`operationGeneration` の変化で送らないことは今までどおり。マップ宛ては合計1回までも同じ。
- パネルの無いセットと、位置が最初から Left/Right の場合の動きは変えない。

## テスト(SetRunnerTest。範囲を広げない)
- 2500ms の時点で null、その 600ms 後に Right → そこで1回送る。
- 2500ms の時点から 3000ms 以上 null または Full のまま → 送らない。
- 待ちの最中に HOME → 送らない。
- 既存テストの期待値は変えない。`coldMaps_panelLeavesSplitDuringDelay_discardsRedirect` が新仕様と合わない場合は、「3000ms 待っても戻らなければ捨てる」形に直してよい。

## 検証
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.sets.SetRunnerTest'`

## 報告
- 変えた点、テスト結果(件数・失敗)、RULES.md の 1「決めたこと」
