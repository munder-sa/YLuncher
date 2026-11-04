最初に `tasks/RULES.md` を読み、従う。

# Task 178 Apple優先の歌詞取得とメモリキャッシュ

176a/b/c・177a/bの実装と対象検証は完了。認証と設定に続き、既存RepositoryへApple取得を統合する。実装担当は司令塔の権限を引き継がない。

## 変更してよいファイル

- `app/src/main/java/com/gt8d/launcher/media/lyrics/LyricsRepository.kt`
- `app/src/main/java/com/gt8d/launcher/AppGraph.kt`

## 作成してよいファイル

- `app/src/main/java/com/gt8d/launcher/media/lyrics/AppleLyricsCache.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/AppleLyricsCacheTest.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/AppleLyricsRepositoryTest.kt`

既存テスト・既存キャッシュ・Session/client/transport/provider/parser・描画・設定・依存・版は変更しない。実通信なし。既存LyricsRepositoryTestの期待値を変えず全件維持する。

## メモリキャッシュ

- `data class AppleLyricsCacheKey(val generation: Long, val storefront: String, val appleMusicId: String, val queryKey: String, val parserVersion: Int = 1)`。
- `class AppleLyricsCache(private val clock: () -> Long)`。`fun activateGeneration(generation: Long)`、`fun get(key: AppleLyricsCacheKey): AppleLyricsResult?`、`fun put(key: AppleLyricsCacheKey, result: AppleLyricsResult)`。同期して使えること。
- `companion object fun key(request: LyricsRequest, connection: AppleMusicConnection): AppleLyricsCacheKey?`。CONNECTED/非負世代/小文字英字2文字/ASCII数字1〜20桁で全桁ゼロでないID/非空曲名歌手のときだけ鍵を返す。query.cacheKeyを含める。再生アプリの検査はRepositoryで別に行う。
- 初期有効世代0。新しい世代をactivateすると全件破棄。同じ世代なら保持。古い世代へのactivateは無視し、古い鍵のget/putも無視する。解除・差替え・認証失敗時の世代変更で内容を消す。秘密値は鍵にも値にも含めない。
- OKかつ単語のあるSyncedだけ成功として30分保持。Missing/NOT_FOUNDとMissing/UNSUPPORTEDは60秒。Failed/Offlineで理由がNETWORK/HTTP/WAIT/TIMEOUT/TOKEN_UNAVAILABLE/INVALIDの結果は、一時的な試行抑制としてmax(60秒, 正のRetry-After)だけ保持し、Missingへ変換しない。AUTH_REQUIRED/NOT_APPLICABLE/Off/Loading/Plain/単語なしOKは保持しない。
- 成功/負キャッシュ/一時抑制を合計最大8件、歌詞textのUTF-8換算合計2MiBまで。getで使用順を更新するLRU。超過時は古い使用順から破棄。単一成功が2MiB超なら保持しない(Repositoryは表示してよい)。これは文字列量の設計上限でありヒープ実測値ではない。
- getでTTLを延長しない。期限と同時刻で失効。時計逆行で過去のエントリを有効化しない。長いRetry-Afterの加算は飽和させる。期限切れを掃除してから容量を判定する。
- callerの可変listをそのまま保持せず、LyricLine/wordsをコピーして保存する。生TTML・認証値・ファイル・新しいscope/タイマーは保持しない。キャッシュ自体のtoStringで歌詞を出さない。

## Repositoryの観測と対象判定

既存コンストラクタの末尾へ省略可能な3引数を追加する。

- `appleConnection: StateFlow<AppleMusicConnection> = MutableStateFlow(AppleMusicConnection())`
- `appleCache: AppleLyricsCache = AppleLyricsCache(clock)`
- `fetchApple: suspend (LyricsRequest, Long) -> AppleLyricsResult = { _, _ -> AppleLyricsResult(LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE) }`

AppGraphの既存Repository生成へ `appleConnection = appleMusic.state`、`appleCache = AppleLyricsCache(SystemClock::elapsedRealtime)`、`fetchApple = appleMusic::fetch` を渡す。共有Sessionは177bのlazyを再利用する。

1. 既存の(pkg, LyricsRequest)とenabledに、認証のstatus/generation/storefrontを加えて観測する。秘密値・reason・待機秒・再生位置・再生中フラグ・ジャケットをキーにしない。同曲のまま接続完了した場合もApple取得を始める。
2. 歌詞オフ・メタデータなしはOffで通信ゼロ。Appleは有効なrequestに加え、再生元が `com.apple.android.music`、CONNECTED、キャッシュ鍵の検証に通るときだけ対象。それ以外は既存AMLL/LRCLIBの動作を維持する。
3. 認証世代が進んだら、歌詞オフでもキャッシュのactivateGenerationを行う。collectLatestが古い非協力的要求の終了を待つ間にも世代更新・破棄できるよう、認証Flowの上流onEach等で処理する。古い処理による世代の巻き戻しを禁止する。
4. 各IO/依存呼出し後、Appleキャッシュ保存と表示の直前にキャンセルと現在の認証世代/状態/地域を確認する。A→B→A・解除→再接続でも、旧要求を新しい要求と取り違えない。世代の不一致は古い結果の破棄であり全体エラーにしない。

## 取得順序

