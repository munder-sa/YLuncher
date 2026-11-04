最初に `tasks/RULES.md` を読み、従う。

# Task 176b Apple Music 公開Bearerの取得と再利用

Task 176aのHTTP処理と6テストは完了。Task 175の実測では、公開ページ200・約2.5MB、最初のJS200・約3.3MBから公開Bearerを取得できた。認証済みユーザートークンはこのTaskでは扱わない。実装担当は司令塔の権限を引き継がない。

## 再起動後の再開

2026-11-04、サーバー再起動で前回の委譲が中断した。ログは `.codex-bak/20261104-131904/`。下記2ファイルは既に作成済み。前回イベントにGradle実行記録はなく、対象XMLもまだ無い。現在のコードとテストを読み、仕様を満たしていれば指定テストから進める。最初から作り直さず、必要な修正だけを行う。既存テスト条件を維持する。司令塔がプロセス/ロック保持者の不在を確認し、一時rulesを同ログディレクトリへ退避した。allowはcreateからwriteへ変更済み。

## 作成してよいファイル

- `app/src/main/java/com/gt8d/launcher/media/lyrics/AppleWebTokenProvider.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/AppleWebTokenProviderTest.kt`

`AppleLyricsTransport.kt`、`scripts/apple-lyrics-probe.py` は読み取りのみ。アプリへの配線・依存・既存コード・設定・版を変更しない。JavaScriptを実行せずテキストとして扱う。

## 契約

packageは既存lyrics。

- `AppleWebToken(value: String, expiresAtEpochSec: Long)` は通常のclass。valプロパティを持つがtoStringは値を表示しない固定文字列。
- `enum class AppleWebTokenFailure { NETWORK, HTTP, TOO_LARGE, TOKEN_NOT_FOUND, TIMEOUT, WAIT }`。
- `sealed interface AppleWebTokenResult` に `Ready(val token: AppleWebToken)` と `Unavailable(val reason: AppleWebTokenFailure, val retryAfterSec: Long? = null)`。これらのtoStringもトークンを出さない。
- `AppleWebTokenProvider(transport: AppleLyricsTransport, clock: () -> Long = System::currentTimeMillis, io: CoroutineDispatcher = Dispatchers.IO)`。
- `suspend fun get(): AppleWebTokenResult`、`suspend fun invalidate(rejected: AppleWebToken)` を提供。invalidateは、現在のキャッシュがそのオブジェクトと同一のときだけ消す。古い要求の認証失敗で新しいトークンを消さない。
- 後続clientで共用する `internal const val APPLE_WEB_USER_AGENT` はPython検証と同じ値: `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36`。

## 取得と制限

