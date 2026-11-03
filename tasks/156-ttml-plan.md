最初に tasks/RULES.md を読み、従う。

# Task 156: AMLL TTML DB を歌詞の取得元に足す、設計書と実装計画を書く

コードは変えない。文書を2つ作る。

## 作るファイル(これ以外は作らない・変えない)
- `docs/superpowers/specs/2026-11-04-ttml-lyrics-design.md`(設計書)
- `docs/superpowers/plans/2026-11-04-ttml-lyrics.md`(実装計画)

## 参考
- 調査の結果: `tasks/156-research-ttml.md.txt`(codex の回答。ライセンス、取得の方法、TTML の形、今のモデルへの写し方、危険)
- 歌詞の設計書 `docs/superpowers/specs/2026-11-03-lyrics-design.md`(§3 取得・§3.4 キャッシュ・§10〜11)、計画の書き方は `docs/superpowers/plans/2026-11-04-v2.0-lyrics-visual.md` をまねる
- コード: `app/src/main/java/com/gt8d/launcher/media/lyrics/`(LrclibClient・LyricsRepository・LyricsCache・Lyrics.kt・LrcParser)、`media/MediaWatcher.kt`・`NowPlaying.kt`、`ui/home/LyricsPane.kt`(単語の時刻があると左から塗る)

## Claude が確かめた事実(2026-11-04)
- 全体の索引 `https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/metadata/raw-lyrics-index.jsonl` は約 1.7MB。プラットフォーム別の `am-lyrics/index.jsonl` は約 4.2MB、`ncm-lyrics/index.jsonl` は約 5.8MB。1行1曲の JSON で、`metadata` に `musicName`・`artists`・`album`・`appleMusicId`・`isrc`・`ncmMusicId` などが配列で入る。
- `https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/am-lyrics/<appleMusicId>.ttml` で直接取れる(Lemon の 1537460612 で 200、約 36KB)。
- Apple Music(Android)の MediaSession の metadata には `android.media.metadata.MEDIA_ID` と `com.apple.android.music.playback.metadata.METADATA_KEY_MEDIA_ID` などがある(Task 137 の記録)。**値が Apple Music のカタログの曲 ID かは未確認**。

## ユーザーの決定
- 取り込む。DB のデータは CC0 だが外来データには元の条件がある、という点は承知のうえで、個人の端末での表示に使う。README と導入手順に、取得元(AMLL TTML DB、リンク)と歌詞の作者への感謝を書く。
- 版は未定(計画では「次の版」と書き、ベータ版の名前は `<次の版>-betaN` とする)。

## 設計で決めること(案を出して、おすすめを書く。決められないことは「未決」と明記)
1. **照合**: ① Apple Music の再生なら、metadata から Apple Music の曲 ID が取れればそれで `am-lyrics/<id>.ttml` を直接取る(最初の Task で、実機で MEDIA_ID の値を記録して確かめる手順を入れる)。② 取れない・他のアプリなら、端末に置いた索引から 曲名+アーティスト(+アルバム)で探す。表記ゆれ(全角半角・大小・空白・「(feat. …)」)の扱い、曖昧な候補は採らない決まり、ISRC があれば優先。
2. **優先順位**: 単語の時刻がある AMLL(確実に照合できたとき)→ LRCLIB の同期歌詞 → LRCLIB の平文。AMLL の失敗は LRCLIB へ。
3. **索引の持ち方**: どの索引を使うか(全体か am 用か)、取得の頻度(1日1回など)、ディスクの置き場所と大きさ、検索のための前処理(曲名・アーティストを正規化した小さな表)、メモリの使い方(端末の負荷を考えて、全部を常に持たない)。HTTP の 512KB の上限の見直し(索引だけ大きくてよい)。
4. **TTML の読み取り**: 主旋律の `<p begin>`・`<span begin>` を `LyricLine`・`LyricWord` に写す。背景の歌詞(`x-bg`)・複数の歌い手・翻訳は今回は捨てる(範囲の外)。`end` の扱い(今は次の単語の始まりまで塗る)。
5. **キャッシュ**: 今の LRCLIB のキャッシュがあると AMLL を見に行かない問題(調査の指摘)。取得元ごとに分けるか、版を上げて取り直すか。未収録の記録の期限。
6. **表示**: 単語の時刻がある行は今の塗り(Task 138)。取得元の表示(例: 歌詞の場所の下に小さく「AMLL TTML DB」)を出すかどうか。
7. 通信の量と、オフ時は通信しない決まり(今どおり)。GitHub の生ファイルへの負荷の配慮(索引は1日1回、TTML は曲ごとに1回とキャッシュ)。

## 計画に書くこと
- 既存の計画と同じ形(Goal・Architecture・Tech Stack・Spec・Global Constraints・Review Focus・Task の並び・各 Task の Files/Interfaces/テストの名前と確かめる値)。Task の番号は 157 から。最初の Task は実機で MEDIA_ID を確かめるための記録(リリース前に外す)。並列にできる組を示す(Gradle は同時に2本まで)。
- Global Constraints に、v1.7.1・v2.0 の性能の作り(描画の層・semantics)を壊さない、新しいライブラリを入れない(XML の読み取りは Android 標準の XmlPullParser)、`--tests` は `:app:` 付き、codex は adb を使わない、を入れる。
- 最後に、ベータ版で実機確認 → 全テスト → main → タグ → リリース。

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)

## 報告
- 文書の要点、未決にしたこと、決めたこと(RULES.md 1)。
