最初に `tasks/RULES.md` を読み、従う。

# Task 176a Apple Music 歌詞用HTTP処理

Task 175の実測が完了した。Task 176をHTTP、公開Bearer、歌詞clientの3つへ分け、このTaskではHTTPだけを実装する。アプリ配線や実通信はまだ行わない。実装担当は司令塔の権限を引き継がない。

## 作成してよいファイル

- `app/src/main/java/com/gt8d/launcher/media/lyrics/AppleLyricsTransport.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/AppleLyricsTransportTest.kt`

既存 `AmllTransport.kt` とテストは参考として読むだけ。既存クライアント・パーサー・設定・依存・版を変更しない。

## 契約

packageは既存の `com.gt8d.launcher.media.lyrics`。

- `fun interface AppleLyricsTransport` に `suspend fun get(url: String, headers: Map<String, String>, maxBytes: Int): AppleLyricsHttpResponse` を定義。
- `AppleLyricsHttpResponse` は `code: Int`、`body: String`、`retryAfterSec: Long? = null`、`tooLarge: Boolean = false`、`location: String? = null` を持つ。`data class`にはせず、`toString()`はcodeとtooLargeだけを出す。本文とLocationを文字列表現に含めない。
- `AppleLyricsUrlConnectionTransport` は上のinterfaceを実装する。テスト差替え用に `open: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }` と `clock: () -> Long = System::currentTimeMillis` をconstructorで受ける。

## 動作

1. 通信前にURIを検証する。HTTPS、hostが `music.apple.com` または `amp-api.music.apple.com` と完全一致、port未指定または443、userinfo/fragmentなし。構文不正や不許可は固定メッセージの `IllegalArgumentException`。例外へ入力URLを含めず、openを呼ばない。
2. `Authorization` / `media-user-token` ヘッダー(大文字小文字を問わない)を持つ要求は `amp-api.music.apple.com` だけ許可する。Cookieヘッダーはこのtransportでは常に拒否する。ヘッダー名/値にCR/LFがあれば拒否。maxBytesは1〜8MiBだけ許可する。名前の大小による重複ヘッダーも拒否する。
3. IOでHTTP GETを実行する。connect/read timeoutは各5000ms、`instanceFollowRedirects=false`、`useCaches=false`。`Accept-Encoding: identity` を設定する。呼び出し側が別のAccept-Encodingを渡したら拒否し、同値なら重複させない。証明書検証やホスト検証を無効化しない。
4. HTTP 200だけinputStreamを読む。最大 `maxBytes + 1` bytesまでで検出し、超過なら `tooLarge=true` / body空。通常はUTF-8で返す。非200の本文とerrorStreamは読まない。
5. `Location` は公開Webホストの301/302/303/307/308だけ取り出して返す。ここでは追跡しない。API側のLocationは返さない。公開Locationの妥当性・回数は後続providerで判定する。
6. Retry-Afterは非負の整数秒かRFC 1123のHTTP日付を解釈する。未来の日付はclockとの差を秒へ切り上げ、過去は0、構文不正・負値・Longを超える整数はnull。計算をオーバーフローさせない。生のヘッダー値を例外やログへ出さない。
7. coroutineのキャンセルでstreamを閉じ、connectionをdisconnectする。開始前に取り消された要求はopenしない。途中/終了時のキャンセルを成功やOfflineへ置き換えず伝播する。通常/エラー/上限超過でも資源を解放する。close/disconnect時の失敗は生のメッセージを外へ出さない。
8. ネットワーク例外は秘密を含みうるため、外へ出す場合は固定メッセージの `IOException` としcauseを付けない。CancellationExceptionはそのまま伝播する。要求URL・ヘッダー・本文をDiagLogや標準出力へ記録しない。

## テスト

実通信せず、既存AmllTransportTestと同じように偽HttpURLConnection/InputStreamを使う。以下のテスト名を使い、既存テストは変更しない。

- `rejectsUnsafeRequestsBeforeOpen`: 不許可host、サブドメイン偽装、http、userinfo、port、fragment、不正URI、Webへの認証ヘッダー、Cookie、CR/LF、大小違いの重複、圧縮指定、0/8MiB超の上限を拒否。openゼロ、例外にダミー秘密値を含めない。
- `readsWithinLimitAndDropsOversizedBody`: ちょうど上限/上限+1、短いreadを繰り返すstream、UTF-8本文。余分に読み続けず、上限超過時は空本文、close/disconnect、GET/timeout/cache/identity/header設定を確認。
- `neverReadsErrorBodyOrFollowsRedirects`: 公開/APIのredirect、401/404/429/500でstreamを読まない。公開redirectだけLocationを返しAPIではnull、follow=false。
- `parsesRetryAfterWithoutOverflow`: 数字の0/120/Long.MAX_VALUE、負値/overflow/不正値、未来/過去のHTTP日付、秒未満の切上げを偽時計で確認。
- `cancelClosesAndDisconnects`: ブロック中の偽streamをキャンセルで解除し、成功結果を返さずclose/disconnectする。開始前キャンセルも確認。待ち合わせには時間制限を付ける。
- `cleansUpAndRedactsFailures`: open/responseCode/readで秘密を含むIOExceptionを注入し、外へ出るメッセージ/causeに残らず、得られたconnection/streamを解放する。レスポンスのtoStringにも本文・Locationが含まれない。

## 使ってよいコマンド

- 読み取りコマンド。
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.AppleLyricsTransportTest'`

変更後に1回。失敗修正時だけ再実行。全テスト・assemble・実通信・adb・Git操作をしない。

## 報告

差分、実行コマンド、テスト件数/失敗件数とXMLの場所、キャンセル/制限/非出力を確認した方法、未確認の実通信、決めたことを日本語で報告する。
