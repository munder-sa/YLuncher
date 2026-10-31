# Task 089: 「＋ セットを追加」を細い行に、セットの呼び名を番号つきでそろえる(v1.6 A3・A4)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
v1.6 設計書 `docs/superpowers/specs/2026-10-31-v1.6-ux-design.md` の §3 A3・A4 と §2、計画 `docs/superpowers/plans/2026-10-31-v1.6-ux.md` の Task 089 を読むこと(この指示書が優先)。
- 今、画面セットのカード(`ui/home/HomeSetCards.kt`。ホームとパネルのセット表示で共用)の最後に「＋ セットを追加」が、セットと同じ大きさ(136〜160dp)の点線のカードとして1枠を占める。行数(`rows`)と高さ(`cardHeight`)の計算にも入っている。
- セットの名前は、カードでは「YouTube | パネル」、幕(`sets/SetRunner.kt` の `CurtainTarget(setName = "セット$index")` 付近)では「セット1」、起動画面(`ui/home/BootScreen.kt`)では別の形。

## 変更・作成するファイル(これで全部)
- create `app/src/main/java/com/gt8d/launcher/ui/home/SetLabel.kt`
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeSetCards.kt`
- `app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt`(幕・起動の予告の名前だけ)
- `app/src/main/java/com/gt8d/launcher/ui/home/BootScreen.kt`(名前の出し方だけ。必要なら)
- create `app/src/test/java/com/gt8d/launcher/ui/home/SetLabelTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/HomeSetCardsTest.kt`
- `app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/BootScreenTest.kt`(あれば)

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.ui.panel.*' --tests 'com.gt8d.launcher.sets.*' --tests 'com.gt8d.launcher.boot.*' --tests 'com.gt8d.launcher.ui.layout.*' --tests 'com.gt8d.launcher.curtain.*'`

## 仕様
1. `SetLabel.kt`: `fun setLabel(set: ScreenSet, labelFor: (String) -> String): String` = 「<左の名前> | <右の名前>」(Panel は「パネル」。`slotAt(Side.Left/Right)` を使う)、`fun numberedSetLabel(index: Int, set: ScreenSet, labelFor: (String) -> String): String` = 「$index  ${setLabel(...)}」(番号の後は全角でなく半角スペース2つ)。
2. **A3**: `HomeSetCards` の追加カードを、セットのカードの格子から外し、格子の**後に**高さ 72dp・全幅の行として置く(白い点線の角丸の枠、中央に「＋ セットを追加」、`Dims.BodyText`、testTag `home_set_add` は今のまま)。`rows` はセットの数だけで計算し、`cardHeight` の計算も追加の行(72dp + 間隔)を除いた高さで行う。少ないときの縦の中央そろえは、カードと追加の行をまとめて中央にそろえる。
3. **A4**:
   - カードの名前(今の中央寄せの名前)の前に番号を付ける: 「1  YouTube | パネル」。番号の部分は `Palette.Muted`、名前は白(`AnnotatedString` などで)。大きさは今のまま(20sp)。
   - 幕・起動画面の予告のセット名を `numberedSetLabel(index, set, d.labelFor)` にする(ホームは「ホーム」、リンクは「リンクを開く」のまま)。
4. 他の見た目は変えない。

## テスト(足す。既存は弱めない。番号つきの名前・追加の行に由来する期待値だけ合わせ、報告する)
- `SetLabelTest`: 右パネル・左パネル・両方アプリの名前、番号つき。
- `HomeSetCardsTest`: 追加の行の高さが 72dp・全幅で、最後のカードより下。セット4つ(2列で2行)で、カードの高さが追加の行を除いた領域で決まる。少ないとき(1つ)はカードと追加の行がまとめて縦の中央。名前が「1  左アプリ | パネル」。既存の `threeRows_fitWithoutClipping` などは新しい作りで意味を保つように合わせる。
- `SetRunnerTest`: 幕の `setName` が「1  YouTube | パネル」の形(偽の `labelFor` に合わせる)。

## この Task でしないこと
- 帯・ドック・ミニプレイヤー・設定画面の変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、合わせた既存の期待値、**決めたこと**。
