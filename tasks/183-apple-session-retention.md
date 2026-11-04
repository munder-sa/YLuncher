最初に `tasks/RULES.md` を読み、従う。

# Task 183 AppleMusicSessionの保存・復元

承認済み設計の工程2。Task 182の暗号化ストアと12件のテストは完了。今回Sessionを拡張する。配線/UI/取得画面は後工程で、既存のメモリだけの利用方法とテストは維持する。実装担当は司令塔の権限を引き継がない。

## 作成・変更してよいファイル

- 更新: `app/src/main/java/com/gt8d/launcher/media/lyrics/AppleMusicSession.kt`
- 新規: `app/src/main/java/com/gt8d/launcher/media/lyrics/AppleMusicRetention.kt`
- 新規: `app/src/test/java/com/gt8d/launcher/media/lyrics/AppleMusicRetentionTest.kt`

既存 `AppleMusicSessionTest.kt`、ストア、Client、Repository、AppGraph、UI、版/依存は読み取りのみ。既存テストを変更しない。ストアの指定コマンドを再実行しない。

## 公開APIと互換性

- Session constructorへ末尾に `credentialStore: AppleCredentialStore? = null`、`persistenceScope: CoroutineScope? = null`、`io: CoroutineDispatcher = Dispatchers.IO`、`clock: () -> Long = System::currentTimeMillis` を追加。storeがある場合scopeを必須とする。constructorは保存領域/ネットワークを触らない。
- storeなしは既存のconnect/fetch/disconnectと世代の動作を完全維持。既存AppleMusicConnection/AppleMusicStatusの形を変えない。新しい保存状態は別StateFlowにする。
- `enum class AppleMusicRetentionStatus { NONE, READING, SAVING, SAVED, SESSION_ONLY, CLEARING, ERROR }`、`enum class AppleMusicRetentionProblem { READ, SAVE, CLEAR }`。
- 非秘密data class `AppleMusicRetentionState(val status: AppleMusicRetentionStatus = NONE, val problem: AppleMusicRetentionProblem? = null)`、`val retention: StateFlow<AppleMusicRetentionState>`。トークン/例外/URLを含めない。
- `suspend fun connect(input: String)`を維持。新規 `suspend fun connectFromWeb(input: String, cookieExpiresAtEpochMillis: Long? = null)` は取得元WEB、既存connectはMANUALで同じ接続ロジックへ進む。
- `fun restoreIfNeeded(): Job?` を追加。storeなし/復元不要ならnull、必要ならpersistenceScopeに復元処理を起動。同時要求は実行中の同じJobへまとめる。ホームやUIをブロックしない。
- `fun disconnect(): Job?` に返り値を付けてもよい(既存呼出し側は無視)。メモリ/進行中要求の無効化は同期で従来どおり、保存削除はアプリscopeのJobで完遂。保存処理が無い時はnull。UI側はretentionでCLEARING/ERRORを表示する後工程とする。
- AppleMusicRetention.ktへ永続化の補助を置いてよい。ヘルパー名/公開範囲/ロック順序を報告する。新規の未使用公開機能を増やさない。

## 認証成功の保存と差し替え

1. storeありの明示接続は、旧接続の世代を無効化し旧要求をキャンセルしてから、IO上で旧保存値/鍵を消す。削除がfalse/例外なら認証要求を開始せず、NEEDS_ATTENTIONとretention ERROR/CLEARで報告し、再起動で旧アカウントが復活する状態を成功扱いしない。
2. 既存の入力cleanToken/地域確認/例外秘匿/キャンセルを維持。cookie期限が負なら期限情報を採用しない。ネットワーク検証に成功した現世代の値だけ保存する。確認日時はclock()の非負値、取得元と任意Cookie期限も保存する。
3. write成功でSAVED。write失敗なら接続はこの起動中だけ維持できるが、SESSION_ONLY/SAVEを公開し保存済みとしない。部分的な書込みが残り得るので現世代の失敗保存をclearで片付け、削除失敗はERROR/CLEARへ区別する。
4. 新しいconnectの失敗/中断で、旧保存値を復活させない。認証成功を待たず入力を保存しない。Clientの公開Bearer更新/retryは既存に任せる。

## 復元

- 最初のrestoreIfNeededだけがファイルをreadする。read/read後の認証はIO/既存のsuspend requestへ移す。ファイルがMissingなら未接続/NONE。FailedならNEEDS_ATTENTION、retention ERROR/READ。破損値を無条件に再保存しない。
- Readyの保存値はprivateに保持しretention SAVEDとするが、既存storefront APIで再検証するまでCONNECTEDにしない。保存地域だけで接続済みとしない。成功した地域を現世代の接続に使う。復元認証成功でファイルを書き直す必要はない(確認日時は最後に永続保存した日時として扱う)。
- 手動接続/解除が先行したら、遅く完了したread/認証を無視。復元のために明示接続用の「旧保存値の削除」を呼ばない。
- NETWORK/TIMEOUT/WAIT/TOKEN_UNAVAILABLE/HTTP/INVALIDなど認証拒否以外の再確認失敗では保存を維持し、接続はNEEDS_ATTENTION。privateの復元候補を保持して後のrestoreIfNeededで再試行できる。読込は繰り返さない。
- 再確認失敗は最低60秒、正のretryAfterSecが長ければそちらまで再試行を抑制。期限計算/残秒はオーバーフローさせない。restoreIfNeededを連続して呼んでも通信を繰返さず、期限後の次の利用時に再確認する。常駐タイマー/自動ポーリングを追加しない。
- AUTH_REQUIRED時だけ現世代のメモリ/候補/保存を無効化し削除する。Clientが公開Bearer更新後も401/403を受けた場合の分類で、最初のHTTP拒否を直接扱わない。fetchでAUTH_REQUIREDが来た場合も同じ永続削除へ進む。古いfetch/restoreが新しい保存値を消さない。
- 明示connect/disconnectを一度行ったら、その後のrestoreIfNeededで古い値を読み直さない。storeなしの既存テストでは一切の追加通信なし。

