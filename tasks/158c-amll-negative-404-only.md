最初に tasks/RULES.md を読み、従う。

# Task 158c: AMLL の未収録のうち、404 だけを保存しない(他は 24 時間保存に戻す)

ユーザーの判断(2026-11-04、158b の後): 「404 や 503 でないなら、今のまま(24 時間保存)でいい」。

## 決まり
- **保存しない**(曲を再生するたびに AMLL を確かめる): 直接取得・raw-lyrics 取得の **HTTP 404**。通信の失敗(Offline・5xx・429 など)は今どおり保存しない。
- **24 時間保存する**(Task 160 の元の決まり): 索引で NotFound・Ambiguous、取れた TTML が単語の時刻なし(Unsupported を含む)、その他の「使えない」結果。期限の扱い・再起動後の扱いは Task 160 のとおり。
- 同じ曲の間は AMLL を1回だけ確かめる、オフ→オンで確かめ直す(158b)は残す。LRCLIB の動きは変えない。

## 変える・作るファイル(これ以外は変えない)
- `app/src/main/java/com/gt8d/launcher/media/lyrics/AmllCache.kt`、`AmllClient.kt`、`LyricsRepository.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/AmllCacheTest.kt`、`AmllClientTest.kt`、`LyricsRepositoryTest.kt`
- 158b で「未収録をすべて保存しない」に合わせて直したテストは、この決まりに合わせて直してよい(報告する)。

## テスト(追加・変更)
- `http404IsNotCached`: 404 の後、別の曲を経て戻ると AMLL をもう一度取りに行く
- `indexNotFoundIsCached24Hours`: 索引で NotFound の曲は、24 時間 −1ms では AMLL を取りに行かず、24 時間で取りに行く
- `wordlessTtmlIsCached24Hours`: 単語の時刻なしの TTML の曲も同様
- `ambiguousIsCached24Hours`

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.*'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、直したテスト、決めたこと(RULES.md 1)。
