最初に `tasks/RULES.md` を読み、従う。

# Task 176c 認証付きApple Music歌詞取得

175の実測でsyllable-lyricsのTTML経路 `data[].attributes.ttml` を確認済み。176aのHTTP6テスト、176bのBearer8テストが成功した。今回は利用地域の確認と単語同期歌詞の取得を既存パーサーへ接続する。実装担当は司令塔の権限を引き継がない。

## 作成してよいファイル

- `app/src/main/java/com/gt8d/launcher/media/lyrics/AppleLyricsClient.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/AppleLyricsClientTest.kt`

既存パーサー、HTTP、Bearer、Repository、UI、依存、版は変更しない。実歌詞/トークンをテストへ同梱しない。実通信なし。

## 契約

- `enum class AppleLyricsReason { OK, NOT_APPLICABLE, AUTH_REQUIRED, NOT_FOUND, UNSUPPORTED, INVALID, NETWORK, HTTP, WAIT, TIMEOUT, TOKEN_UNAVAILABLE }`。
- `AppleLyricsResult(val state: LyricsState, val reason: AppleLyricsReason, val retryAfterSec: Long? = null)` は通常のclass。toStringはstateの型名/reason/待機秒のみで、歌詞を含めない。
- `sealed interface AppleConnectResult` に `Ready(val storefront: String)`、`Unavailable(val reason: AppleLyricsReason, val retryAfterSec: Long? = null)`。認証値や応答本文は戻り値に含めない。
- `AppleLyricsClient(transport: AppleLyricsTransport, tokens: AppleWebTokenProvider, clock: () -> Long = System::currentTimeMillis, io: CoroutineDispatcher = Dispatchers.IO)`。
- `suspend fun connect(userToken: String): AppleConnectResult` と `suspend fun fetch(request: LyricsRequest, storefront: String, userToken: String): AppleLyricsResult`。

## 入力と通信

1. tokenは前後空白を除き、空値・ISO制御文字・UTF-8で16KiB超を拒否。fetchはstorefrontが小文字英字2文字、Apple IDがASCII数字1〜20桁で全桁ゼロではなく、曲名/歌手が空白でないことも検証。不正入力は通信ゼロ。connectはUnavailable(INVALID)、fetchはMissing/NOT_APPLICABLEを返す。
2. connectは `https://amp-api.music.apple.com/v1/me/storefront`、fetchは `/v1/catalog/{storefront}/songs/{id}/syllable-lyrics` だけを使う。URLは固定構成し、認証値をURLへ含めない。曲情報include、行同期lyrics、検索には問い合わせない。
3. ヘッダーは `Authorization: Bearer <公開token>`、`media-user-token: <ユーザーtoken>`、`Origin: https://music.apple.com`、`Referer: https://music.apple.com/`、`Accept: application/json`、`User-Agent: APPLE_WEB_USER_AGENT`。Cookieを使わない。transportのmaxBytesは2MiB。
4. JSON/XML解析と通信は指定io上。connect全体は35秒(公開初期化30秒+API確認の余地)、fetch全体は5秒。自分の期限超過はTIMEOUT、外側のキャンセルは伝播。HTTP/解析の直後と結果反映前にもキャンセルを確認する。
5. 401/403なら、その要求に使った公開tokenを `tokens.invalidate(token)` で無効化して再取得し、同じAPIを1回だけ再試行。再度401/403ならAUTH_REQUIRED。ユーザートークンの期限切れとは断定しない。上記全体期限は再試行でも延長しない。
6. APIの429/503はクライアント全体の待機にする。正のRetry-Afterと60秒の長い方を使い、期限は飽和計算。期限前はAPI/公開Bearerへの追加通信なしでWAITと残秒切上げを返す。connect/fetchから同時にアクセスされても待機情報を壊さない。その他の即時リトライはしない。
7. Providerの失敗はNETWORK→NETWORK、TIMEOUT→TIMEOUT、WAIT→WAITと待機値、その他→TOKEN_UNAVAILABLEに分ける。例外はキャンセル以外、固定のNETWORK/INVALIDへ変換し生の例外やcauseを公開しない。

