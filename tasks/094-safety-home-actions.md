# Task 094: 安全モードのホームに「純正ランチャーを開く」「安全モードを解除」を出す(v1.6 B4)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景(ユーザーの判断 2026-10-31)
v1.6 設計書 `docs/superpowers/specs/2026-10-31-v1.6-ux-design.md` §4 B4。安全モード(`safety/SafetyGuard.isFallbackActive`。YLuncher が繰り返し落ちたときに純正ランチャーへ逃がす)のとき、今の `HomeActivity` は onCreate で説明の文字だけの画面を出し、onResume で**すぐに純正ランチャーを開く**ため、利用者は解除の手段に気づけない。設定には解除の処理(`settings/SafetyRecoveryScreen.kt` の `safetyRecover(context)`)がある。
ユーザーの判断: **自動で純正へ切り替えず**、説明と2つのボタンを出す。

## 変更するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeActivity.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/HomeActivityTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.safety.*' --tests 'com.gt8d.launcher.settings.*'`

## 仕様
1. 安全モードのときの画面(onCreate の分岐)を: 中央に「安全のため純正ランチャーを使っています」(今の文言、`Dims.BodyText`)、その下に2つのボタン(どちらも 72dp 以上、幅 360dp 程度、縦に 16dp 空けて): 「純正ランチャーを開く」(testTag `safety_open_stock`、今の `stockLauncherIntent()` を開く)、「安全モードを解除」(testTag `safety_unlock`、`safetyRecover(this)` → 今の「解除してホームへ」と同じ後の動き。HomeActivity は onResume の既存の分岐で `recreate()` される)。ボタンは既存の設定の部品(`SettingsButton` など)か、同じ見た目の Compose で。この画面は軽いまま(重い初期化・帯・セットの起動をしない。今と同じ)。
2. onResume の安全モードの分岐で、純正ランチャーを**自動で開くのをやめる**(`startActivity(stockLauncherIntent())` を消す)。帯の起動・セットの起動をしないのは今のまま。
3. 安全モードでないときの動きは変えない。

## テスト(足す。既存は弱めない。自動で純正へ行くことを確かめていた既存のテストは、この仕様に合わせて置き換え、報告する)
- 安全モードで HomeActivity を開くと `safety_open_stock`・`safety_unlock` が表示され(72dp 以上)、純正ランチャーは自動で開かれない(Robolectric の `shadowOf(activity).nextStartedActivity` が null)。
- `safety_open_stock` を押すと純正ランチャーの Intent が開かれる。
- `safety_unlock` を押すと安全モードが解除され(`SafetyGuard.isFallbackActive == false`)、作り直して通常のホームになる。

## この Task でしないこと
- 安全モードに入る条件の変更。他の画面の変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、置き換えた既存テスト、**決めたこと**。
