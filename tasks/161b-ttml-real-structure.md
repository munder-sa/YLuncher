最初に tasks/RULES.md を読み、従う。

# Task 161b: 実物の構造の TTML で AMLL が使われない不具合を直す、AMLL の経路の記録

## 実機の症状(2.1.0-beta2、2026-11-04)
- Apple Music で Lemon(MEDIA_ID 1537460612、AMLL TTML DB に収録あり。GitHub の生ファイルで 200)を再生、歌詞をオン → LRCLIB の行単位の歌詞が使われた。記録は `[Lyrics] track …` → `cache miss` → `get try=1 code=200` → `result Synced`(LRCLIB)。**AMLL の取得の記録は何も出ていない**(AmllClient が記録を出さないのか、呼ばれていないのか不明)。
- 疑い: ① identity(appleMusicId)が Repository まで届いていない ② AMLL から取れたが `TtmlParser` が実物の構造を Unsupported / Invalid にした(Task 159 は未知の要素・role を Unsupported にする)。

## 資料
- `app/src/test/resources/ttml/structure-like-real.ttml`: 実物(Lemon)の TTML から、**歌詞の文字を「ア」に、メタデータの値を「dummy」に置き換えた**もの。要素・属性・入れ子・時刻は本物のまま(54 の `p`、628 の `span`、head に `ttm:agent` と 12 個の `amll:meta`、`span ttm:role="x-translation"`/`"x-roman"` を含む)。このファイルは変えない(司令塔が作った)。

## やること
1. `TtmlParserTest` に `parsesRealStructureFixture` を足す: 上のファイルを読んで、結果が単語の時刻つき(WordSynced)、行が 1 行以上(主旋律の `p` の数と合う)、各行に単語がある。今の `TtmlParser` で落ちるなら、原因(どの要素・属性・role で Unsupported/Invalid になったか)を報告し、**実物にある要素・属性は受け付ける**ように直す(`head` の中の未知の要素は読み飛ばす、`ttm:agent`・`amll:meta`・`itunes:*`・`xml:lang` などの属性は無視、`x-translation`・`x-roman`・`x-bg` の span は捨てる)。拒否すべきもの(DOCTYPE、入れ子の時刻の矛盾など)は今どおり拒否。
2. identity が Repository の AMLL 呼び出しまで届くかを、`LyricsRepositoryTest` か `NowPlayingTest` で確かめるテストを足す(`appleMusicIdReachesAmllClient`: Apple Music の pkg と MEDIA_ID の付いた NowPlaying を流すと、偽の AmllClient が appleMusicId="1537460612" の要求を受ける)。届いていなければ直す。
3. 記録(DiagLog、タグ "Lyrics")を足す: AMLL を試すとき `"amll try id=<id|none> url=<host>"`、応答 `"amll code=<code|例外名> ms=<経過>"`、読み取り `"amll parse <WordSynced lines=N | Unsupported <理由> | Invalid <理由>>"`、採用 `"amll result <状態> source=<AMLL|LRCLIB>"`。歌詞の本文は出さない。

## 変える・作るファイル(これ以外は変えない)
- `app/src/main/java/com/gt8d/launcher/media/lyrics/TtmlParser.kt`、`AmllClient.kt`、`LyricsRepository.kt`、`app/src/main/java/com/gt8d/launcher/media/NowPlaying.kt`・`MediaWatcher.kt`(identity が届いていない場合だけ)
- `app/src/test/java/com/gt8d/launcher/media/lyrics/TtmlParserTest.kt`、`LyricsRepositoryTest.kt`、`app/src/test/java/com/gt8d/launcher/media/NowPlayingTest.kt`(追加のみ)

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.*'`

## 報告
- 原因(①か②か、両方か。コードの根拠)、実行したコマンドと結果、決めたこと(RULES.md 1)。
