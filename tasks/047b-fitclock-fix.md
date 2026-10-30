# Task 047b: 時計(FitClock)が測った大きさと描いた大きさが合わない問題を直し、Task 047 を完了させる

最初に `tasks/RULES.md` を読み、従うこと。`tasks/047-responsive-layout.md` の仕様・テストがそのまま有効(このブランチには 047 の途中までの実装がある)。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

## 状況
047 の実装で、`FitClockTest` 12件と `LayoutMatrixTest` 76件が、時計の確認(`hasVisualOverflow == true`)で失敗している。
報告: 表示サイズ 247×112px に対して、段落の幅が 360px と報告される。ほかのテスト(帯の余白、状態の保持、作り直しの復元、既存のテスト)は通っている。

## 司令塔の見立て(確かめてから使うこと)
`FitClock` は、測るときは素の `TextStyle(fontSize, Bold, "tnum")` で `rememberTextMeasurer().measure` し、描くときは Material3 の `Text` を使っている。
Material3 の `Text` は `LocalTextStyle.current`(テーマの `bodyLarge`。**行の高さ 24sp**・字間・`platformStyle`・`lineHeightStyle` を含む)と渡した様式を合成するため、**測った様式と描いた様式が違う**可能性が高い。
特に、行の高さ 24sp のまま 96sp の文字を描くと、行の高さを超えて縦にはみ出す。

## やること
1. まず原因を確かめる: `FitClockTest` の1件(例 360x400 scale=1)で、測ったときの `TextLayoutResult`(`size`、`multiParagraph.width/height`、`layoutInput.style`)と、描いた `clock` の `TextLayoutResult` を両方 `println` し、違いを報告に書く(確かめたら `println` は消す)。
2. 直す: **測る様式と描く様式を、同じ1つの `TextStyle` の値にする**。描画は `BasicText`(または `Text` に完全な様式を渡す)にし、行の高さは文字の大きさに合わせる(`lineHeight = TextUnit.Unspecified` など。`LineHeightStyle` を使うならそれも同じ値で測る)。
   測るときの幅の制約(`Constraints(maxWidth = …)`)も描くときと同じにする。原因が見立てと違えば、確かめた原因に合わせて直し、報告に書く。
3. `FitClock.kt` 以外を変える必要があれば、`tasks/047-responsive-layout.allow` に宣言済みのファイルの範囲で、最小限にする。テストは弱めない(RULES.md の 4)。

## 作成・変更してよいファイル
`tasks/047-responsive-layout.allow` と同じ(このファイルの .allow に同じ内容を書いてある)。

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.ui.layout.FitClockTest`
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.ui.* --tests com.gt8d.launcher.edge.*`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 手順
1. 上の 1 で原因を確かめる。2. 直して `FitClockTest` を通す。3. `ui.*`・`edge.*` のテストを通す(直しは最大3回)。4. 通ったら `assembleDebug`。

## この Task でしないこと
- 047 の仕様の変更、テストの期待値の変更。全テストの実行、実機の操作、git のコミット等。

## 報告
1 で確かめた違い、実行したコマンドと結果(件数・失敗件数、assembleDebug)、変更したファイル、**決めたこと**。
