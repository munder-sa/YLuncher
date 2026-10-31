# Task 104: パネルのセット表示を横長カードに、角丸の段をそろえ、シークをドラッグだけに(v1.6 段階 C の C1・C4・C5)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
v1.6 設計書 `docs/superpowers/specs/2026-10-31-v1.6-ux-design.md` §5「段階 C の決定」の C1・C4・C5。ユーザーが選んだ見た目は `/mnt/f/YLuncher/build/mock/stage-c/c1-panel-sets.png` の案A(HTML は `c1-panel-sets.html`)。今の実機は `/mnt/f/YLuncher/build/mock/stage-c/ref/panel-sets.png`(狭いパネルでホームと同じ大きなカードが3つしか見えない)。

## 変更・作成するファイル(これで全部)
- create `app/src/main/java/com/gt8d/launcher/ui/panel/PanelSetCards.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelContent.kt`(セット表示で `HomeSetCards` の代わりに `PanelSetCards` を使う所だけ)
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeSetCards.kt`(角丸だけ)
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeMiniPlayer.kt`(角丸だけ)
- `app/src/main/java/com/gt8d/launcher/ui/panel/MediaCard.kt`(角丸だけ)
- `app/src/main/java/com/gt8d/launcher/ui/panel/SeekBar.kt`
- create `app/src/test/java/com/gt8d/launcher/ui/panel/PanelSetCardsTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/PanelContentTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/MediaCardTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/HomeMiniPlayerTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/HomeSetCardsTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/layout/LayoutMatrixTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.ui.panel.*' --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.ui.layout.*'`

## 仕様
1. `PanelSetCards(sets, appInfo, currentIndex, onOpen: (Int) -> Unit, onAdd: () -> Unit, modifier)`(C1):
   - 縦に並ぶ横長のカード: 高さ 104dp、間隔 8dp、角丸 24dp、地 `Palette.Background1`、全幅。中: 左に左右のアプリのアイコン(各 48dp、4dp 空け。パネルの側は `PanelIcon` 48dp、左なら反転。入っていないアプリは頭文字の四角 `Palette.Control`)、16dp 空けて「<番号>  <名前>」(番号 `Palette.Muted`・名前 白、`Dims.BodyText`、1 行で省略。名前は `setLabel` を使う)。今のセット(`currentIndex == 番号`)は白い輪郭 3dp。押すと `onOpen(番号)`。タグは `panel_set_<id>`、選択の意味(`selected`)を付ける。
   - 最後に「セットを追加」の行(高さ 72dp、全幅、角丸 24dp、実線 1dp `Palette.Control`、地は透明、`Icons.Default.Add` + 8dp +「セットを追加」、どちらも `Palette.Muted`。Task 100 のホームの追加と同じ見た目)。タグ `panel_set_add`、押すと `onAdd()`。
   - 縦にスクロール(入りきらないとき)。少ないときは上寄せ(縦の中央にしない)。
   - `PanelContent` のセット表示(`state.view == PanelView.Sets`)で、今の `HomeSetCards(...)` と高さの計算(`cardsHeight`)をやめ、`PanelSetCards` を使う。許可の帯はカードの上(今どおり)。ホーム(ワイド)は `HomeSetCards` のまま。
2. 角丸の段(C4): 外側の面 24dp・内側の部品 12dp・画像 8dp。
   - `HomeSetCards`: セットのカード・追加のカード/行 28dp → 24dp。マス(`SetBlock`)12dp は今のまま。
   - `HomeMiniPlayer`: 外側の `Surface` 32dp → 24dp。ジャケットの画像の角丸は 8dp(今が違えば)。左右 40dp の余白は変えない。
   - `MediaCard`: 外側 16dp → 24dp。ジャケット 8dp。