## 応答

- 2MiB超またはtooLargeはINVALID。偽transportでも本文のUTF-8サイズを確認する。APIの非200本文は解析しない。
- connectの200はJSONオブジェクトの `data` が1要素の配列、その要素の `id` が文字列の小文字英字2文字のときだけReady。欠落/型違い/複数はINVALID。404その他はHTTP、429/503はWAIT、401/403は上記処理。
- fetchの404または正常な `data: []` はMissing/NOT_FOUND。200以外はFailed/HTTP(認証/待機は専用理由)。404を全取得元の欠曲に拡張しない。
- fetchの200はJSONオブジェクト、`data`配列の要素が1つ、`attributes.ttml`が非空の文字列の場合だけ処理。複数要素・型違い・欠落・空文・非空のerrorsはINVALID。任意キーの再帰探索をしない。
- TTMLはUTF-8で512KiBまで。DTDを拒否し、rootが標準TTML namespaceのttであることを確認してから既存 `TtmlParser.parse` へ渡す。root確認もキャンセル可能で本文を出さない。既存パーサーは変更しない。
- durationはrequest.query.durationSecが1〜3600の場合のみ1000倍して渡す。それ以外はnullにしオーバーフローさせない。
- WordSyncedはSynced/OK、UnsupportedはMissing/UNSUPPORTED、InvalidはFailed/INVALID。IOExceptionはOffline/NETWORK、TIMEOUT/認証/待機はFailedと各理由。実データでのパーサー採用は後続ベータで判定する。
- 診断は任意で固定理由・HTTPコード・所要時間・パーサーの固定診断だけ。本文、URLの動的値、ヘッダー、トークン、例外をログに出さない。ディスクキャッシュ・アプリ配線を追加しない。

## テスト

Robolectricとcoroutines-testを使い、合成TTML・偽transport・偽時計で次の名前のテストを追加する。実際のproviderに偽transportを渡し、公開/認証APIを同じ偽通信で検査してよい。

- `validatesInputsWithoutNetwork`: token/storefront/ID/空メタデータの拒否と境界。
- `connectsUsingAccountStorefront`: jp固定でなくgbなどの応答を採用、ヘッダー/固定URL/2MiB上限、応答型違いと複数地域の拒否。
- `fetchesOnlyDirectWordLyrics`: 確認済みJSON経路だけ、単語開始・UTF-16文字位置、timed divを含む合成TTML。別経路を再帰採用しない。
- `classifiesMissingUnsupportedAndInvalid`: 404、data空、行同期のみ、壊れたJSON/XML、DTD、違うroot、複数データ、errors、サイズ制限と境界。
- `refreshesBearerAtMostOnceOnAuthenticationFailure`: 401/403→成功、繰り返し認証失敗、古いtoken無効化、APIと公開資産の要求回数、タイムアウトを延長しないこと。
- `respectsHostCooldownAcrossCalls`: 429/503、Retry-After、Long.MAX_VALUE、connect/fetch間の共有、待機中通信ゼロ、期限後。
- `classifiesProviderAndNetworkFailures`: Providerの固定理由、IOException等の例外、非200本文を採用しないこと、再試行の制限。
- `boundsTimeAndPropagatesCancellation`: 5秒/35秒、外側キャンセル、遅い完了を成功へ変えないこと。仮想時間を使う。
- `doesNotExposeCredentialsOrLyrics`: 結果/toString/DiagLog/例外へダミー秘密値と歌詞が混ざらないこと。

## 使ってよいコマンド

- 読み取りコマンド。
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.AppleLyricsClientTest' --tests 'com.gt8d.launcher.media.lyrics.TtmlParserTest'`

変更後に1回、失敗修正時のみ再実行。既存パーサーテストの期待値を変更せず、実通信・assemble・adb・Git操作を行わない。

## 報告

実行コマンド、件数/失敗数、XML、認証値/本文非出力、未確認の実通信、決めたことを日本語で報告。
