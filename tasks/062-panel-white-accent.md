# Task 062: パネルとホームの緑(Accent)を白にする

最初に `tasks/RULES.md` を読み、従うこと。

## 背景(ユーザーの判断 2026-10-31)
ホーム画面(全画面)とパネル(分割の右)で緑(`Palette.Accent` = 0xFF8FE63B)を使っている文字・線・シークバー・ボタンを、すべて白にする。設定画面・幕・起動画面・帯は今回は変えない(緑のまま)。

## 変更するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/ui/theme/Theme.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/SeekBar.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/MediaCard.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/IconGrid.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelContent.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt`
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeSetCards.kt`
- `app/src/test/java/com/gt8d/launcher/ui/theme/PaletteTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/HomeSetCardsTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.ui.theme.* --tests com.gt8d.launcher.ui.home.* --tests com.gt8d.launcher.ui.panel.*`

## 仕様
1. `Palette` に `val PanelAccent = Color(0xFFFFFFFF)` を足す(コメント1行: ホームとパネルの強調色。白)。`Accent` と `darkColorScheme` の `primary` は変えない。
2. 次の `Palette.Accent` を、すべて `Palette.PanelAccent` に置き換える(これで全部。`BootScreen.kt` は対象外で緑のまま。それ以外の ui/panel と ui/home に `Palette.Accent` は残らないこと):
   - `SeekBar.kt` の `thumbColor` と `activeTrackColor`
   - `MediaCard.kt` の操作ボタンの `tint`
   - `IconGrid.kt` の「＋」の文字色
   - `PanelContent.kt` の「許可が必要」の文字色
   - `PanelScreen.kt` の「許可が必要」の文字色
   - `HomeSetCards.kt` の「＋ セットを追加」の点線の枠と文字、今のセットの枠、空きの「パネル」の枠と文字
3. シークバーの未再生部分(`inactiveTrackColor`)が白と見分けにくい場合に備え、`SeekBar.kt` の `SliderDefaults.colors` に `inactiveTrackColor = Palette.PanelAccent.copy(alpha = 0.24f)` を足す。
3b. 同じ `SliderDefaults.colors` に、操作中の色(`thumbColor` 以外で緑が出うるもの、例: `activeTickColor`・`inactiveTickColor`)を、Material3 の API にあるものだけ白系にそろえる。テーマの `primary` から波紋に緑が出る場合は、`SeekBar` を `CompositionLocalProvider(LocalRippleConfiguration ...)` など、使っている Compose のバージョンにある方法で白の波紋にする。無い・不要なら何もしない(どちらにしたかを「決めたこと」に書く)。
4. `PaletteTest` に `assertEquals(Color(0xFFFFFFFF), Palette.PanelAccent)` を足す。既存の確認は消さない。

5. **ホームのカードのはみ出しを直す**(ユーザーの指摘: 3段目の「＋ セットを追加」が下で切れる)。`HomeSetCards` のカードの高さ 160dp 固定をやめ、行数を `rows`、領域の高さを `areaHeight` として、
   `cardHeight = ((areaHeight - 24.dp * (rows - 1)) / rows).coerceIn(136.dp, 160.dp)` にする(セットのカードと「＋ セットを追加」の両方)。収まらないとき(下限 136dp でも超える)だけ、今のスクロールのまま。少ないときの縦の中央そろえは今のまま。
6. `HomeSetCardsTest` に足す: `threeRows_fitWithoutClipping` — `host(height = 480, count = 5)`(2列で3行)で、`home_set_s1` の top >= area.top、`home_set_add` の bottom <= area.bottom(許容 1px)。既存のテストは変えない。

## この Task でしないこと
- 設定画面・幕(curtain)・BootScreen・EdgeStripService の色の変更。大きさ・配置の変更。テストの弱体化。git のコミット等。

## 報告
変更点、テストの結果、**決めたこと**。