3. シーク(C5、`SeekBar.kt`): つまみをドラッグしたときだけ確定する。バーの単発のタップ(押して、タッチの遊び `viewConfiguration.touchSlop` を超えて動かさずに離す)では `onSeek` を呼ばず、表示も元の位置に戻す。ドラッグで端まで動かしたときの今の端の扱い(0・`duration`)は残す。
4. 見た目・動きのほかは変えない(ドック・時計・セグメント・ミニプレイヤーの中身)。

## テスト(足す。既存は弱めない。角丸の値・パネルのセット表示で `home_set_*` を確かめていた既存のテストは、新しい形で同じ場面を確かめる形に置き換え、報告する)
- `PanelSetCardsTest`: カードの高さ 104dp・間隔 8dp、アイコン 2 つと番号つきの名前、今のセットの輪郭、押すと `onOpen` の番号、パネルの側に PanelIcon(左なら反転)、追加の行が実線・Muted で押すと `onAdd`、長い名前が1行で省略、5 セットでスクロールでき最後の追加が届く。
- `PanelContentTest`: セット表示で `panel_set_*` が出て `home_set_*` は出ない。
- 角丸: 外側の形が 24dp(今のテストの確かめ方に合わせる。確かめていなければ足さなくてよい)。
- `SeekBar`(既存の MediaCard・HomeMiniPlayer のテストか、新しいテストで): バーの途中を1回タップしても `onSeek` が呼ばれない。つまみをドラッグすると呼ばれる。端までのドラッグで 0 / duration。
- `LayoutMatrixTest`: 狭いパネルのセット表示の組み合わせで、カードと追加が画面の中・重ならない。

## この Task でしないこと
- 設定(Task 102)・幕・起動画面(Task 103)。git のコミット等。

## 報告
変更点、テストの結果(件数)、置き換えた既存テスト、**決めたこと**。

## 追記(実装前レビューの反映。本文より優先)
- 変更してよいテストに `app/src/test/java/com/gt8d/launcher/ui/panel/PanelScreenTest.kt` を足す。パネルのセット表示を `home_set_*` / `home_sets` で確かめていた所は、同じ場面を `panel_set_*` で確かめる形に置き換える(ホームのワイドの所は `home_set_*` のまま)。
- シーク: ドラッグはバーのどこから始めてもよい(つまみからに限らない)。押してからタッチの遊びを超えて動いたときだけ、離したときに1回 `onSeek`。遊び以下の小さな動き・単発のタップでは `onSeek` を呼ばず、進み具合と時間の表示を元に戻す。前の操作の状態を次の操作に持ち越さない。今のテストの「中央から押して動かす」は、遊びを超えて動くので今のまま通ること。テストに、遊びの前後(超えない / 超える)、表示が戻ること、確定が1回だけ、を足す。
- スクロールの作り: `PanelContent` のセット表示は、スクロールしない `Column` に、許可の帯(あれば。固定で、一緒にスクロールしない)→ `PanelSetCards(Modifier.fillMaxWidth().weight(1f))`。スクロールは `PanelSetCards` の中だけ(親の `verticalScroll` はやめる)。
- アイコンが無いとき: 入っているがアイコンが取れない → 頭文字の四角(地 `Palette.Panel`、白い文字、角丸 12dp)。入っていない → 頭文字の四角(地 `Palette.Control`、`Palette.Muted` の文字)。両方テストする。
- カードの内側の余白: 左右 16dp、中身は縦の中央。
- `LayoutMatrixTest` の「画面の中」は、各カード・追加の行をスクロールして見せた時点で画面の中にあり、カード同士・時計・セグメント・ドックと重ならないこと。0 セットなら追加の行だけ、少ないときは上寄せ、もテストする。
- C4 の範囲: この Task で変える角丸は本文に挙げた外側の面とジャケット(`HomeMiniPlayer` のジャケットは今 16dp → 8dp、`MediaCard` のジャケットも 8dp)だけ。ミニプレイヤーの中のボタン(36dp など)は変えない。