## 競合・キャンセル

- メモリ用lockを保持したままディスクI/O/通信/joinを行わない。永続read/write/clearは別のゲート(Mutex等)で直列化し、IOへ移す。
- 永続操作の開始時と完了時の両方で世代と対象を照合する。writeの途中に解除/差し替えが入った場合、遅いwrite後の消去を新しいwriteより先に完遂し、古い保存値を残さない。古いcleanupが新しい書込みを消さない。
- キャンセルされたconnectを保存済みへ遷移させない。ディスク書込みが既に終わっていても、同じ世代のキャンセルであれば保存を回収する。IO境界でのprompt cancellationにも対処する。NonCancellableは進行中の永続操作の完結/後始末に限り、認証通信をキャンセル不能にしない。
- 解除/認証失効の保存消去は呼出し画面のscopeに依存させない。削除中はCLEARING、完了後NONE、失敗ERROR/CLEAR。メモリは直ちに無効化し、ERRORを未接続成功と混同しない。
- 依存が合成secretを含む例外を投げても既存の固定cancel例外/固定分類を維持し、ログ/例外のcause/StateFlow/toStringへ漏らさない。

## 必須テスト

新規AppleMusicRetentionTestに以下の名前で作成。合成token、偽store/時計/dispatcher/通信を使う。レースはCompletableDeferredまたはラッチ等で順序を制御し、実スレッドは待機上限を持たせる。

- `persistsOnlyValidatedCurrentCredentials`: MANUAL/WEBと期限、未検証保存なし、保存の前後でSAVING/SAVED、trim/地域/時刻、storeなしは既存動作。
- `restoresOnceAndRevalidatesBeforeConnecting`: 新Sessionで復元、未確認中に歌詞を取らない、認証後の地域採用、重複要求は1read/1connect、constructor I/Oなし。
- `transientRestoreFailuresKeepSavedCredentialsAndRespectCooldown`: NETWORK/TIMEOUT/WAIT等でfile/keyを消さない、60秒/RetryAfter/最大値、期限後再試行、read1回だけ。
- `manualReplacementCannotResurrectPreviousAccount`: 明示差し替えの成功/失敗/中断→新Sessionで旧値なし、clear失敗時新しい認証なし。
- `lateRestoreCannotOverrideConnectOrDisconnect`: readと認証それぞれを遅延させ手動/解除を先行、古い結果を破棄し新しいstoreを維持。
- `disconnectWinsAgainstInFlightSave`: 書込み中に解除、メイン側状態更新が待たされない、最終storeなし、消去完了まではCLEARING、遅いwriteから復活しない。
- `cancelledSaveIsReclaimedWithoutDeletingNewerCredentials`: 書込み中/完了直前キャンセルとその間の次connect、現世代だけ回収、古いcleanupで新値を消さない。
- `authFailureClearsOnlyCurrentSavedGeneration`: restore/fetch AUTH_REQUIRED、古いfetch認証失敗と新connect、他理由でclearなし。
- `storageFailuresAreVisibleAndNeverReportedAsSaved`: read/write/clear失敗、write後にfalseを返す偽storeも含めcleanup、メモリのみと消去失敗を区別。
- `cancellationAndFailuresNeverExposeSecrets`: 公開状態/例外/toString/ログと例外causeに秘密なし。既存cancel semanticsを維持。

既存AppleMusicSessionTestも実行して、メモリ経路の全条件が変わらないことを検証する。

## 使ってよいコマンド

- 状態を変えない読み取りコマンド。
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.AppleMusicSessionTest' --tests 'com.gt8d.launcher.media.lyrics.AppleMusicRetentionTest'`

変更後1回、失敗修正時だけ再実行。外部通信/ADB/assemble/Git更新は行わない。

## 報告

変更とAPI、ロック順序/世代/キャンセルの根拠、テスト件数/失敗/エラー/スキップ、実コマンド/XML、決めたことを日本語で報告。AppGraph/UIへの配線と実機は未実施と区別する。

## 実装・司令塔レビュー結果 (2026-11-04)

- `.codex-bak/20261104-162335/`、終了0、許可3ファイルのみ。指定コマンド1回で既存9件+新規10件=19件成功、失敗/エラー/スキップ0。既存テスト変更なし。司令塔は差分・新規コード/テスト全文・XML・実コマンドを照合。再実行なし。allow警告1件は引用符正規化の既知差で実コマンドは指定どおり。
- 決定: 永続化はinternal AppleMusicPersistence、対象識別はprivate Epoch/Reset。永続Mutex→短時間memory lockの順で、I/O/通信/join中はmemory lockを保持しない。古いwriteの回収を同じgate内で完結。戻り境界キャンセルも現世代だけ回収。期限はLong.MAX_VALUEで飽和/残秒切上げ、時計の負値/例外は0。読込み/接続前削除失敗の接続理由はINVALID+保存側分類。解除失敗はUNCONFIGURED+ERROR/CLEAR。現世代の復元キャンセルは候補を保持しNEEDS_ATTENTION/INVALID、60秒待機。レースはDeferred/ラッチ/手動dispatcher、実スレッド待機は5秒上限。
- AppGraph/UIの配線、Android Keystoreによる実機復元は後工程。
