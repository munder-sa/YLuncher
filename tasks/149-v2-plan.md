最初に tasks/RULES.md を読み、従う。

# Task 149: v2.0.0(歌詞と背景の見た目)の設計書と実装計画を書く

コードは変えない。文書を2つ作る。

## 作るファイル(これ以外は作らない・変えない)
- `docs/superpowers/specs/2026-11-04-v2.0-lyrics-visual-design.md`(設計書)
- `docs/superpowers/plans/2026-11-04-v2.0-lyrics-visual.md`(実装計画)

## 参考にする既存の文書・コード
- 歌詞の設計書 `docs/superpowers/specs/2026-11-03-lyrics-design.md`(特に §5.2 と §10・§11 の r1.4・r1.5)、既存の計画 `docs/superpowers/plans/2026-11-03-lyrics.md`(書き方・Task の粒度・Global Constraints・Review Focus の形をまねる)
- `app/src/main/java/com/gt8d/launcher/ui/home/LyricsPane.kt`・`HomeMiniPlayer.kt`・`ui/theme/Theme.kt`・`media/MediaWatcher.kt`(ジャケットの Bitmap)・`ui/panel/MediaCard.kt`
- 調査の結果(codex の回答): `tasks/149-research-amll.md.txt`(AMLL と AMLL-DroidMate の比較。どちらも AGPL-3.0 なので**考え方だけ参考にし、コードは写さない**)

## ユーザーの決定(2026-11-03)
v2.0.0 に入れるのは次の4つ(調査の優先順の上から4つ):
1. **ジャケットから色を取った、暗い静止のグラデーション背景**(ホームのミニプレイヤーのカードの地。動かさない。新しいライブラリを入れない=自前の色抽出。ジャケットが無い・抽出に失敗したら今の `Palette.Panel` #2E2E38)
2. **今の行と次の行の明るさの調整**(背景が変わっても読みやすいこと。主要な文字はコントラスト比 4.5:1 以上を目標。白文字・`Palette.Muted`・半透明の歌詞は、背景と合成した後の色で確かめる)
3. **行ごとに少しずつ遅れて動く送り**(見えている行だけ、隣の行との差 50ms 程度・合計 100ms 程度から。今の行は遅らせない。2行以上の飛び・戻りは今どおりすぐ。送りの途中で次の行に変わっても跳ばない)
4. **今の行以外をわずかに縮める**(今の行 1.0、他 0.96〜0.97 程度。`graphicsLayer` の scale、変形の原点は左の縦中央。文字の大きさ・行の高さの計算は変えない。読みやすさ優先で、値は実機で決められるよう1か所の定数に)

見送り: 今の行以外のぼかし、動くメッシュの背景、AMLL TTML DB(別の調査にする)。

## 守ること(Global Constraints に入れる)
- 端末 GT8D は全画面の描画が GPU 側で約 50ms/フレームが上限(実測)。毎フレーム描き続ける演出を足さない。動きは描画の層(`graphicsLayer`・`placeRelativeWithLayer`)で、組み立て直し・再レイアウトを起こさない(v1.7.1 の Task 142〜145 の作りを保つ)。
- リリース版はユーザー補助に画面を「パネル」1つとして見せる作り(Task 145)を保つ。
- 新しいライブラリは入れない。BuildConfig を有効にしない。
- ブランチ `v2.0`。ベータ版の名前は `2.0.0-betaN`(版コードは毎回進める)。版は最後に `2.0.0`。
- ビルド・テストは `/home/munde/tools/gradle-run.sh`、`--tests` は `:app:testDebugUnitTest --tests '...'` の形。codex は adb を実行しない。
- テストは触ったパッケージだけ。全テストは最後に1回。

## 設計書に書くこと
- 4つそれぞれの仕様(値・決まり・例外)。1 の色の取り方(縮小して主要色。透明・白黒に近い画素の扱い、暗くする決まり、グラデーションの形と色の数)、計算する時機(ジャケットが変わったときだけ、メインスレッドの外で、結果を保持、曲を素早く変えたときに古い結果を使わない)、② パネルの MediaCard に同じ背景を使うかどうか(案を出して、おすすめを書く。決まっていないことは「未決」と明記)。
- 実機での確かめ方(gfxinfo・perfetto で v1.7.1 と比べる項目、見た目の確認)。
- 範囲の外。

## 計画に書くこと
- 既存の計画と同じ形(Goal・Architecture・Tech Stack・Spec・Global Constraints・Review Focus・Task の並び・各 Task の Files/Interfaces/テストの名前と確かめる値)。Task の番号は 150 から。並列にできる組を示す(Gradle は同時に2本まで)。
- 最後に、実機確認(ベータ版)→ 全テスト → main 取り込み → タグ → リリース、の流れ。

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)

## 報告
- 作った文書の要点、未決にしたこと、決めたこと(RULES.md 1)。
