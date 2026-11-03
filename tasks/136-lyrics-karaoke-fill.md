最初に tasks/RULES.md を読み、従う。

# Task 136: 歌詞の今の行を左から塗る(カラオケ風)、全行を同じ大きさ・左揃えに

仕様: `docs/superpowers/specs/2026-11-03-lyrics-design.md` の §5.2 の **Synced(r1.3)**。ユーザーが実機確認で、参考の録画(今の行が左から白く塗られていき、他の行は薄く、行が上へ送られる)を示して決めた。

## 変えるファイル(これ以外は変えない)
- `app/src/main/java/com/gt8d/launcher/ui/home/LyricsPane.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/LyricsPaneTest.kt`

`LyricsPane(state, now, modifier, clock)` の形と testTag(`player_lyrics`・`player_lyrics_current`)は変えない。Plain・Loading の表示、上下 32dp のぼかし、行送りの決まり(ちょうど1行進んだら 300ms、それ以外はすぐ)、measure・layout で snapshot の状態に書き込まない作り(Task 134)は保つ。

## 変更(Synced)
1. **見た目**: 全行 24sp・`FontWeight.Bold`・`TextAlign.Start`(左揃え)・最大 2 行・省略記号、行の間 16dp。色はすべて白で、不透明度で差を付ける。
   - 今の行以外: 今の行からの距離 d(行数の差の絶対値)で、d=1 → 0.45、d=2 → 0.35、d≥3 → 0.25。最初の行より前(index = -1)は、0 行目を d=0 とみなして塗りなし(全体 0.45)、他は 0 行目からの距離で上の値。
   - 今の行: まだの所は 0.45、塗った所は 1.0。
2. **今の行は縦の中央**(今と同じ計算。行の高さが揃うので中央の位置がずれないこと)。
3. **塗り**:
   - 塗りの割合 `fillFraction(lines, index, positionMs, durationMs): Float` を、`LyricsPane.kt` に `internal` の純粋な関数として作る: index が -1 なら 0。始まり = `lines[index].timeMs`、終わり = 次の行の時刻、最後の行なら `始まり + 5000`(`durationMs` が null でなく、それより小さければ `durationMs`)。割合 = `(positionMs - 始まり) / (終わり - 始まり)` を 0〜1 に収める(終わり <= 始まりなら 1)。
   - 再生中は `withFrameMillis` のループで、毎フレーム `now.positionAt(clock())` から割合を求め、`mutableFloatStateOf` に入れる。この値は**描画(draw)の中だけで読む**(再コンポーズ・再レイアウトを起こさない)。停止中は1回だけ求める。
   - 描き方: 今の行は、薄い(0.45)Text の上に、同じ位置・同じ折り返しの白(1.0)の Text を重ね、白い方を `drawWithContent` で「塗った範囲」だけ見せる。塗った範囲は、本文の文字数 × 割合 の位置(`TextLayoutResult` の `getLineForOffset`・`getHorizontalPosition` で求める)まで、文字の並び順(1行目の左→右、次に2行目)。境目は 24dp の横のグラデーションでぼかす(`BlendMode.DstIn` などで)。`TextLayoutResult` は `onTextLayout` で受け取り、状態にせず入れ物に保持してよい。
4. `internal fun lyricLineAlpha(distance: Int): Float`(上の 1 の値)も純粋な関数として作る。

## テスト(LyricsPaneTest)
追加:
- `fillFractionBasics`: 行 [1000, 3000] で、index 0・位置 1000 → 0、2000 → 0.5、3000 → 1、500 → 0。index -1 → 0
- `fillFractionLastLine`: 最後の行 3000 で、位置 5500 → 0.5、`durationMs = 4000` なら 位置 3500 → 0.5
- `lineAlphaByDistance`: 0 以外の距離で 1 → 0.45、2 → 0.35、3 → 0.25、10 → 0.25
- `linesAreLeftAligned`: 3 行の文字の左端(`getBoundsInRoot().left`)が `player_lyrics` の左端と同じ(1dp 以内)
- `currentLineCenteredWithUniformSize`: 今の行(`player_lyrics_current`)の縦中央が `player_lyrics` の縦中央と 1dp 以内。前後の行の高さが今の行と同じ(1行の文のとき)
既存のテストは弱めない。文字の大きさ・揃え・色が変わったことで期待値が合わなくなる既存テストがあれば、その期待値を新しい仕様の値に合わせるだけ直し、どれをどう直したか報告する(検証している内容を減らさない)。

歌詞の文字列は架空のダミー文を使う。

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.*'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、直した既存テスト、決めたこと(RULES.md 1)。
