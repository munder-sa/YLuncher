最初に `tasks/RULES.md` を読み、従う。

# Task 236: 分割中のパネルの上の帯を消す(やり直し)

## 背景(司令塔が実機 GT8D と AOSP の android15-release のソースで確認済み)
- Task234 の `enableEdgeToEdge` では帯は消えなかった(実機で `android:id/content` は `0,36-…` のまま、上端の色は `#303030` のまま)。
- 原因: 分割中は WindowManager がステータスバーを強制的に「消費させる種類」にする(`InsetsPolicy.updateSystemBars` で、分割・フリーフォームのとき `setForcedConsumingTypes(statusBars | navigationBars)`。実機のパネルの InsetsState でも `type=statusBars … flags=FORCE_CONSUMING`)。
- 標準の画面の枠(`android:id/content` の親の `LinearLayout`、`screen_simple.xml` で `fitsSystemWindows="true"`)が、この強制分を余白(上 36px)として受け取っている。実機の部品の配置で、`LinearLayout` は `0,0-…`、その子の `content` が `0,36-…`(=LinearLayout の上の padding)。
- 同じ分割の YouTube(Morphe)は AppCompat の枠を使っていて、`content` が `0,0-…` で上まで描けている。
- ユーザーと合意: **帯を消してパネルを上端まで描く**。システムのステータスバーは隠さない(右上のアイコンはパネルの上に重なってよい。他のアプリと同じ)。ホームは変えない。

## 変えること(`PanelActivity.kt`)
1. Task234 で入れた `enableEdgeToEdge(…)` の呼び出しと、その 2 つの import(`SystemBarStyle`・`enableEdgeToEdge`)を消す(効果が無かったため、元に戻す)。
2. `setContent { … }` の直後で、`android.R.id.content` の親の View の `fitsSystemWindows` を `false` にし、`requestApplyInsets()` を呼ぶ。
   ```kotlin
   (findViewById<View>(android.R.id.content).parent as? View)?.let {
       it.fitsSystemWindows = false
       it.requestApplyInsets()
   }
   ```
   なぜこうするかを、日本語で短いコメントに書く(分割中はステータスバーが強制的に消費され、標準の枠がそれを上の余白にするため)。
- それ以外は変えない。Compose の側(`WindowInsets` の余白など)は足さない。

## テスト(`PanelActivityTest.kt` に 1 件追加。既存のテストは変えない)
- `contentRoot_doesNotFitSystemWindows`: `Robolectric.buildActivity(PanelActivity::class.java).create().start().resume().visible()` の後、`activity.findViewById<View>(android.R.id.content).parent` が View で、その `fitsSystemWindows` が `false` であること。
- 変更前のコードでは、この親の `fitsSystemWindows` が `true` で落ちることを先に確かめる。変更前から `false` なら、推測で書き方を変えずに止めて 3 点セットで報告する。

## 作る・変えるファイル(これ以外は変えない)
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelActivity.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/PanelActivityTest.kt`

## 使ってよいコマンド
    /home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.panel.PanelActivityTest'

## 報告
- 実行したコマンドと結果(件数・失敗数)。「変更前は落ちる」を確かめた結果。変更の要約、「決めたこと」。
