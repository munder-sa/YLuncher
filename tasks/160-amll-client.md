最初に tasks/RULES.md を読み、従う。

# Task 160: AMLL TTML DB の取得(Apple Music の曲 ID で直接)・取得元ごとのキャッシュ

計画 `docs/superpowers/plans/2026-11-04-ttml-lyrics.md` の **Task 160** の Files・Interfaces・Step を実施する。ただし次の変更を加える。設計書 `docs/superpowers/specs/2026-11-04-ttml-lyrics-design.md`(末尾の「実機・データの確認」を含む)と、Task 159 の `TtmlParser` を使う(変えない)。

## 計画からの変更(司令塔の決定、2026-11-04)
1. **索引(Task 158)はまだ無い**。この Task では「Apple Music の曲 ID で直接取る経路」だけを作る。`AmllClient` の索引による照合の部分は、索引の口(interface。例 `AmllIndexLookup`)だけ用意し、既定は「索引なし=常に未照合」にする。計画のテストのうち索引を前提にするもの(`unverifiedIdUsesIndex`・`direct404TriesIndexOnce`・`isrcPrecedesTextMatch`)は、偽の索引の口で確かめる形にしてよい(索引の実装は作らない)。
2. **ミラーを順に試す**: 直接取得は、次の順にベースの URL を試し、HTTP の失敗・TTML でない応答・`TtmlParser` が Invalid のときは次へ進む。404 はその URL で「未収録」として次のミラーへは進まない(どのミラーも同じ内容のため。ただし最後に GitHub の生ファイルで 404 を確かめるかは、決めたことに書く)。ミラーの正確な URL の形は、AMLL-DroidMate のソース(https://github.com/Zeehan2005/AMLL-DroidMate の `LyricsRepository.kt` 1582〜1715 行付近、コミット c6ae2c2)と AMLL TTML DB の README の「镜像源」を Web で確かめ、`am-lyrics/<id>.ttml` の取得に使える形だけを採る(確かめられないミラーは入れない。最後は必ず `https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/am-lyrics/<id>.ttml`)。採ったミラーと根拠の URL を報告する。
3. Apple Music の曲 ID は `LyricsIdentity.appleMusicId`(数字だけの文字列のときだけ使う)。他は計画どおり。

## 作る・変えるファイル(これ以外は変えない)
- 作る: `app/src/main/java/com/gt8d/launcher/media/lyrics/AmllClient.kt`、`AmllCache.kt`、`AmllTransport.kt`、`app/src/test/java/com/gt8d/launcher/media/lyrics/AmllClientTest.kt`、`AmllCacheTest.kt`、`AmllTransportTest.kt`
- 変える: `app/src/main/java/com/gt8d/launcher/media/lyrics/Lyrics.kt`(`LyricsIdentity`・`LyricsRequest` の追加のみ)

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)と Web の閲覧
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.AmllClientTest' --tests 'com.gt8d.launcher.media.lyrics.AmllCacheTest' --tests 'com.gt8d.launcher.media.lyrics.AmllTransportTest'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、採ったミラーと根拠、決めたこと(RULES.md 1)。

## 追記(codex の質問への回答。ミラーは司令塔が確かめた。Web を見る必要はない)
資料: `tasks/160-ref-droidmate-mirrors.kt.txt`(DroidMate の該当箇所)、`tasks/160-ref-ttmldb-mirrors.md.txt`(DB の README の镜像源)。2026-11-04 に Lemon(1537460612)で実測:
- `https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/am-lyrics/<id>.ttml` → 200、0.57 秒。未収録の 604748203 は 404 が 0.57 秒で返る。
- `https://amll-ttml-db.stevexmh.net/am/<id>` → 200、1.0 秒(拡張子なし)。
- `https://amll-ttml-db.gbclstudio.cn/am-lyrics/<id>.ttml` → 200、1.7 秒。
- 採らない: `amlldb.bikonoo.com`(302 の転送。転送は追わない作り)、`amll.mirror.dimeta.top`(未収録の ID で 10 秒の時間切れ)。

決定:
- 試す順は **GitHub の生ファイル → stevexmh → gbclstudio**。
- **404 は「未収録」として確定し、次のミラーへ進まない**(公式の GitHub が最初なので、404 はそのまま信じてよい)。
- 次へ進むのは、接続できない・時間切れ・5xx・429・TTML でない応答・`TtmlParser` が Invalid のとき。429 の待機は計画どおり(Retry-After、無効なら 60 秒)。
- 各ミラーの接続・読み取りの時間切れは 5 秒ずつ。
