# Task 064: ジャケットをタップで再生中のアプリを開く・ミニプレイヤーの余白を少し広げる

最初に `tasks/RULES.md` を読み、従うこと。

## 背景(ユーザーの希望 2026-10-31)
- ジャケット(アートワーク)をタップすると、今再生しているプレイヤーのアプリが開くようにしたい。
- ホームのミニプレイヤーの、上と左右の余白を「ほんの少し」広げたい。

## 変更するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeMiniPlayer.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/MediaCard.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/HomeMiniPlayerTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/MediaCardTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.ui.home.* --tests com.gt8d.launcher.ui.panel.* --tests com.gt8d.launcher.ui.layout.*`

## 仕様
1. HomeMiniPlayer: 再生情報があるとき(`now != null`)のジャケット(testTag `player_art`)を押すと `onLaunch(now.pkg)` を呼ぶ。押せることが分かるよう `Modifier.clickable(onClickLabel = "<アプリ名>を開く")`(アプリ名は `appInfo(now.pkg).label`)。角丸の形で押したときの波紋が出るよう、`clip` の後に `clickable` を付ける。アプリが未インストール(`appInfo(now.pkg).installed == false`)なら押せない。
2. MediaCard(分割時のパネル): ジャケット(testTag `artwork`)にも同じ動き(`onLaunch(now.pkg)`)を付ける。押せる領域は最小 `Dims.MinTouch` を保つ(今 72dp なので満たす)。アプリ名を得る手段が MediaCard に無ければ `onClickLabel` は「再生中のアプリを開く」でよい。
3. HomeMiniPlayer の余白: `padding(32.dp)` を `padding(start = 40.dp, top = 40.dp, end = 40.dp, bottom = 32.dp)` にする。余白の段階の計算(063 の `PlayerSpacing`)は `BoxWithConstraints` の中の maxWidth/maxHeight で動いているので、そのまま使えること(はみ出さないこと)を既存テストで確かめる。`wrapFavorite` の判定幅(336dp)は変えない。
4. テストを足す(既存は弱めない):
   - `HomeMiniPlayerTest.artTap_launchesPlayerApp`: `player_art` を押すと `onLaunch` が `"music"` で1回呼ばれる。
   - `MediaCardTest` に、`artwork` を押すと `onLaunch` が now.pkg で1回呼ばれるテスト。
   - 余白: `home_player` の左端と `player_art`(または `player_title`)の左端の差が 40dp ±1。

## この Task でしないこと
- 色・マーキー・カード・ドックの変更。押したときの起動の仕方(launchApp)の変更。git のコミット等。

## 報告
変更点、テストの結果、**決めたこと**。
