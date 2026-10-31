# Task 066: ホームのミニプレイヤーのお気に入りを曲名の行の右へ移す

最初に `tasks/RULES.md` を読み、従うこと。

## 背景(司令塔が実機で確かめた崩れ 2026-10-31)
064 で左右の余白を 40dp にした結果、GT8D の実機(ミニプレイヤーの内側の幅が 336dp 未満)で `wrapFavorite` が真になり、☆(お気に入り)だけが操作ボタンの上の行に回り、ジャケットも小さくなった。崩れは許容されない。
Apple Music の再生画面にならい、☆を**曲名・アーティスト名の右端**に置き、操作ボタンの行は「前へ・再生/一時停止・次へ」の3つにする。

## 変更するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeMiniPlayer.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/HomeMiniPlayerTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.ui.layout.*'`

## 仕様
1. `wrapFavorite` とその分岐をなくす。操作ボタンの行は Prev・Play/Pause・Next のみ(大きさ・間隔は今のまま)。`controlsHeight` は 96dp 固定。
2. 曲名とアーティスト名を `Column(Modifier.weight(1f))` にまとめ、その右に 8dp 空けて ☆(`PlayerControl(MediaAction.Favorite, ...)`、72dp、testTag `player_fav` は今のまま)を置く `Row`(縦は中央そろえ)にする。`now.hasFavorite` が偽なら ☆ を置かず、Column が全幅。
3. 高さの計算: 曲名+アーティスト名の行の高さは `max(曲名の高さ + 余白 + アーティスト名の高さ, ☆があれば 72dp)`。063 の段の計算(`PlayerSpacing`)・`required` に、この値を使う(はみ出さないこと)。ジャケットと曲名の間などの余白の規則は今のまま。
4. マーキー(`MarqueeText`)は Column の幅の中で流れる(☆に重ならない)。
5. テスト(既存は弱めない。☆の位置が変わる前提の期待値だけ合わせる。どれをどう合わせたか報告に書く):
   - `favorite_isBesideTitle`: 幅 336dp(GT8D と同じ、内側 256dp)・高さ 800 で、`player_fav` の縦の中心が `player_title` の top〜`player_artist` の bottom の範囲内にあり、`player_fav` の left >= `player_title` の右端…ではなく(マーキーの Text は自然な幅を持つので)、`player_fav` の right <= `home_player` の right − 40dp(±1dp)。`player_play` の top より `player_fav` の bottom が上。
   - `controls_singleRow`: 幅 336dp で `player_prev`・`player_play`・`player_next` の縦の中心が同じ(±1px)。
   - `noFavorite_hidesFav` などの既存はそのまま通ること。

## この Task でしないこと
- MediaCard(分割のパネル)の変更。色・余白の値の変更。git のコミット等。

## 報告
変更点、テストの結果、**決めたこと**。
