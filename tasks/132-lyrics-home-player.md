最初に tasks/RULES.md を読み、従う。

# Task 132: ホームのミニプレイヤーで歌詞とジャケットを切り替える

仕様: `docs/superpowers/specs/2026-11-03-lyrics-design.md` の §5.2(と §1.1)。計画: `docs/superpowers/plans/2026-11-03-lyrics.md` の Task 132。見た目の手本: `design/mocks/lyrics-miniplayer.html`(ブラウザでは開けないので、CSS の値を読む)。
使う既存の部品: `media/lyrics/Lyrics.kt` の `LyricsState`・`LyricLine`、`ui/home/LyricIndex.kt` の `rememberLyricIndex`、`media/NowPlaying.kt` の `NowPlaying.metaTitle`・`NowPlaying.lyricsQuery`、`ui/panel/PanelContent.kt` の `PanelState.lyrics`・`PanelState.lyricsEnabled`・`PanelActions.onToggleLyrics`。これらは変えない。

## 作る・変えるファイル(これ以外は作らない・変えない)
- 作る: `app/src/main/java/com/gt8d/launcher/ui/home/LyricsToggle.kt`
- 作る: `app/src/main/java/com/gt8d/launcher/ui/home/LyricsPane.kt`
- 作る: `app/src/test/java/com/gt8d/launcher/ui/home/LyricsPaneTest.kt`
- 変える: `app/src/main/java/com/gt8d/launcher/ui/home/HomeMiniPlayer.kt`
- 変える: `app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt`(`HomeWideContent` の `HomeMiniPlayer(...)` 呼び出しに3つの引数を足すだけ)
- 変える: `app/src/test/java/com/gt8d/launcher/ui/home/HomeMiniPlayerTest.kt`(追加のみ。既存のテストは弱めない)

## LyricsToggle.kt
```kotlin
val LyricsIcon: ImageVector // 40×40 の viewBox。線のみ(塗りなし)、線幅 2.5、端は丸
@Composable
fun LyricsToggle(on: Boolean, available: Boolean, description: String, onToggle: () -> Unit,
                 modifier: Modifier = Modifier, boxAlignment: Alignment = Alignment.Center)
```
- `LyricsIcon` の図形(モックの SVG をそのまま): `M9 6h22a5 5 0 0 1 5 5v15a5 5 0 0 1-5 5H17l-8 5v-5a5 5 0 0 1-5-5V11a5 5 0 0 1 5-5Z`、`M12 14h16`、`M12 22h12`。
- 押せる範囲: 72dp × 72dp(testTag `player_lyrics_toggle`)。その中に見える囲み 48dp × 48dp、角丸 12dp(testTag `player_lyrics_toggle_box`)。囲みの位置は `boxAlignment`(呼び出し側が、囲みの右端を内容の右端にそろえるのに使う)。図柄は 28dp。
- 色: オフ = 地 `Palette.Control`・図柄 白。オン = 地 白・図柄 `Palette.Background0`。`available = false` のときは、オンの色のまま囲み全体を不透明度 0.38(`alpha`)。**押せるのは常に**(available でも押せる)。
- semantics: `contentDescription = description`、役割 `Role.Switch` 相当、`toggleableState`(on)。押すと `onToggle()`。

## LyricsPane.kt
```kotlin
@Composable
fun LyricsPane(state: LyricsState, now: NowPlaying, modifier: Modifier = Modifier,
               clock: () -> Long = { SystemClock.elapsedRealtime() })
```
- testTag `player_lyrics`。押しても何もしない(clickable を付けない)。スクロールしない。
- **Synced**: `rememberLyricIndex(now, state.lines, clock)` で今の行 i を得る。行を縦に並べ、今の行(i が -1 なら 0 行目)が縦の中央に来るように全体を縦にずらす。今の行は白・26sp・SemiBold(testTag `player_lyrics_current`、i が -1 のときは `Palette.Muted`・20sp で testTag は付けない)。他の行は `Palette.Muted`・20sp。中央揃え、各行は最大2行、超えたら省略記号。行の間は 16dp。
  - 動き: i が**ちょうど1つ増えた**ときだけ、ずらし量を 300ms(`tween(300)`)で動かす。それ以外の変化(2つ以上の増加、減少、曲替え)はすぐ。
