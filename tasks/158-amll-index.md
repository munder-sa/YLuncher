最初に tasks/RULES.md を読み、従う。

# Task 158: AMLL の全体の索引(曲名・アーティストで探す。Apple Music 以外のアプリ用)

計画 `docs/superpowers/plans/2026-11-04-ttml-lyrics.md` の **Task 158** の Files・Interfaces・Step 1〜4 を実施し、さらに下の「組み込み」も行う。設計書 `docs/superpowers/specs/2026-11-04-ttml-lyrics-design.md`。Task 160 の `AmllClient`・`AmllIndexLookup`・`AmllCache`、Task 161/161b の `LyricsRepository` を使う。

## 確定した事実(司令塔が 2026-11-04 に確かめた)
- 索引: `https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/metadata/raw-lyrics-index.jsonl`(約 1.7MB、3407 行)。1行 = `{"metadata": [[key, [値…]], …], "rawLyricFile": "<名前>.ttml"}`。`metadata` の key は `musicName`・`artists`・`album`・`appleMusicId`・`isrc`・`ncmMusicId`・`qqMusicId`・`spotifyId`・`ttmlAuthorGithub`・`ttmlAuthorGithubLogin` ほか(値は別表記の配列。例: Lemon は `musicName` が `["Lemon", "日剧《非自然死亡》主题曲"]`、`album` が `["Lemon","レモン"]`)。`id` の項目は無い。
- TTML の取得先: `raw-lyrics/<rawLyricFile>`。`https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/raw-lyrics/<file>` → 200、`https://amll-ttml-db.gbclstudio.cn/raw-lyrics/<file>` → 200、`https://amll-ttml-db.stevexmh.net/raw/<file>` → 404(使わない)。試す順は GitHub → gbclstudio。404 は未収録として確定(Task 160 と同じ決まり)。
- テスト用の資料: `app/src/test/resources/ttml/raw-lyrics-index-sample.jsonl`(本物の索引の先頭 40 行+Lemon の行。メタデータだけで歌詞は含まない。変えない)。
- `AmllTarget` の folder は `am-lyrics` と `raw-lyrics` の2つだけを許す。`rawLyricFile` は `[A-Za-z0-9._-]+\.ttml` のみ。

## 組み込み(計画の Task 158 に追加)
- `AmllClient`(Task 160)が `raw-lyrics` の取得先を扱えるようにする(上の URL と順)。Apple Music の曲 ID の直接取得の経路は変えない。
- `AppGraph` で `AmllIndexStore` を1つ作り、`AmllIndexLookup` として `AmllClient` に渡す(今は既定の `Unavailable`)。索引の取得は、歌詞表示がオンで、かつ索引が必要になったとき(Apple Music の ID で取れなかった、または ID が無いアプリ)にだけ行い、24 時間に 1 回まで。オフの間は通信しない。
- 照合が一意に決まらないとき(Ambiguous)・索引が使えないとき(Unavailable)は AMLL を諦めて LRCLIB へ(今の流れ)。
- 記録(DiagLog、タグ "Lyrics"): `"amll index refresh code=<…> rows=<N> ms=<…>"`、`"amll index match <Unique|NotFound|Ambiguous|Unavailable>"`。曲名は出してよいが歌詞は出さない。

## 変える・作るファイル(これ以外は変えない)
- 作る: `app/src/main/java/com/gt8d/launcher/media/lyrics/AmllIndex.kt`、`AmllIndexStore.kt`、`AmllIndexTransport.kt`、`app/src/test/java/com/gt8d/launcher/media/lyrics/AmllIndexTest.kt`、`AmllIndexStoreTest.kt`、`AmllIndexTransportTest.kt`
- 変える: `app/src/main/java/com/gt8d/launcher/media/lyrics/AmllClient.kt`、`AppGraph.kt`(`app/src/main/java/com/gt8d/launcher/AppGraph.kt`)、`app/src/test/java/com/gt8d/launcher/media/lyrics/AmllClientTest.kt`(追加のみ)

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.*'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、決めたこと(RULES.md 1)。
