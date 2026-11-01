# Task 103: 幕と起動画面の緑を白に、パネルの絵を PanelIcon に、アプリはアイコンで(v1.6 段階 C の C2・C3)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
v1.6 設計書 `docs/superpowers/specs/2026-10-31-v1.6-ux-design.md` §5「段階 C の決定」の C2・C3(幕・起動画面)。ユーザーが選んだ見た目は `/mnt/f/YLuncher/build/mock/stage-c/c2-curtain-boot.png` の案A(HTML は `c2-curtain-boot.html`)。今の実機は `/mnt/f/YLuncher/build/mock/stage-c/ref/curtain.png`。
今: 幕(`curtain/CurtainContent.kt`)のパネルの側は緑の3本の縦線、進行線・今の点が緑、アプリは頭文字の丸(`CurtainWindow` が `iconFor` を渡していない)。起動画面(`ui/home/BootScreen.kt`)も緑。

## 変更するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/curtain/CurtainContent.kt`
- `app/src/main/java/com/gt8d/launcher/edge/CurtainWindow.kt`(`iconFor` を渡すだけ)
- `app/src/main/java/com/gt8d/launcher/ui/home/BootScreen.kt`
- `app/src/test/java/com/gt8d/launcher/curtain/CurtainContentTest.kt`
- `app/src/test/java/com/gt8d/launcher/edge/CurtainWindowTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/BootScreenTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.curtain.*' --tests 'com.gt8d.launcher.edge.*' --tests 'com.gt8d.launcher.ui.home.*'`

## 仕様
1. 幕:
   - パネルの側(`SlotPreview.Panel`)の絵を `ui/home/PanelIcon`(96dp)に。そのブロックが左にあるとき(`curtain_left`)は `mirrored = true`。下の「パネル」の文字は今のまま。
   - アプリの側は `iconFor(pkg)` のアイコンを 96dp・角丸 8dp で(取れないときだけ今の頭文字の丸)。`CurtainWindow` は `CurtainContent` に `iconFor` を渡す: アプリのアイコンを `PackageManager.getApplicationIcon(pkg)` から `ImageBitmap` にしたものを、パッケージごとに覚えておく(幕を出すたびに読み直さない。読めなければ null)。
   - 進行線(`LinearProgressIndicator`)の色を白(`Palette.PanelAccent`)、地は白の 24%。点は、今の位置 = 白、ほか = 白の 40%(今と同じ)。
   - 「開いています」「ホームへ戻る」・大きさ・位置は今のまま。
2. 起動画面(`BootScreen.kt`): 緑(`Palette.Accent`)を使う所を白系に(セット名の文字は白、主ボタン「今すぐ開く」は白い filled に暗い文字、進み具合の線も白)。パネルの側の表示に `PanelIcon`(48dp 程度、左なら反転)を名前の横に。アプリの側は今の表示のまま(アイコンが取れるならアイコン 48dp を名前の横に。取れなければ文字だけ)。
3. このタスクの後、`rg "Palette.Accent" app/src/main/java/com/gt8d/launcher/curtain app/src/main/java/com/gt8d/launcher/ui/home/BootScreen.kt` が 0 件。

## テスト(足す。既存は弱めない。緑・3本の縦線を確かめていた既存のテストは置き換えて報告する)
- `CurtainContentTest`: パネルの側に PanelIcon(内容説明「パネル」など、PanelIcon の今のテストの見分け方に合わせる)、左のときは反転。`iconFor` があればアイコンの画像、無ければ頭文字。進行線と今の点の色が白。
- `CurtainWindowTest`: `CurtainContent` に `iconFor` が渡り、同じパッケージは2回目以降キャッシュから。
- `BootScreenTest`: 緑が無い(主ボタンが白)、パネルの側に PanelIcon。

## この Task でしないこと
- 設定(Task 102)・パネル・ホーム(Task 104)。幕の動き(取り消し・時間)の変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、置き換えた既存テスト、**決めたこと**。

## 追記(実装前レビューの反映。本文より優先)
- 起動画面に進み具合の線は無い。「進み具合の線も白」は取り消し(線は足さない)。
- 起動画面のアイコン: `BootScreen` に名前つきの引数 `iconFor: (String) -> ImageBitmap? = { null }` を最後に足し、`HomeActivity.kt` から今のアプリの情報の取り方(`AppCatalog.icon` など今あるもの)で渡す。変更してよいファイルに `app/src/main/java/com/gt8d/launcher/ui/home/HomeActivity.kt` を足す(渡す所だけ)。今の位置引数の呼び出し(`OtherScreensLayoutTest` など)はそのまま動くこと。
- 起動画面の左右の枠は、内側の幅が 200dp 以上のときだけアイコン(48dp)を名前の左に出し、狭ければ文字だけ。名前は 1 行で省略。起動画面の PanelIcon は 48dp(設計書の 96dp は幕のこと)。アプリのアイコンが取れたときだけアプリの側にも 48dp のアイコン。
- `CurtainWindow` のアイコン: `CurtainWindow` の生きている間、パッケージごとに結果(取れなかった null も)を覚える。アイコンの読み出し・`ImageBitmap` への変換・キャッシュを `CurtainWindow` に足してよい(「渡すだけ」は取り消し)。テストは、再表示で同じパッケージは読み直さない・別のパッケージは読む・取れないときは頭文字。
- テストに足す: 幕のアプリの画像 96dp・角丸 8dp、進行線の地が白 24%、今でない点が白 40%、右パネルは反転しない。起動画面のアプリのアイコンの有無・左パネルの反転・セット名が白・主ボタンの文字が暗い色。色はセマンティクスのキー(今のテストの書き方に合わせる)か、描画した画素で確かめる。
- 置き換える既存のテストが無ければ「なし」と報告する。
- テストのコマンドは `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.curtain.*' --tests 'com.gt8d.launcher.edge.*' --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.ui.layout.*'`。
