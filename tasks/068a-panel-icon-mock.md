# Task 068a: 「パネル」のアイコンの案を3つ作る(モック)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
画面セットのカード(`app/src/main/java/com/gt8d/launcher/ui/home/HomeSetCards.kt` の `SetBlock`)で、右がパネル(このランチャーの右側の画面。時計・ミニプレイヤー・アプリのアイコン一覧・右端のドックが入る)のとき、今は文字の「パネル」を枠で囲んで出している。他の枠はアプリのアイコン(56dp の角丸の正方形。YouTube・Apple Music など)なので、パネルにも**アプリのアイコンと並んで違和感のない、Apple(iOS)/ Android(Material)らしいシンプルなアイコン**を作る。背景は黒(#0B0B0F)の枠の中に置かれる。強調色は白(緑は使わない)。

## 作るファイル(これで全部)
- create `build/mock/panel-icon-mock.html`

使ってよいコマンド:
- `"/mnt/c/Program Files/Google/Chrome/Application/chrome.exe" --headless=new --disable-gpu --hide-scrollbars --virtual-time-budget=3000 --window-size=1200,700 --screenshot=F:\YLuncher\build\mock\panel-icon-mock.png F:\YLuncher\build\mock\panel-icon-mock.html`

## 仕様
1. 案を3つ、インラインの SVG(viewBox 0 0 24 24 の図柄 + 角丸の正方形の地)で作る。どれも、後で Compose の `ImageVector`(path だけ)に移せる単純な図形(角丸の四角・円・線)だけにする。グラデーションは地に限り可。
   - 案A: 地は濃いグレーの角丸(iOS の設定アプリのような)、図柄は白で「上に時計の横棒 + 下に 2x2 のアイコン」=パネルの中身を表す。
   - 案B: 地は白、図柄は濃いグレーで「縦長の画面に、右側の細い縦帯(ドック)と左の区画」=右側に出る画面であることを表す。
   - 案C: codex が考える最良の案(理由を1行で)。
2. HTML の上半分: 3案を 120px で横に並べ、名前と1行の説明。下半分: 実際の使われ方の再現 — 黒い角丸のカード(#17171D)の中に、左に YouTube 風のアイコン(赤い角丸に白い三角)の枠、右に各案のアイコン(56px)の枠(#0B0B0F・角丸12px)を 6:4 で並べ、下に中央寄せで「YouTube | パネル」。3案ぶん横に並べる。
3. 作ったら許可コマンドでスクリーンショットを撮り、見た目を自分で確かめる(崩れていたら直す)。

## この Task でしないこと
- アプリのコードの変更。git のコミット等。

## 報告
各案の意図、**決めたこと**。
