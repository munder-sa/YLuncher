最初に `tasks/RULES.md` を読み、従う。

# Task 177a Apple Musicのメモリ内接続管理

176a/b/cのHTTP・公開Bearer・認証付きclientは実装と対象テスト成功。手動入力の設定画面に先立ち、秘密値をメモリ内だけで管理するSessionを作る。実装担当は司令塔の権限を引き継がない。

## 作成してよいファイル

- `app/src/main/java/com/gt8d/launcher/media/lyrics/AppleMusicSession.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/AppleMusicSessionTest.kt`

既存client/transport/provider/parser、Repository、UI、AppGraph、依存、版は変更しない。実通信なし。

## 公開契約

- `enum class AppleMusicStatus { UNCONFIGURED, CHECKING, CONNECTED, NEEDS_ATTENTION }`
- `data class AppleMusicConnection(val status: AppleMusicStatus = AppleMusicStatus.UNCONFIGURED, val generation: Long = 0L, val storefront: String? = null, val reason: AppleLyricsReason? = null, val retryAfterSec: Long? = null)`。秘密値・歌詞を絶対に含めない。
- `class AppleMusicSession(private val connectRequest: suspend (String) -> AppleConnectResult, private val fetchRequest: suspend (LyricsRequest, String, String) -> AppleLyricsResult)`。
- `val state: StateFlow<AppleMusicConnection>`、`suspend fun connect(input: String)`、`fun disconnect()`、`suspend fun fetch(request: LyricsRequest, expectedGeneration: Long): AppleLyricsResult`。
- 初期状態UNCONFIGURED。構築/状態参照だけでは通信しない。歌詞オンの設定に依存せず、明示connectは利用地域確認だけ。fetchを自動呼出ししない。

## 状態と秘密値

1. userTokenはprivateなメモリだけに置く。StateFlow/公開モデル/toString/ログ/例外/URL/設定/ファイル/Bundleへ含めない。入力をtrimし、trim前にISO制御文字があれば拒否、trim後空・UTF-8で16KiB超も拒否。拒否はconnectRequest呼出しゼロでNEEDS_ATTENTION/INVALID。
2. connect開始ごとにgenerationを進め、旧token/storefrontを消し、CHECKINGを公開する。他の接続確認と歌詞要求を取り消す。結果Readyのstorefrontは小文字英字2文字のみ受理。成功なら同じ世代をCONNECTED/有効storefrontにし、検証済みtokenをprivate保持。失敗は同世代NEEDS_ATTENTION/reason/待機秒、秘密値を残さない。
3. connect中の外側キャンセルは伝播し、その操作がまだ現在世代なら秘密値を消して世代を進めUNCONFIGUREDへ。古い操作のcatch/finallyが新しい接続を変更してはいけない。
4. disconnectは同期的に秘密値とstorefrontを消し、世代を進めUNCONFIGUREDへ。実行中の接続確認・歌詞要求をキャンセルする。新しいSessionインスタンスは必ず未接続。
5. fetch開始時、CONNECTEDかつexpectedGeneration一致の場合のみprivate token/storefrontをスナップショットしてfetchRequest(request, storefront, token)を1回呼ぶ。非接続/世代違いはMissing/NOT_APPLICABLEで通信ゼロ。引数には認証値を返さない。
6. 結果を反映する直前にもアクティブ性と世代/接続を検査。操作途中に解除・差替えされた応答は固定メッセージのCancellationExceptionとして破棄し、成功/失敗を返さない。非協力的な古い要求が遅れて完了/失敗しても新しい状態を変えない。
7. 現世代のfetchがAUTH_REQUIREDなら、秘密値を消し世代を進めNEEDS_ATTENTION/AUTH_REQUIREDにし、ほかの同世代要求をキャンセルする。自動再接続しない。現在のfetch自身はその結果を返してよい(Repositoryで世代変更を観測して既存取得へ進む)。ほかの理由は接続状態を変えない。
8. 依存関数がIOExceptionならconnectはNETWORK、fetchはOffline/NETWORK。その他のExceptionはINVALID/Failed。ただしCancellationExceptionは伝播し、例外メッセージ/causeを公開しない。catch/finallyでもキャンセルを隠さない。
9. 短いsynchronized区間で秘密値・世代・状態・実行中Jobを整合させ、ネットワーク中はロックを保持しない。各suspendメソッドはcoroutineScope等の子Jobを登録し、解除時に呼出し元の共有scopeまでキャンセルしない。旧Jobのcancelはロック外で行う。キャンセルを無視する旧処理を待たずに新接続を開始できること。
10. 世代変更時にキャッシュを消す処理は後続Repositoryで追加する。今回キャッシュ・永続化・タイマー・自動再試行・ログは作らない。

## テスト

偽connect/fetch・coroutines-testを使い、以下の名前で追加する。合成文字列のみ。

- `startsUnconfiguredWithoutRequests`: 初期/新規インスタンスの状態、未接続fetch、構築/参照時通信ゼロ。
- `validatesAndConnectsWithoutFetchingLyrics`: 空/制御文字/UTF-8境界/trim、異なる地域、接続してもfetchゼロ、異常storefront拒否。
- `fetchUsesOnlyCurrentPrivateCredentials`: request/地域/tokenの受渡し、世代違い通信ゼロ、公開stateに秘密値なし。
- `replacementCancelsOldOperationsAndRejectsLateResults`: connect/connect、connect/fetch競合と非協力的な遅延成功/失敗、新世代優先。
- `disconnectCancelsAndClearsCredentials`: 接続中/確認中/歌詞要求中の解除、世代更新、旧要求の結果破棄、共有親scopeは生存。
- `authenticationFailureRequiresManualReconnect`: AUTH_REQUIREDで破棄・世代変更・再取得ゼロ、同時旧要求取り消し、明示再接続後のみ復帰。
- `transientFailuresPreserveConnection`: HTTP/WAIT/NETWORK/INVALID/TIMEOUT/NOT_FOUND/UNSUPPORTEDは接続を解除しない、失敗理由の受渡し。
- `cancellationAndExceptionsDoNotPublishStaleState`: 外側キャンセル、キャンセル直後IOException、失敗したconnect、古いfinallyが新状態を消さない。
- `doesNotExposeCredentialsOrLyrics`: state/Session/resultのtoString・DiagLog/ログ・例外にダミーtoken/歌詞/生の例外内容が出ない。

## 使ってよいコマンド

- 読み取りコマンド。
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.AppleMusicSessionTest'`

変更後1回、失敗修正時のみ再実行。過去の成功テスト・全テスト・実通信・assemble・adb・Git操作は実行しない。検証結果と決めたことを日本語で報告する。