- **Plain**: `state.text` を 20sp・白・中央揃えで上から表示。はみ出す分は切る(スクロールしない)。
- **Loading**: 中央に「歌詞を探しています…」(`Palette.Muted`・20sp)。
- それ以外の状態: 何も描かない(呼び出し側が出さない)。
- Synced・Plain は上下 32dp をぼかす: `Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent { drawContent(); drawRect(Brush.verticalGradient(0f to Transparent, 32dp to Black, (h-32dp) to Black, h to Transparent), blendMode = BlendMode.DstIn) }` の形。

## HomeMiniPlayer.kt
- 引数を最後に足す: `lyrics: LyricsState = LyricsState.Off`、`lyricsEnabled: Boolean = false`、`onToggleLyrics: () -> Unit = {}`。
- **ボタンを出す条件** `showToggle = now != null && now.lyricsQuery != null`(曲名・アーティストがある)。待ち受けのカードには出さない。
- **歌詞を出す条件** `showLyrics = showToggle && lyricsEnabled && lyrics が Synced・Plain・Loading のどれか && lyricsHeight >= 120.dp`。
- **使える** `available = !(lyricsEnabled && !showLyrics)`(オンなのに歌詞を出せないときだけ false)。
- **理由の文字**(`lyricsEnabled && !showLyrics` のとき): Missing →「歌詞なし」、Offline →「オフライン」、Failed →「取得できません」、それ以外(狭いなど)→ なし。
- **説明**: オフ「歌詞を表示」、オンで使える「ジャケットを表示」、使えない「<理由>。押すと歌詞表示をオフ」(理由が無いとき「表示する場所がありません」)。
- **高さの計算**: 既存の `layout()` はそのまま(段の選び方・ジャケットの大きさは変えない)。ただし、アプリ名の行の高さを、`showToggle` のとき 48dp、そうでないとき 44dp にする(既存の 44dp の箇所を変数にする。計算と描画の両方)。`lyricsHeight` = 選ばれた段で、`maxHeight - fixed(details, true)` を 0 以上にしたもの(ジャケットと違い `maxWidth` で頭打ちにしない)。
- **描画**:
  - `showLyrics` なら、ジャケットの代わりに `LyricsPane(lyrics, now, Modifier.fillMaxWidth().height(lyricsHeight))` を、ジャケットと同じ位置(`gaps.beforeArt` の後)に置く。`gaps` は既存のまま(art があるときの値を使う)。そうでなければ既存どおりジャケット。
  - ボタンの置き場所:
    - `showDetails`(アプリ名の行を出す): アプリ名の行の右端に `LyricsToggle`。行の高さ 48dp の中で、72dp の押せる範囲を `Modifier.offset`/`layout` で囲みの中心に合わせ、**囲みの右端を行の右端(=内容の右端)にそろえる**(押せる範囲は右と上下に 12dp はみ出してよい。`boxAlignment = Alignment.Center`)。理由の文字があれば、ボタンの左に `Palette.Muted`・20sp・1行・省略記号で出し、アプリ名(既存の `weight(1f)`)を詰める。
    - そうでないとき: 曲名の行(`player_title_row`)で、曲名の列の右に 8dp 空けてボタン。☆があるときは「曲名の列 → 8dp → 囲み → 8dp → ☆の丸(72dp)」に見えるよう、ボタンの押せる範囲を `boxAlignment = Alignment.CenterEnd` にし、☆の手前の間隔を 8dp にする(押せる範囲は☆と重ならない)。☆が無いときは `boxAlignment = Alignment.CenterEnd` で、押せる範囲の右端=内容の右端(囲みの右端もそろう)。理由の文字は出さない。
