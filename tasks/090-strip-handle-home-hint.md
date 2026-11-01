# Task 090: 画面の端の帯に取っ手を出し、ホームに操作の案内を出す(v1.6 A5)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
v1.6 設計書 `docs/superpowers/specs/2026-10-31-v1.6-ux-design.md` の §3 A5 と §2、計画 Task 090 を読むこと(この指示書が優先)。
- 画面の左右の端に帯(`edge/EdgeStripService.kt`。幅 `gestureConfig.stripWidthPx`、実機は 48px)があり、左帯のスワイプ=次のセット、右帯=前のセット、長押し=ホーム(`edge/EdgeGesture.kt`)。帯の View は普段 `alpha = 0`(透明)で、触ると緑(`Palette.Accent` の 30%)が光る。帯は見た目では分からない。

## 変更・作成するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/edge/EdgeStripService.kt`
- create `app/src/main/java/com/gt8d/launcher/ui/home/HomeHintStore.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt`(ワイドのホーム `HomeWideContent` に案内の行を足すだけ)
- create `app/src/test/java/com/gt8d/launcher/ui/home/HomeHintStoreTest.kt`
- `app/src/test/java/com/gt8d/launcher/edge/EdgeStripServiceTest.kt`(無ければ create にして allow を直さず報告)
- `app/src/test/java/com/gt8d/launcher/ui/panel/PanelScreenTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.edge.*' --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.ui.panel.*' --tests 'com.gt8d.launcher.ui.layout.*'`

## 仕様
1. **取っ手**: 帯の View を、背景(触ったときに光る面)と、取っ手(子の View)の2層にする(`FrameLayout` など)。取っ手は帯の縦の中央、幅 4px・高さ 96px・角丸(半径 2px)、白、**不透明度 0.25 で常に表示**。帯のうち画面の内側寄りに置く(左帯なら帯の右端から 8px、右帯なら帯の左端から 8px)。帯の幅・タッチの判定・光り方の時間は変えない。光る色は緑ではなく**白の 30%**(`Color.White.copy(alpha = 0.3f)`)。設定の「光る演出 OFF」のときも取っ手は出す。
2. **案内**: `HomeHintStore(context)`(`SharedPreferences` "home_hint"、キー "shown_count")に `fun shouldShow(): Boolean`(count < 5)と `fun recordShown()`(+1、5 で止める)。ワイドのホーム(`HomeWideContent`)の時計の行の下(セットのカードの上)に、`shouldShow()` のときだけ1行「端からスワイプでセットを切り替え・長押しでホーム」(`Dims.BodyText`、`Palette.Muted`、1行、はみ出すときは省略、testTag `home_hint`)。ホームの画面が表示されるたびに1回 `recordShown()`(同じ表示の中で何度も数えない。`LaunchedEffect(Unit)` で1回)。設定の「デフォルトに戻す」で回数は戻さない。
3. 案内の行を出したときもカードが切れないこと(カードの領域の高さの計算は今の仕組みのまま、案内の分だけ領域が小さくなる)。

## テスト(足す。既存は弱めない)
- `HomeHintStoreTest`: 初めは true、`recordShown` を5回で false、6回目以降も false のまま。
- `PanelScreenTest`(ワイドのホーム): 回数 0 で `home_hint` が出る、回数 5 で出ない、表示で回数が1増える。
- 帯: 取っ手の View が存在し、不透明度 0.25・幅 4px・高さ 96px、光る色が白(既存の EdgeStripService のテストの作り方に合わせる。View を作る処理を関数に分けてテストしてよい)。

## この Task でしないこと
- 帯の操作の意味・判定の変更。ドック・カード・幕の変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、**決めたこと**。
