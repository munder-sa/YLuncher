# Task 076: 左パネルのセットを開く(v1.5 Task 3)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
v1.5(設計書 `docs/superpowers/specs/2026-10-31-v1.5-left-panel-design.md` §4.1〜4.3、§5 の幕、計画 Task 3。この指示書が優先)。Task 074 で `ScreenSet.left: Slot`、`Side`、`panelSide`・`slotAt`・`appPkgAt` ができている(`config/Model.kt` を読むこと)。実機で、純正の分割 E に中継アプリ `com.gt8d.launcher.panel` を**左**として渡しても(`"com.gt8d.launcher.panel|com.google.android.youtube"`、`2:3`)分割が成立し、PanelActivity が左の 40% に出ることを確認済み。比率は左:右の順でそのまま効く。

## 変更するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt`
- `app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.sets.*' --tests 'com.gt8d.launcher.boot.*'`

## 仕様
1. `openSet`: `isPanelRight` をやめ、`val panelSide = set.panelSide` を使う。パネルの側のパッケージは `d.panelHostPkg`、反対側はそのセットのアプリ。`left`・`right` は実際の左右(左パネルなら `left = d.panelHostPkg`)。`Opening(left, right, startMs)`・`lastOpenedPair = left to right` もこの順。
2. 「アプリ側」を `val appPkg`(パネルの反対側のアプリ。両方アプリのときは今のまま左)として、次を左右対称にする:
   - 未インストール: パネルを含むセットで `appPkg` が入っていない → ホームへ(今の「左が無く右がパネル」と同じ扱い)。中継が入っていない → `appPkg` を全画面(今の「右がパネルで中継が無い」と同じ扱い)。両方アプリのセットは今の規則のまま。
   - resize 判定: パネルの側は判定を免除(今の右パネルと同じ)。
   - `launcher == null`(LeftOnly)・`LaunchResult.Failed`(`handleLaunchFailure`)・resize 不可: パネルを含むセットでは `appPkg` を全画面(今の右パネルでは左のアプリ=appPkg なので結果は同じ)。両方アプリは今のまま左。
3. `waitBoth`: 引数を `isPanelRight: Boolean` から `panelSide: Side?` にし、パネルを含むとき(左右どちらでも)は「アプリ側の Resume + `panelResumedInSplit` の合図」で成立、を使う(今の右パネルの判定の左右対称版)。`bothResumed(left, right…)` の呼び方、`retry skipped`(437 行付近の `left in seen || ...`)、呼び直し(443 行付近)も、パネルの側を除いた「アプリ側」で判定するよう直す。両方アプリは今のまま。
4. 幕・起動画面の予告の入力(593 行付近): `CurtainTarget.left/right` を `slotAt(Side.Left)`・`slotAt(Side.Right)` から作る(Panel → `SlotPreview.Panel`、App → `SlotPreview.App(pkg, label)`)。ホームの予告(578・587 行付近)の右パネル固定も同様に。
5. `fun panelLaunched(pkg: String)` を足す(public)。分割中(`lastOpenedPair` があり、その片方が `d.panelHostPkg`)なら、`lastOpenedPair` のパネルでない側を `pkg` に替え、`DiagLog.d("SetRunner", "panelLaunched pkg=$pkg pair=$lastOpenedPair")`。それ以外は何もしない。**呼び出し元の配線はこの Task ではしない**(後の Task 079)。
6. 後の Task 079 のために、空の口を2つ足す: `private fun onSetReady(set: ScreenSet)`(パネルを含むセットが `waitBoth ready` で幕が `Ready` になった直後に呼ぶ)、`private fun onSetEnded(reason: String)`(ホームへ・次のセットの開始・`openForLink` の開始・失敗/Safety/逃がし先で呼ぶ)。中身は `DiagLog.d("SetRunner", "session ready id=…")`・`"session ended reason=…"` のログだけ。呼ぶ場所を報告に列挙する。
7. 右パネル・両方アプリ・リンク(`openForLink`)の今の動きとログの文言は変えない(`openSplit left=… right=…`・`waitBoth ready left=… right=…` の形を保つ)。

## テスト(既存の `SetRunnerTest` の偽物の作り方に合わせて足す。既存は弱めない)
- `leftPanel_passesPanelHostOnLeft`: `ScreenSet("s", Slot.Panel, Slot.App("y"), SplitRatio.R4_6)` を開くと、偽の vendor に `open(panelHostPkg, "y", R4_6)`、`lastOpenedPair == panelHostPkg to "y"`。
- `leftPanel_waitBoth_readyWithPanelSignal`: "y" の Resume と `panelResumedInSplit()` で `waitBoth ready`、幕が Ready で外れる。
- `leftPanel_retrySkipped_whenProgress`: 右パネルの同じテストの左右対称版。
- `leftPanel_appMissing_goesHome`、`leftPanel_panelHostMissing_opensAppFullscreen`、`leftPanel_splitUnavailable_opensAppFullscreen`、`leftPanel_startFailed_opensAppFullscreen`(全画面は "y")。
- `curtainInput_leftPanel`: 幕の `left == SlotPreview.Panel`、`right` は "y" の App。
- `panelLaunched_updatesPartner_leftAndRight`: 左パネルで開いた後 `panelLaunched("maps")` → `panelHostPkg to "maps"`。右パネルでは `"maps" to panelHostPkg`。分割でないときは変わらない。
- 既存の右パネル・両方アプリ・リンク・起動時(boot)のテストはそのまま通る。

## この Task でしないこと
- PanelActivity・PanelScreen など UI の変更、入れ替えの保存、リンクの変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、`onSetReady`・`onSetEnded` を呼ぶ場所の一覧、**決めたこと**。
