最初に tasks/RULES.md を読み、従う。

# Task 133: 分割中のパネルのカードに歌詞の今の行、文書

仕様: `docs/superpowers/specs/2026-11-03-lyrics-design.md` の §5.3・§8。計画: `docs/superpowers/plans/2026-11-03-lyrics.md` の Task 133。
使う既存の部品: `media/lyrics/Lyrics.kt` の `LyricsState`、`ui/home/LyricIndex.kt` の `rememberLyricIndex`、`media/NowPlaying.kt` の `NowPlaying.lyricsQuery`、`ui/panel/PanelContent.kt` の `PanelState.lyrics`・`PanelState.lyricsEnabled`。これらは変えない。

## 変えるファイル(これ以外は作らない・変えない)
- `app/src/main/java/com/gt8d/launcher/ui/panel/MediaCard.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelContent.kt`(`MediaCard(...)` の呼び出しに2つの引数を足すだけ)
- `app/src/test/java/com/gt8d/launcher/ui/panel/MediaCardTest.kt`(追加のみ。既存のテストは弱めない)
- `README.md`
- `docs/install-v1.6.md`
- `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md`

## MediaCard.kt(仕様 §5.3)
- `MediaCard` の引数の最後に `lyrics: LyricsState = LyricsState.Off`、`lyricsEnabled: Boolean = false` を足し、`ActiveMediaContent` へ渡す。
- 曲名・アーティストの列(`media_metadata`)の、アーティストの下に1行(testTag `media_lyric_line`)を、`lyricsEnabled && now.lyricsQuery != null` のときだけ置く。
  - 行の高さは、本文 `Dims.BodyText` の1行の高さで固定(中身が空でも同じ高さ)。
  - 中身: `lyrics` が `Synced` なら `rememberLyricIndex(now, lyrics.lines)` で今の行 i を求め、i >= 0 かつ本文が「♪」でなければ本文を白・`Dims.BodyText`・1行・`TextOverflow.Ellipsis`(マーキーにしない)。i が -1 か本文が「♪」なら `Palette.Muted` の「♪」。`Synced` 以外は空(空文字の Text でよい)。
  - 行が変わるときに動きは付けない。
- 条件を満たさないときは行を置かない(今のカードと同じ)。

## PanelContent.kt
- `MediaCard(...)` に `lyrics = state.lyrics, lyricsEnabled = state.lyricsEnabled` を足す。ほかは変えない。

## 文書
- `README.md` の機能の説明のところ、`docs/install-v1.6.md` の注意のところに、次の一文を足す(場所は既存の構成に合わせる): 「ホームのミニプレイヤーの歌詞ボタンで歌詞表示をオンにすると、再生中の曲名・アーティスト・アルバム名・長さを LRCLIB(lrclib.net)に送って歌詞を探します。オフ(既定)の間は送りません。」
- `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md`: 先頭の改訂履歴の表に1行(版は既存の最後の次。内容「ミニプレイヤーの歌詞表示(詳細は 2026-11-03-lyrics-design.md)」)。§4.4 のミニプレイヤーの段落と、パネルのメディアカードの段落の末尾に、それぞれ1〜2文で歌詞の要点(①ジャケットと歌詞の切り替えボタン、②アーティストの下に今の行1行、詳細は別紙)を足す。

## テスト(MediaCardTest に追加。この名前で書く。既存のテストの組み立て方に合わせる)
- `syncedShowsCurrentLine`: 行 [1000「一」, 2000「二」]、停止中で位置 2500 → `media_lyric_line` に「二」
- `beforeFirstLineShowsNote`: 位置 500 →「♪」
- `longLineEllipsized`: 長い本文で `media_lyric_line` の文字が1行(`TextLayoutResult.lineCount == 1` と `hasVisualOverflow`、既存テストの文字の測り方に合わせる)
- `lineReservedWhileEnabled`: オンで Loading・Plain・Missing・Offline・Failed のそれぞれで `media_lyric_line` があり、カード(既存の testTag)の高さが Synced のときと同じ
- `noLineWhenOffOrNoQuery`: オフ、またはアーティストが null の曲で `media_lyric_line` が無く、カードの高さが引数を渡さないとき(既定)と同じ

歌詞の文字列は架空のダミー文を使う。

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.panel.*'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、決めたこと(RULES.md 1)。
