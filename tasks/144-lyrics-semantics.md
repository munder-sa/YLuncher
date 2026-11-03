最初に tasks/RULES.md を読み、従う。

# Task 144: 同期歌詞の行ごとの semantics を出さない(ユーザー補助の差分チェックを軽くする)

## 背景(実機の perfetto、1.7.1-beta2、2026-11-03)
- Task 143 で組み立て直しは 78% 減った。残る最大の仕事は、ユーザー補助向けの `checkForSemanticsChanges`(1回 9.5ms)と `sendAccessibilitySemanticsStructureChangeEvents`(1回 7.8ms)で、12 秒に 30 回。端末ではユーザー補助サービスが常に2つ有効。
- 同期歌詞は、行が変わるたびに各行の semantics(文字・`player_lyrics_current` の印・`LyricAlphaKey`・塗りの重ねなど)が変わり、構造の変更として扱われる。運転中に読み上げで歌詞を1行ずつ追う必要はない。

## 変えるファイル(これ以外は変えない)
- `app/src/main/java/com/gt8d/launcher/ui/home/LyricsPane.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/LyricsPaneTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/HomeMiniPlayerTest.kt`(必要な場合だけ。下の決まりで)

## 変更
1. `LyricsState.Synced` の表示では、`player_lyrics` の節(歌詞の場所の外枠)を `clearAndSetSemantics { contentDescription = "歌詞"; testTag は今の "player_lyrics" を保つ }` にし、行ごとの semantics(行の文字、`player_lyrics_current`・`player_lyrics_line_<i>`・`player_lyrics_fill`・`player_lyrics_interlude` の testTag、`LyricAlphaKey`・`LyricDrawProbeKey`)をユーザー補助の木に出さない。外枠の semantics は、曲の間ずっと変わらないこと(今の行の文字を contentDescription に入れたりしない)。
2. **テストのためだけ**に、行ごとの semantics を出す切り替えを付ける: `internal val LocalLyricsDetailSemantics = staticCompositionLocalOf { false }`。true のときは今どおり行ごとの semantics を出し、外枠は今の形(`testTag("player_lyrics")`)のまま。既定(false)が本番の動き。
3. `Plain`・`Loading` の表示は変えない(変化が無いので負荷にならない)。
4. 見た目・動き(行送り、フェード、塗り、●●●、端のぼかし)は一切変えない。

## テスト
- `LyricsPaneTest` の既存テストで行ごとの semantics を使うものは、`CompositionLocalProvider(LocalLyricsDetailSemantics provides true)` で包む(期待値・確かめる内容は変えない)。`HomeMiniPlayerTest` で同期歌詞の中の行を調べるテストがあれば同様に包む。包んだテストを報告する。
- 追加(`LyricsPaneTest`):
  - `syncedLyricsExposeSingleNode`: 既定(false)で同期歌詞を出すと、`player_lyrics` の節が1つあり contentDescription が「歌詞」、その子孫に行の文字の節が無い(`onAllNodesWithText("<1行目の文字>", useUnmergedTree = true)` が 0 件)
  - `semanticsStableAcrossLineChange`: 既定(false)で、今の行が変わる前と後で、`player_lyrics` の節の semantics の設定(`SemanticsNode.config`)が等しい

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.*'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、包んだテスト、決めたこと(RULES.md 1)。