- ジャケットを押すとアプリを開く既存の動作は変えない(ジャケットを出しているときだけ)。

## PanelScreen.kt
- `HomeWideContent` の `HomeMiniPlayer(...)` に `lyrics = state.lyrics, lyricsEnabled = state.lyricsEnabled, onToggleLyrics = actions.onToggleLyrics` を足す。ほかは変えない。

## テスト(この名前で書く)
`HomeMiniPlayerTest` に追加(既存の `host` を、`lyrics`・`lyricsEnabled`・`onToggleLyrics` を受け取れるよう、既定値つきの引数を足して使う。曲は `metaTitle`・`artist` のある NowPlaying を別に用意。幅 409dp。座標は `getBoundsInRoot()` / `getUnclippedBoundsInRoot()`):
- `toggleInAppRowWhenTall`(高さ 694): 囲みの幅・高さ 48dp、押せる範囲 72dp。囲みの右端 = `player_seek` の右端。囲みの縦中央 = アプリ名の行の縦中央(行には testTag `player_app_row` を付ける)
- `toggleInTitleRowWhenShort`(アプリ名の行が出ない高さを探して使う。☆なし): 囲みの右端 = `player_seek` の右端、囲みの縦中央 = `player_title_row` の縦中央
- `toggleBesideFavorite`(同じ高さ・☆あり): 囲みの右端 + 8dp = `player_fav` の左端、`player_fav` の右端 = `player_seek` の右端、押せる範囲の右端 <= `player_fav` の左端
- `tapCallsOnToggle`
- `onShowsLyricsInsteadOfArt`: Synced・オン → `player_lyrics` あり、`player_art` なし
- `missingFallsBackToArt`: Missing・Offline・Failed のそれぞれで、オン → `player_art` あり、`player_lyrics` なし、理由「歌詞なし」/「オフライン」/「取得できません」の文字がある
- `unavailableToggleStillTurnsOff`: Missing・オンで押すと onToggle が呼ばれる。説明が「歌詞なし。押すと歌詞表示をオフ」
- `loadingShowsSearching`: Loading・オン →「歌詞を探しています…」
- `noMetaTitleHidesToggle`(metaTitle null)、`noArtistHidesToggle`(artist null)、`idleCardHasNoToggle`(now null)
- `lyricsHeightThreshold`: 高さを 1dp ずつ下げて、歌詞の高さが 120dp 以上のときは `player_lyrics`、未満になったら出ないことを確かめる(境目の両側を1つずつ)
- `spacingTierUnchangedByToggle`: 高さ 694 で、オフ(ジャケット)とオン(Synced)の `player_title_row` の上端が同じ
- `offShowsArtAndToggleOff`: オフ → `player_art` あり、説明「歌詞を表示」

`LyricsPaneTest`(Robolectric の Compose テスト、`mainClock.autoAdvance = false`、時計は変数):
- `highlightsCurrentLine`: 行 [1000「一」, 2000「二」, 3000「三」]、位置 2500(停止中) → `player_lyrics_current` の文字が「二」
- `beforeFirstLineHasNoCurrent`: 位置 500 → `player_lyrics_current` が無く、「一」が表示されている
- `nextLineAnimates`: 再生中、位置が 1行目→2行目に変わってから `mainClock.advanceTimeBy(150)` の時点で、2行目の縦位置が最終位置に達していない。さらに 300ms 後は達している
- `jumpSnapsWithoutAnimation`: 1行目→3行目に飛ぶと、次のフレームで3行目が最終位置
- `plainIsStatic`: Plain の長い文で、`performTouchInput { swipeUp() }` しても最初の行の位置が変わらない
- `loadingText`: Loading →「歌詞を探しています…」
- `lyricsAreaNotClickable`: `player_lyrics` に `hasClickAction()` が無い

歌詞の文字列は架空のダミー文を使う。

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.*'`
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.panel.*'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、決めたこと(RULES.md 1)。
