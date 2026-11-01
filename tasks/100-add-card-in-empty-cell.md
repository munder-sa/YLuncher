# Task 100: 「セットを追加」を、最後の行の空いたマスに置き、控えめな見た目にする(ユーザーの判断 2026-10-31)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
v1.6 の Task 089(A3)で、ホームのセットのカード(`ui/home/HomeSetCards.kt`)の「＋ セットを追加」を、格子の後の全幅・高さ 72dp の行にした。実機(1920×1080・密度 238、ワイドのホーム、2 列)で、セットが 5 つあると、3 行のカード + 24dp + 72dp の行が入りきらず、追加の行が画面の下で切れて見える(縦のスクロールで出るが、崩れて見える)。ユーザーの判断: **最後の行に空いたマスがあれば、そこにカードと同じ大きさの点線のカード「＋ セットを追加」を置く。空きが無いときだけ、今の全幅の細い行にする。**

## 変更するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeSetCards.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/HomeSetCardsTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/layout/LayoutMatrixTest.kt`(期待の形が変わる所だけ)

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.ui.panel.*' --tests 'com.gt8d.launcher.ui.layout.*'`

## 仕様
1. `columns`(今の `PanelLayout.homeSetColumns`)と `sets.size` から:
   - `sets.size % columns != 0`(最後の行に空きがある)なら、**最後の行の次のマス**に、セットのカードと同じ幅・同じ高さ(`cardHeight`)・同じ角丸 28dp の、2 の見た目のカードを置き、中央に 2 の中身。全幅の行は出さない。カードの高さの計算は `((areaHeight - 24.dp * (rows - 1)) / rows).coerceIn(136.dp, 160.dp)`(72dp + 24dp の分を引かない)。
   - 空きが無い(`sets.size % columns == 0`。0 件・1 列も含む)なら、今のまま(格子の後に 24dp 空けて全幅・高さ 72dp の行。高さの計算も今のまま)。
   - どちらも `testTag("home_set_add")`・押すと `onAdd()`、`Role.Button` の意味・読み上げ「セットを追加」は同じ。
2. **見た目を控えめにする(ユーザーの判断 2026-10-31: 破線は目立ちすぎる。Material のボタンに破線は無い)**: カードの形も全幅の行も、Material 3 の outlined card / outlined button に合わせ、**破線をやめて実線 1dp の輪郭 `Palette.Control`(#454550。Material の outlineVariant に当たる控えめな色)**、地は透明。中身は `Icons.Default.Add`(24dp)+ 8dp +「セットを追加」(`Dims.BodyText`)を横に並べて中央、色はどちらも `Palette.Muted`(白にしない)。「＋」の文字は使わない。押したときは標準の波紋。角丸は今のまま(カード 28dp・行 28dp)。
3. 縦の中央そろえ・入りきらないときの縦のスクロールは今のまま。
4. パネルのセット表示(狭いパネルで同じ `HomeSetCards` を使う所)も同じ規則(1 列なら常に全幅の行)。

## テスト(足す。既存は弱めない。追加の行の位置・大きさを確かめていた既存のテストは、新しい規則に合わせて置き換え、報告する)
- 2 列で 5 セット: `home_set_add` が最後のカード(5 番目)と同じ行の右のマスにあり、幅・高さがカードと同じ。ワイドのホームの大きさ(1920×1080・密度 238 の dp に換算した領域)で、`home_set_add` の下端が領域の中に収まる(スクロールしなくても全部見える)。
- 2 列で 4 セット・6 セット、0 セット、1 列で 3 セット: 全幅・高さ 72dp の行(今の形)。
- 押すと `onAdd` が1回。
- 見た目: 文字「セットを追加」(「＋ セットを追加」ではない)と `Add` のアイコンが `Palette.Muted`、輪郭は実線(破線の `PathEffect` を使わない)。読み上げは「セットを追加」。

## この Task でしないこと
- カードの中身・高さの範囲(136〜160dp)の変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、置き換えた既存テスト、**決めたこと**。
