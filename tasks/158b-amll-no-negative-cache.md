最初に tasks/RULES.md を読み、従う。

# Task 158b: AMLL の「未収録」を保存しない(曲を再生するたびに AMLL だけ確かめる)

ユーザーの判断(2026-11-04): 歌詞が LRCLIB の同期・LRCLIB の平文・未収録のときは、曲を再生するたびに **AMLL(単語の時刻あり)だけ**を取りに行く。AMLL の未収録は 24 時間保存しない。

## 今の作り(Task 160・161)
- LRCLIB のキャッシュがある曲は、それをすぐ出し、600ms 後に AMLL だけを確かめて、単語付きがあれば差し替える(これは変えない)。
- AMLL の「未収録」(404・索引で NotFound)は `AmllCache` に 24 時間保存され、その間は AMLL を見に行かない(これを変える)。

## 変更
1. AMLL の「未収録」(404・索引の NotFound・Ambiguous・単語なし)は `AmllCache` に保存しない(既存の負の記録の読み取りも無視してよい)。成功(単語付き)は今どおり期限なしで保存。
2. 同じ曲(曲の鍵が同じ)を流している間は、AMLL を一度確かめたら繰り返さない(曲が変わる・歌詞をオフ→オンにしたときにだけ、もう一度確かめる)。通信の失敗(Offline・5xx・429)の取り直しは今どおり。
3. 索引(Task 158)の取得は 24 時間に 1 回のまま(索引の中での探し直しは曲ごとに行ってよい)。
4. LRCLIB の動き(キャッシュ・取り直し・全体の待機)は変えない。

## 変える・作るファイル(これ以外は変えない)
- `app/src/main/java/com/gt8d/launcher/media/lyrics/AmllCache.kt`、`AmllClient.kt`、`LyricsRepository.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/AmllCacheTest.kt`、`AmllClientTest.kt`、`LyricsRepositoryTest.kt`
- 既存テストで「未収録を 24 時間保存する」を前提にしたもの(`negativeExpiresAt24Hours`・`generationOrIdentityInvalidatesNegative` など)は、この仕様の変更に合わせて直してよい(確かめる内容を新しい仕様で保つ。報告する)。

## テスト(追加)
- `notFoundIsNotCached`: 404 の後、別の曲を経て同じ曲に戻ると、AMLL をもう一度取りに行く
- `sameTrackDoesNotRecheckAmll`: 同じ曲の間に位置・再生状態・artwork だけ変わっても、AMLL は 1 回だけ
- `toggleOffOnRechecksAmll`: 歌詞をオフ→オンにすると AMLL をもう一度確かめる
- `lrclibCacheStaysWhileAmllRechecks`: LRCLIB のキャッシュがある曲で、AMLL が未収録なら LRCLIB は取り直さずキャッシュのまま

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.*'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、直した既存テスト、決めたこと(RULES.md 1)。
