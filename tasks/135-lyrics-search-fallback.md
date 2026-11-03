最初に tasks/RULES.md を読み、従う。

# Task 135: 同期歌詞が無いときに LRCLIB の検索で補う、キャッシュの版

仕様: `docs/superpowers/specs/2026-11-03-lyrics-design.md` の §3.1 の「検索で補う(r1.2)」と §3.4 の版 `"v":2`。

## 背景(実機で確認、2026-11-03)
Apple Music の「夜の踊り子 / サカナクション / アルバム sakanaction / 302 秒」で、`/api/get` は平文だけの記録(303 秒、syncedLyrics: null)を返し、歌詞が静止表示になった。`/api/search?track_name=夜の踊り子&artist_name=サカナクション` では、長さ 302 秒前後で syncedLyrics を持つ記録が 10 件以上ある。

## 変えるファイル(これ以外は変えない)
- `app/src/main/java/com/gt8d/launcher/media/lyrics/LrclibClient.kt`
- `app/src/main/java/com/gt8d/launcher/media/lyrics/LyricsCache.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/LrclibClientTest.kt`(追加と、URL の呼び出し回数が変わる既存テストの調整だけ)
- `app/src/test/java/com/gt8d/launcher/media/lyrics/LyricsCacheTest.kt`(追加のみ)

`LrclibClient.fetch(q): FetchResult` と `LyricsCache` の公開の形(名前・引数・戻り値)は変えない。

## LrclibClient.fetch
1. 今の `/api/get` の処理はそのまま(仕様 §3.2 の判定)。
2. その結果が `Plain` または `Missing` で、Missing の理由が「instrumental = true」でないとき(404・400・200 で歌詞なし)、続けて検索を1回呼ぶ:
   `https://lrclib.net/api/search?track_name=<title>&artist_name=<artist>`(エンコードは get と同じ。アルバム・長さは付けない)。ヘッダも get と同じ。
3. 検索の応答:
   - `IOException`・200 以外(429 を除く)・JSON の配列でない → 手順1の結果を返す。
   - 429 → 手順1の結果を返し、`retryAfterSec` は get の 429 と同じ決まり(無い・0 以下なら 60)で付ける。
   - 200 の配列 → 各要素(オブジェクトでないものは飛ばす)から候補を選ぶ: `instrumental` が true でない、`syncedLyrics` が文字列で `LrcParser.parse` が1行以上、`q.durationSec` があるときは要素の `duration`(数値、秒)との差の絶対値が 2.0 以下(`duration` が無い要素は、`q.durationSec` があるときは候補にしない)。
   - 候補の並べ方: ① `albumName` が `q.album` と一致(両方を trim・lowercase。`q.album` が null なら全員不一致扱い)するものが先 ② 長さの差が小さいものが先(`q.durationSec` が null なら差は 0 扱い) ③ 配列の順。先頭の候補の Synced を返す。
   - 候補が無ければ手順1の結果を返す。
4. get が Synced・Offline・Failed・instrumental の Missing のときは、検索を呼ばない。

## LyricsCache
- 書くとき、JSON に `"v":2` を入れる。
- 読むとき、`v` が 2 でない(無い・違う)ファイルは、壊れたファイルと同じく消して null を返す。

## テスト(この名前で追加。偽の transport は URL ごとに応答を決められるようにする)
`LrclibClientTest`:
- `searchUsedWhenGetIsPlain`: get が plain のみ → search を呼び、duration 302 の synced を返す(q.durationSec=302)
- `searchUsedWhenGetIsNotFound`: get が 404 → search の synced を返す
- `searchNotUsedWhenGetIsSynced`: search の URL が呼ばれない
- `searchNotUsedWhenInstrumental`: get が instrumental=true → search を呼ばず Missing
- `searchNotUsedWhenOffline`、`searchNotUsedWhenServerError`(get が 503 → Failed、search なし)
- `searchUrl`: `https://lrclib.net/api/search?track_name=…&artist_name=…`(アルバム・長さなし)
- `searchSkipsDurationMismatch`: duration 315 と 233 の synced しかない(q=302)→ get の Plain を返す
- `searchPrefersSameAlbum`: 差 0 の別アルバムと差 1 の同じアルバム → 同じアルバムのもの
- `searchPrefersCloserDuration`: アルバムが同じでない 2 件、差 2 と差 0 → 差 0
- `searchIgnoresEntriesWithoutDuration`: q.durationSec=302 で duration 無しの synced のみ → get の結果
- `searchWithoutQueryDurationAcceptsAny`: q.durationSec=null → 先頭の synced(アルバム優先は効く)
- `searchFailureFallsBackToGet`: search が IOException / 500 / 配列でない JSON → get の Plain
- `searchTooManyRequestsCarriesRetryAfter`: search が 429(header なし)→ get の Plain と retryAfterSec 60
`LyricsCacheTest`:
- `fileWithoutVersionIsIgnored`: `{"kind":"plain","savedAt":0,"text":"x"}`(v なし)を書いたファイル → get は null、ファイルは消える
- `writtenFileHasVersion2`: put したファイルの JSON に `"v":2`

既存のテストは弱めない。get だけを想定していた既存テストで、検索の呼び出しが増えて落ちるものは、偽の transport に search の応答(空の配列 `[]`)を足す形でだけ直し、期待値は変えない。どれを直したか報告する。

歌詞の文字列は架空のダミー文を使う(実在の曲の歌詞をテストに入れない。曲名・アーティスト名もダミーでよい)。

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.*'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、直した既存テスト、決めたこと(RULES.md 1)。
