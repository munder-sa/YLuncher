最初に tasks/RULES.md を読み、従う。

# Task 161: AMLL TTML DB を今の歌詞の取得の流れに組み込む

計画 `docs/superpowers/plans/2026-11-04-ttml-lyrics.md` の **Task 161** の Files・Interfaces・Step 1〜2 を実施する。設計書 `docs/superpowers/specs/2026-11-04-ttml-lyrics-design.md`(末尾の確定事項を含む)、Task 157 の `LyricsMetadataProbe`、Task 159 の `TtmlParser`、Task 160 の `AmllClient`・`AmllCache`・`LyricsIdentity`・`LyricsRequest` を使う(それらは変えない。必要なら止めて報告)。

## 確定事項(司令塔)
- **identity の供給元**: `pkg == "com.apple.android.music"` のときだけ、`LyricsMetadataProbe` で読んだ `android.media.metadata.MEDIA_ID`(数字だけの文字列)を `LyricsIdentity.appleMusicId` にする。Apple のキー(`METADATA_KEY_MEDIA_ID`)と値が食い違うときは null(計画の `unknownPackageAndConflictingIdsRejected`)。他のアプリは null。ISRC は null(供給元なし)。
- **索引(Task 158)はまだ無い**。`AmllIndexLookup` は既定の `Unavailable` を使う。
- **優先順位**: AMLL(単語の時刻あり)→ LRCLIB の同期 → LRCLIB の平文。AMLL が未収録・失敗・単語なしなら LRCLIB の今の流れ(キャッシュ・取り直し・全体の待機を含む)を使う。
- Task 157 の一時の記録(`// TEMP(157)`)は、この Task では残す(Task 162 で外す)。

## 変える・作るファイル(これ以外は変えない)
- 変える: `app/src/main/java/com/gt8d/launcher/media/NowPlaying.kt`、`media/MediaWatcher.kt`、`media/lyrics/LyricsRepository.kt`、`AppGraph.kt`、`app/src/test/java/com/gt8d/launcher/media/NowPlayingTest.kt`、`media/lyrics/LyricsRepositoryTest.kt`、`ui/home/LyricsPaneTest.kt`(追加のみ)
- 作る: `app/src/test/java/com/gt8d/launcher/media/LyricsIdentityTest.kt`
- 既存テストで、AMLL を先に引くことで呼び出しの順・回数が変わるもの(LyricsRepositoryTest)は、偽の AMLL を「未収録」にして今の LRCLIB の期待値のまま通す形に直してよい(確かめる内容は減らさない。報告する)。

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.*' --tests 'com.gt8d.launcher.ui.home.LyricsPaneTest'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、直した既存テスト、決めたこと(RULES.md 1)。