1. JSON/HTML/JSの解析と通信は指定io上。Mutex等で更新を1本にまとめる。有効なキャッシュがあれば通信ゼロで同じtokenを返す。有効判定はJWTのexpが現在時刻の秒数より60秒を超えて先。expによるキャッシュ判定は署名検証の代用ではない。
2. 最初は `https://music.apple.com/us/browse` を読む。公開要求のヘッダーはUser-Agentのみを渡す(Accept-Encodingはtransportがidentityにする)。認証ヘッダー・Cookie・Origin・Refererは付けない。
3. 公開301/302/303/307/308はLocationを現在のURLに対して解決し、HTTPS・同じmusic.apple.com・port未指定/443・userinfo/fragmentなしの場合だけ追う。要求ごと最大3回。不正/外部/欠落/超過はHTTP失敗。各段階でキャンセルを確認する。
4. ページからscriptのsrcとrel=modulepreloadのlinkのhrefを、引用符付きHTML属性として抽出する。属性名/タグ/relは大小を区別しない。単一/二重引用符、属性順序、複数のrelトークンを扱う。コメント内のタグは候補にしない。HTMLはスクリプトとして評価しない。
5. URLの解決基点はPythonと同じ `https://music.apple.com`。HTTPS・music.apple.com・port未指定/443・userinfo/fragmentなしでpathが `.js` で終わるものだけ候補にする。属性の既知のHTML文字参照(`amp`, `quot`, `apos`, `lt`, `gt`と妥当な数値参照)を解釈してから検査する。未知/不正な参照は候補を捨てる。重複排除し、pathに `/index` を含む候補を先に、残りは出現順。最大8本。
6. ページに有効JWT候補があればJSは読まない。なければ候補JSを順に読む。JS非200は次へ進めるが、429/503はその場で待機結果へ進む。
7. 各応答は最大8MiB、ページ+JSの本文合計はUTF-8で16MiB。要求のmaxBytesに残りの上限を反映する。tooLargeまたは偽通信のbodyが上限を超えている場合もTOO_LARGE。更新全体30秒。超過はTIMEOUTにするが呼び出し側のキャンセルは伝播し、キャッシュへ結果を入れない。
8. JWTの字句は既存Pythonの `eyJ`で始まるBase64URLの3部分を使う。候補は文書ごと最大20個の異なる値、1候補UTF-8で16KiBまで。payloadだけBase64URLとJSONを読む。expは非文字列の正のLong整数、余裕60秒を満たすものだけ。失敗時に生の値・デコード結果・例外を出さない。候補内ではexpが最も後のもの、同じなら最初を採用。公開資産内の実トークンをコード/テストへ固定しない。
9. 失敗後の再試行抑制は最低60秒。429/503の正のRetry-Afterがあればその長い方。期限計算は飽和させオーバーフローしない。抑制中は通信ゼロのUnavailable(WAIT,残秒切上げ)。invalidateは現在保持するtokenだけを消し、他の失敗待機を勝手に解除しない。
10. ログ・永続保存・バックグラウンド更新を追加しない。通常の通信/解析例外は固定の分類へ変換し、CancellationExceptionは握りつぶさない。

## テスト

偽transport・偽時計・coroutines-testのdispatcherを使い、実通信なしで次を検証する。

- `findsPageTokenAndReusesUntilExpiry`: ページ内JWT、exp最大の選択、60秒境界、キャッシュ通信ゼロ、失効後更新。ダミーJWTを生成し実値を使わない。
- `extractsAndOrdersSafeAssets`: script/link、属性順と引用符/大小、modulepreload、文字参照、重複、index優先、他host/不正URL/コメントの除外、候補8本までを確認。
- `followsOnlyBoundedSameOriginRedirects`: 相対/絶対の同一origin、許可コード、最大3回、外部/userinfo/port/fragment/http/不正/Location欠落の拒否。全要求に認証情報が無いことも確認。
- `rejectsInvalidAndOversizedTokens`: 不正Base64/JSON、exp欠落/文字列/小数/負値/期限切れ、16KiB上限、候補20個上限、出力の非漏洩。
- `boundsDownloadSizesAndTotalTime`: 個別/合計容量の境界、tooLarge、残りに合わせたmaxBytes、30秒のタイムアウト、無期限再試行なし。偽transportで仮想時間を使う。
- `deduplicatesConcurrentRefreshAndInvalidatesByIdentity`: 同時getは1回更新、同じtokenを共有、古いtokenのinvalidateで新しいtokenを消さない。
- `failuresRespectCooldown`: IO/HTTP/候補なしの固定分類、60秒、429/503のRetry-After、Long.MAX_VALUE、期限後再取得。本文/例外を結果へ含めない。
- `cancellationDoesNotPublishOrCache`: 待機中/通信中/完了直前のキャンセルを成功へ変換せず、次のgetを妨げる中途半端な状態を残さない。

テスト名は維持。実装に合わせて期待を弱めない。公開Webの変化により取得できない場合を正しく失敗として扱い、任意ホストやJS実行へ広げない。

## 使ってよいコマンド

- 読み取りコマンド。
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.AppleWebTokenProviderTest'`

変更後に1回、失敗修正時だけ再実行。既に成功したtransportのテストを重ねて実行しない。実通信・assemble・adb・Git更新をしない。

## 報告

変更、対象テスト件数/失敗件数、実行コマンドとXML、キャンセル/更新集約/非出力の根拠、実通信は未確認であること、決めたことを日本語で報告する。
