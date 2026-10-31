# Task 063: ミニプレイヤーの余白を整え、長い曲名・アーティスト名を流す

最初に `tasks/RULES.md` を読み、従うこと。

## 背景(ユーザーの指摘 2026-10-31)
- ホームのミニプレイヤーで、アプリ名の行とジャケット、ジャケットと曲名、アーティスト名とシークバーの間が詰まっている。バランスよく余白をとる。
- 曲名・アーティスト名が幅を超えるとき、今は「…」で切れている。**文字が流れる(マーキー)** にする(ユーザーの希望)。分割時のパネルの MediaCard にも同じ処理を入れる。
- 運転中に見る画面なので、流しっぱなしにはしない(Android for Cars の基準 ST-1 は自動スクロールの文字を禁止している。ユーザーの希望を優先しつつ、動きを最小にする)。

## 変更・作成するファイル(これで全部)
- create `app/src/main/java/com/gt8d/launcher/ui/layout/MarqueeText.kt`
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeMiniPlayer.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/MediaCard.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/HomeMiniPlayerTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/MediaCardTest.kt`
- create `app/src/test/java/com/gt8d/launcher/ui/layout/MarqueeTextTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.ui.home.* --tests com.gt8d.launcher.ui.panel.* --tests com.gt8d.launcher.ui.layout.*`

## 仕様

### A. MarqueeText(新しい共通部品)
1. `@Composable fun MarqueeText(text: String, modifier: Modifier = Modifier, tag: String? = null, fontSize: TextUnit = Dims.BodyText, color: Color = Color.White, fontWeight: FontWeight? = null, restartKey: Any? = text)`。
2. 構造: 外側の `Box(modifier.clipToBounds() + フェード + semantics { this[MarqueeOverflow] = 超えるか })` の中に、`basicMarquee` を付けた内側の `Text(maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, textAlign = TextAlign.Start)`。**`tag`(testTag)と `OneLineKey` は内側の Text 自身に付ける**(未結合ツリーの `OneLineKey` の各ノードで `GetTextLayoutResult` が取れること。`LayoutMatrixTest`・`OtherScreensLayoutTest`・`longTitle_isOneLine` が通ること)。`MarqueeOverflow` は `val MarqueeOverflow = SemanticsPropertyKey<Boolean>("MarqueeOverflow")` として同じファイルで公開する。
3. 流し方: `Modifier.basicMarquee(iterations = 1, animationMode = MarqueeAnimationMode.Immediately, initialDelayMillis = 3000, repeatDelayMillis = 3000, velocity = 24.dp, spacing = MarqueeSpacing(32.dp))`(Foundation 1.7.6 では実験的APIではない。違えば報告して止まる)。幅に収まるときは動かない(basicMarquee の性質)。1周したら先頭に戻って止まる。
4. `restartKey` が変わったら、`key(restartKey)` で作り直し、初回待ちから流し直す。
5. 右端のフェード: 文字が**幅を超えるときだけ**、外側の表示領域に `graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)` + `drawWithContent` で右端 16dp を透明へのグラデーション(`BlendMode.DstIn`)にする。左端のフェードは付けない(止まっているときに先頭の文字が欠けるため)。幅を超えるかは、`rememberTextMeasurer` で測った文字の自然な幅と、外側の表示幅(`BoxWithConstraints` の maxWidth など)の比較で判断する(`hasVisualOverflow` は使わない。marquee の中の Text は横に制約なしで測られるため)。

### B. HomeMiniPlayer の余白(上から順に。padding 32dp は今のまま)
1. アプリ名の行(44dp)→ ジャケット: 16dp
2. ジャケット → 曲名: 24dp
3. 曲名 → アーティスト名: 4dp
4. アーティスト名 → シークバー: 16dp
5. シークバー(と時間)→ 操作ボタン: 残りの高さを `Spacer(Modifier.weight(1f))` で取る(最小 16dp)。操作ボタンは下端にそろう。
6. 余白は**ある要素どうしの間にだけ**置く(アプリ名の行・ジャケット(artSize 0 のとき)・アーティスト名(null のとき)・シークバー(非表示のとき)が無ければ、その前後の余白も置かない)。計算と配置で同じ規則を使う(小さな関数にまとめる)。
7. 余白の段階: まず上の通常の余白(16/24/4/16/最小16)で `artSize` を計算し、`artSize < 120dp` になるなら、詰めた余白(8/8/2/8/最小8)で計算し直す。それでもジャケットが入らなければ今のとおり 0(出さない)。`showDetails` の判定も同じ余白で計算する。高さ 400・480 の既存テスト(曲名・再生ボタン等が表示される)が通ること。
8. 曲名とアーティスト名を `MarqueeText` にする。`restartKey` は `now.pkg to now.title to now.artist` の組(曲の ID が無いため。位置・再生状態は入れない)。testTag `player_title`・`player_artist` は今のまま。

### C. MediaCard(分割時のパネル)
1. 曲名・アーティスト名を `MarqueeText` にする(大きさ・色・testTag `title`・`artist` は今のまま。restartKey は B-8 と同じ)。余白は変えない。

### D. テスト(足す。既存は弱めない)
- `MarqueeTextTest`: (a) 短い文字は `MarqueeOverflow` が false。(b) 長い文字(幅 200dp に 40文字)は `MarqueeOverflow` が true。(c) どちらも1行(`GetTextLayoutResult` の lineCount == 1)。(d) 文字を短→長に変えると `MarqueeOverflow` が false→true に変わる。**動きそのもの(待ち時間・速度・1周で止まる)は実機で司令塔が確かめる**ので、ユニットテストでは確かめなくてよい。
- `HomeMiniPlayerTest`: `spacing_isBalanced` — 高さ 800 で、ジャケット(testTag `player_art` を足す)の下端と `player_title` の上端の差が 24dp ±1、`player_artist` の下端と `player_seek` の上端の差が 16dp 以上、全要素が `home_player` の中に**縦方向で**収まる(マーキーの Text は自然な幅を持つので横方向は判定しない)。
- `MediaCardTest`: 長い曲名で `title` が1行であること(無ければ足す)。
- テスト時間: 既存の `HomeMiniPlayerTest` の曲名は非常に長く(`"長い曲名".repeat(30)`)、マーキーの1周の待ちでテストが遅くなるおそれがある。**変更前後で `HomeMiniPlayerTest` の所要時間を比べ、2倍を超えて遅くなる場合だけ**、曲名を `"長い曲名".repeat(8)`(幅 360dp でも必ずはみ出す長さ)に短くしてよい(判定は弱めない)。時計を止めるテストを書く場合は、`finally` で `autoAdvance` を戻す。

## この Task でしないこと
- 色の変更(062 で済み)。ホームのカード・ドックの変更。`OneLineText` そのものの変更。git のコミット等。

## 報告
変更点、テストの結果、**決めたこと**。

## 追記(司令塔の判断 2026-10-31、1回目の実行で止まった件)
- 1回目の実行で、高さ 400dp では詰めた余白でも 2dp 足りず、再生ボタンが 94dp になった。**今の作業ツリーの変更(1回目の実装)を土台に続ける**(作り直さない)。
- B-7 に3段目を足す: 詰めた余白(8/8/2/8/最小8)でも、ジャケット 0 のときに `fixed` が `maxHeight` を超えるなら、**最小の余白(0/0/0/4/最小0)** で計算し直す。表示する情報・ボタンの大きさは減らさない。段の選び方は「通常 → 詰め → 最小」の順で、はみ出さない最初の段(通常だけは artSize >= 120dp も条件)。
- 既存テスト(96dp 以上など)は変えない。終わったら許可コマンドでテストを流し直し、全件の結果を報告する。
