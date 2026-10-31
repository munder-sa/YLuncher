# Task 068: セットのカードの「パネル」をアイコンにし、セットの名前を中央寄せにする

最初に `tasks/RULES.md` を読み、従うこと。

## 背景(ユーザーの判断 2026-10-31)
- 画面セットのカード(`HomeSetCards`。全画面のホームと、狭いパネルのセット表示の両方で使う)で、右がパネルのとき、今は文字「パネル」を白い枠で囲んで出している。これを、モック `build/mock/panel-icon-mock.html` の**案B(右側の画面)**のアイコンにする(ユーザーが選んだ)。
- カードの下のセットの名前(例「YouTube | パネル」)を**中央寄せ**にする。

## 変更・作成するファイル(これで全部)
- create `app/src/main/java/com/gt8d/launcher/ui/home/PanelIcon.kt`
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeSetCards.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/HomeSetCardsTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.ui.panel.*' --tests 'com.gt8d.launcher.ui.layout.*'`

## 仕様
1. `PanelIcon.kt`: モックの `<symbol id="panel-b">` と**同じ図形**を Compose の `ImageVector`(`val PanelIconVector: ImageVector`、24x24 の viewport、`ImageVector.Builder` と `path { ... }`)に移す。
   - 地: 白(#FFFFFF)の角丸の正方形 `M5 0H19Q24 0 24 5V19Q24 24 19 24H5Q0 24 0 19V5Q0 0 5 0Z`
   - 線(stroke #303036、幅 1.5、塗りなし): `M7.5 4.5H16.5Q18 4.5 18 6V18Q18 19.5 16.5 19.5H7.5Q6 19.5 6 18V6Q6 4.5 7.5 4.5Z M14.5 4.5V19.5 M6 10.5H14.5`
   - 塗り(#303036): `M8.5 13H12Q12.5 13 12.5 13.5V16.5Q12.5 17 12 17H8.5Q8 17 8 16.5V13.5Q8 13 8.5 13Z`
   - (Q は二次ベジェ。`quadTo` で移す。`PathParser().parsePathString(...)` を使ってもよい。)
   - `@Composable fun PanelIcon(modifier: Modifier = Modifier)` = `Image(PanelIconVector, contentDescription = "パネル", modifier)`。
2. `HomeSetCards.SetBlock`: `app == null`(パネル)のとき、白い枠(`border`)と文字「パネル」をやめ、アプリのアイコンと同じ 56dp で `PanelIcon` を中央に出す。枠の地(`Palette.Background0`・角丸 12dp)はアプリの枠と同じ。
3. セットの名前(`OneLineText("${left.label} | ${right?.label ?: "パネル"}")`)を中央寄せにする: `Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { OneLineText(...) }`。長い名前は今までどおり「…」で切れる(はみ出さない)こと。
4. テスト(既存は弱めない。「パネル」の文字を探す既存の期待値はアイコンに合わせる。どれを変えたか報告する):
   - パネルのセットで、`contentDescription = "パネル"` の要素が有り、その大きさが 56dp。文字だけの「パネル」(枠の中のテキスト)は無い。
   - 名前の中央寄せ: 名前の Text の中心の x と、カードの中心の x の差が 1dp 以内(短い名前で)。
   - 長い名前(40文字)で、名前が1行・カードの中に収まる。

## この Task でしないこと
- 他の画面・設定画面の「パネル」表示の変更。アプリのアイコンの変更。git のコミット等。

## 報告
変更点、テストの結果、**決めたこと**。

## 追記(司令塔の判断)
- 今の作業ツリーの変更を土台に続ける。
- 長い名前のテストで求めるのは「1行(lineCount == 1)で、名前の Text の表示範囲がカードの中に収まる(テキストノードの boundsInRoot の左右がカードの内側。はみ出しは `clipToBounds` 等で切れていればよい)」まで。`isLineEllipsized` の確認は指示書に無い追加なので外す(Robolectric の文字の配置では省略記号の判定が実機と一致しないことがある)。代わりに、名前の Text の幅がカードの内側の幅以下であることを確かめる。