1. 対象曲にAppleの成功キャッシュがあれば即表示し、ネット要求はゼロ。
2. なければ既存AMLL単語キャッシュ、次いでLRCLIBキャッシュを暫定表示してよい。Apple対象のときはAMLLキャッシュの早期returnで取得を飛ばさない。Apple非対象の既存早期returnは維持。
3. 既存の600ms待機を1回だけ行う。曲が落ち着いたらAppleを1回試す。既に負キャッシュ/一時抑制があれば通信せず既存取得元へ進む。clientが持つ全体5秒上限を再延長しない。
4. OK/単語ありSyncedならAppleキャッシュへ保存し、表示して終了。AppleのPlainや単語なしSyncedは採用せず既存へ進む。
5. Appleが404/非対応/認証再確認/待機/失敗なら、表示済みのAMLL/LRCLIBを消さず既存処理へ進む。キャンセル以外の予期しないfetchApple例外は固定NETWORKの失敗へ変換し、秘密値/例外を出さない。
6. Appleを試した後のAMLL→LRCLIBの優先順位、キャッシュ形式、再試行10/30/60秒、AMLLの待機制御と既存ログは維持する。LRCLIB再試行ループからAppleを繰り返し呼ばない。Appleの失敗をLRCLIB/AMLLキャッシュへ書かず、既存各取得元が実際に返した値だけを既存キャッシュへ書く。既存処理が確定した歌詞状態の選択も維持し、Appleだけの失敗を理由に余分な再試行タイマーを作らない。
7. 接続解除/認証失敗でAppleが非対象になったら、同じ曲でも既存取得元へ戻る。再認証はSessionと設定が担当しRepositoryからconnectを呼ばない。
8. 固定診断としてAppleの採否/理由・cache有無・取得解析合計ms・成功時の行数/単語数を記録してよい。本文/ヘッダー/認証値/動的URL/生例外をログに出さない。既存logResultのログ文字列を変えない。

## テスト

coroutines-testの偽時計/偽通信と合成歌詞のみ。既存RepositoryTestのFixtureを参考にするが変更しない。

AppleLyricsCacheTest:

- `keysSeparateIdentityQueryStorefrontAndGeneration`: 正常/不正入力、曲ID/曲情報/地域/世代/parser版の分離。
- `positiveAndNegativeEntriesExpireAtTheirBoundaries`: 成功30分、404/非対応60秒、期限前/同時刻、getで延長なし、時計逆行。
- `transientFailuresRemainFailuresAndRespectCooldown`: 一時失敗はMissingにならず60秒以上、Retry-AfterとLong.MAX_VALUE、非対象状態を保持しない。
- `boundsEntryCountAndUtf8TextBytes`: 8件LRU、合計2MiB、ASCII/多バイト、単体超過、古い期限切れ掃除。
- `generationChangeRejectsOldReadsAndWrites`: 新世代で破棄、古いactivate/get/putの無効、同世代保持。
- `copiesMutableInputsWithoutPersistingSecrets`: list変更後も保存結果を維持、toStringは歌詞なし、新規cacheは空。

AppleLyricsRepositoryTest:

- `appleWinsAndSkipsLowerSources`: Apple成功とキャッシュ、600ms待機、下位取得0、各ディスクキャッシュへAppleを書かない。
- `appleUpgradesBothExistingCaches`: AMLL単語とLRCLIB行/Plain/Missingを暫定表示しAppleへ格上げ。既存キャッシュの早期return問題を検出する。
- `appleFailuresFallBackWithoutPoisoningCaches`: 全失敗/404/非対応/不正な成功値でAMLLまたはLRCLIBを継続、既存表示を空にしない。
- `disabledOrIneligibleMakesNoAppleRequests`: オフ、他アプリ、ID不明/不正、未接続、CHECKING、不正地域、空メタデータ。オフでは既存取得も0。
- `connectionCompletionFetchesTheSameTrack`: 同曲のまま接続完了で取得開始。単なるreason/待機秒変更では追加取得しない。
- `positionPlaybackAndArtworkDoNotRefetch`: 位置/再生/ジャケットは取得増なし、ID/曲が変わると再取得。
- `rapidTrackChangesAndLateResultsAreDiscarded`: 600ms内の連続曲送り、非協力的な遅いA→B→A応答は表示/保存しない。
- `offCancelsAndPreventsCacheWrites`: 取得中オフ、キャンセル後成功/例外で保存せずOff、無関係scopeを止めない。
- `disconnectAndReplacementClearOldCache`: 歌詞オフ中を含む世代変更で即破棄、遅い旧世代結果は新接続へ反映しない。
- `authenticationFailureUsesSessionStateAndFallback`: 実Sessionと偽clientの組合せでAUTH_REQUIRED→NEEDS_ATTENTION、新世代、同曲の既存歌詞へ戻る。手動再接続以外ではApple再試行なし。
- `cooldownAndLegacyRetriesDoNotRepeatApple`: 60秒/Retry-Afterの試行抑制、負キャッシュ期限、LRCLIB再試行中Apple1回、成功後タイマーなし。
- `logsOnlyFixedAppleDiagnostics`: 選択元/固定理由/件数を確認し、合成歌詞/秘密の例外がログにない。

## 使ってよいコマンド

- 読み取りコマンド。
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.AppleLyricsCacheTest' --tests 'com.gt8d.launcher.media.lyrics.AppleLyricsRepositoryTest' --tests 'com.gt8d.launcher.media.lyrics.LyricsRepositoryTest'`

変更後1回、失敗修正時のみ再実行。既存Repositoryを除く成功済みテスト・全テスト・実通信・assemble・adb・Git操作は行わない。実行件数/失敗数、XML、未確認事項、決めたことを日本語で報告する。
