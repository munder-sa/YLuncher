# 次の版 AMLL TTML 歌詞 Implementation Plan

> 司令塔(Claude)が各 Task を `tasks/NNN-xxx.md` と `.allow` に展開し、変更範囲と完全なコマンドを許可する。本計画自体は実行許可ではない。Task 156 では本書と設計書のみ作成する。コミット・ブランチ操作・main取り込み・タグ・リリースは司令塔が担当する。

**Goal:** 確実に照合できる AMLL の単語同期歌詞を追加し、取得不能時は既存 LRCLIB とそのキャッシュを利用する。

**Architecture:** 確認済み identity を再生情報から取得要求へ渡す。全体索引の取得/ストリーム前処理と端末内照合、XmlPullParser による主旋律変換、ソース別キャッシュを分離し、Repository が優先順位とキャンセルを管理する。UI は既存モデルを使う。

**Tech Stack:** 既存 Kotlin、coroutines、kotlinx.serialization、HttpURLConnection、Android 標準 XmlPullParser、JUnit、coroutines-test、Robolectric。新規依存なし。

**Spec:** [TTML 設計案](../specs/2026-11-04-ttml-lyrics-design.md)。既存歌詞設計 §10〜11 と v2.0 表示設計を維持。調査結果は `tasks/156-research-ttml.md.txt`、既知の数値は Task 156 が根拠。

## Global Constraints

- コードはこの Task では変更しない。将来も各指示書/allowのファイル・コマンドだけを使う。外部通信の許可も別途必要。
- v1.7.1/v2.0 の性能構造を維持。アニメーション値は層で読み、毎フレームの再組立て・再計測・再配置を増やさない。静止背景は共用、ユーザー補助はリリース画面全体で「パネル」1つ。テスト用行semanticsをリリースへ出さない。BuildConfigを有効にしない。
- 新しいライブラリを入れない。XML は Android 標準 XmlPullParser。AMLL の描画コードをコピーしない。
- オフなら全ソース通信ゼロ、進行中はキャンセル。曲名・歌手無しは取得しない。IO/検索/解析はメイン外。キャンセル例外を握りつぶさない。歌詞本文はログ・同梱データへ書かない。
- 既存 v3 キャッシュ・24時間Missing・LRCLIB検索補完/再試行を維持。既存テストを削除/緩和しない。旧期待値と衝突すれば RULES.md の3点セットで止める。
- Gradleは `/home/munde/tools/gradle-run.sh` 経由。絞る場合は必ず `:app:testDebugUnitTest --tests '...'`。同時最大2本、同じ作業場所では直列。並列作業は司令塔が分離した作業場所と許可を用意する場合のみ。
- codex は adb を使わない。実機操作・ログ取得・ネットワーク調査は人/司令塔が実施し、記録を渡す。
- テストは関連範囲のみ。全テストはベータ実機合格後。版名は「次の版」、beta は `<次の版>-betaN`。正式番号・N・増加するversionCodeは配布履歴から司令塔が決める。

## Review Focus

1. 誤った ID/同名曲/別音源を採らない: 157の実測、158の一意性・矛盾拒否。
2. XML と索引の資源上限・外部実体・不完全更新: 158/159の境界と失敗注入。
3. LRCLIB キャッシュが AMLL を遮らない: 161の既存キャッシュからの格上げ。
4. 一時障害を未収録として保存しない: 160/161のソース別状態・期限・待機。
5. 曲変更とオフで遅い結果を捨てる: 161の世代/キャンセル、162の実機。
6. 単語開始とUTF-16位置、end近似、描画層/semanticsを維持: 159/161/162。

## Task の並び

| 波 | Task | 内容 | 依存・並列 |
|---|---|---|---|
| 1 | 157 | 一時MEDIA_ID診断と実機/形式確認 | 最初、確認結果待ちは依存作業停止 |
| 2 | 158 | 索引取得・照合 | 157の索引資料後、159と並列可 |
| 2 | 159 | TTMLパーサー | 157のXML資料後、158と並列可 |
| 3 | 160 | AMLLキャッシュ・HTTP client | 158/159後 |
| 4 | 161 | identity伝播・Repository統合・回帰 | 160後 |
| 5 | 162 | beta・実機・性能・診断除去 | 161後、人/司令塔と協働 |
| 6 | 163 | 全テスト・main・タグ・リリース | 162合格後、直列 |

158は索引専用ファイル、159はパーサー専用ファイルのみを変更し、共通 `Lyrics.kt` は変更しない。159は既存 `LyricLine` を返す。160以降の統合は直列。同時Gradle最大2本の制限は別作業場所でも守る。以下、コードパスは `app/src/main/java/com/gt8d/launcher/`、テストは `app/src/test/java/com/gt8d/launcher/` 基点。新API名は計画上の提案。

### Task 157: MEDIA_ID の一時記録と実機・データ形式の確定

**Files:**
- Modify: `media/MediaWatcher.kt`(一時診断だけ)。
- Create: `media/LyricsMetadataProbe.kt`、Test/Create: `media/LyricsMetadataProbeTest.kt`。
- Modify: 本設計書・本計画(人が確認した値・形式・採否の記録)。
- Read only: `media/NowPlaying.kt`、既存Task137記録。

**Interfaces:** Consumes: MediaMetadata、pkg、曲変更通知。Produces: 指定した2キーの型/値を安全に読む `LyricsMetadataProbe` と、確認済みキー/ID形式/対象packageの契約。一時的な値のログ出力はMediaWatcher内に置き、probe自体はログを出さず、161で恒久的な安全な読み取りに利用する。歌詞取得処理にはまだ渡さない。

- [ ] **Step 1: テスト**。`readsOnlyTwoAllowedKeys`(標準MEDIA_IDとAppleキーだけ)、`missingAndWrongTypeAreSafe`(欠落/異なる型は取得不可、落ちない)、`doesNotExposeLyricsOrOtherMetadata`(戻り値に本文や他の値を含めない)、`probeUnchangedForPositionUpdates`(位置更新10回で読み取り結果不変、ID変更で変化)。一時ログは曲メタデータの変化時のみ出し、出力箇所を除去対象として明記する。
- [ ] **Step 2: 失敗→実装→PASS**。将来allow: `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.LyricsMetadataProbeTest'`。既存DiagLogで2キーのみ記録、キーが同じでも曲IDが変われば記録。型を推測して強制変換しない。
- [ ] **Step 3: 人が実機確認**。Apple Musicのカタログ曲を最低3曲(確認済み Lemon ID 1537460612を含む)、別アルバム版、ローカル曲、他アプリ、曲送り・停止再開を比較。値・型・カタログIDの根拠・アプリ版を設計書に記す。数字だっただけでは合格にしない。ISRCも既知の供給元が確認できた場合だけ採る。未確認なら直接経路/ISRCを無効と記録できる。
- [ ] **Step 4: 人/司令塔がデータ形式を確認**。別途許可のもと全体索引の配列・ID/パス・重複/改訂例と、主旋律/背景/翻訳/agent/時間形式のTTML例を確認。本文をリポジトリへ転載せず、フィールド・namespace・架空例を本設計書へ記録。158/159の開始条件を満たさなければ止める。索引から取得先が確定しない場合は全体/am索引を再比較する。
- [ ] **Step 5: 司令塔レビュー**。採用キー/形式・非対応経路・後続TaskのInterfacesを確定しコミット。一時診断を正式版へ残さない除去対象として162へ引き継ぐ。

### Task 158: 全体索引の取得・前処理・厳格照合

**Files:** Create: `media/lyrics/AmllIndex.kt`、`AmllIndexStore.kt`、`AmllIndexTransport.kt`。Test/Create: 対応する `AmllIndexTest.kt`、`AmllIndexStoreTest.kt`、`AmllIndexTransportTest.kt`。

**Interfaces:** Consumes: 既存LyricsQuery、任意の検証済みISRC、時計・IO・差替えstream transport。Produces: `AmllTarget(folder, id)`、`AmllMatch`(Unique/NotFound/Ambiguous/Unavailable)、`AmllIndexStore.resolve(query, isrc)` と索引世代。実フィールド対応は157で確定したもののみ。索引更新と照合はsuspend、任意URLを返さない。

- [ ] **Step 1: テスト**。`normalizesNfkcCaseAndWhitespace`(全角Ａ/半角a、連続空白が一致)、`featRequiresSameContributor`(基礎曲名だけでは不可)、`doesNotSplitArtistOrStripVersion`(live/remix/複合歌手を勝手に同一視しない)、`albumAndIsrcConflictsReject`、`isrcWinsButTitleConflictRejects`、`uniqueWithoutAlbumOnly`(別パス2件はAmbiguous)、`duplicateTargetCountsOnce`、`unknownRevisionNeverUsesLastRow`。
- [ ] `refreshAt24Hours`(23:59:59.999で0回、24時間で1回、失敗後も同様)、`concurrentResolveDownloadsOnce`、`limitsAreInclusive`(受信/生成8MiBと行64KiBちょうど可、+1byte不可)、`invalidLineKeepsOldTable`、`interruptedWriteKeepsOldTable`、`staleTableStillResolves`、`noTableFailureIsUnavailable`、`onlyCurrentCandidatesRetained`(全索引JSON木を保持しない)、`rejectsUnsafeTarget`(../・任意host・未確認folder不可)、`redirectIsNotFollowed`、`cancelClosesAndDisconnects`。通信は全て偽物。
- [ ] **Step 2: 失敗を確認**。将来allow: `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.AmllIndexTest' --tests 'com.gt8d.launcher.media.lyrics.AmllIndexStoreTest' --tests 'com.gt8d.launcher.media.lyrics.AmllIndexTransportTest'`。
- [ ] **Step 3: 実装**。設計§3〜4、1行ずつ処理・原子的置換・試行日時を再起動後も保持。正常世代だけを公開。成功後の元索引と古い一時表を除去する。
- [ ] **Step 4: 同じコマンドでPASS**。件数/失敗数とサイズ境界を記録。司令塔レビュー・コミット。

### Task 159: XmlPullParser で主旋律を既存モデルへ変換

**Files:** Create: `media/lyrics/TtmlParser.kt`。Test/Create: `media/lyrics/TtmlParserTest.kt`。Read only: `Lyrics.kt`、`LrcParser.kt`、`ui/home/LyricsPane.kt`。

**Interfaces:** `TtmlParser.parse(xml, durationMs): TtmlParseResult`。結果は WordSynced(lines)/Unsupported/Invalid。外部通信なし。対応形式は157の資料と設計§5で確定する。UIとモデルを変更しない。

- [ ] **Step 1: 架空XMLのテスト**。`mapsLineAndWordStarts`(p=1秒、span=1/1.5秒→1000/1500ms)、`utf16OffsetsPreserveWhitespace`(`あ😀 B`のBは4)、`prefixDoesNotAffectNamespace`、`backgroundTranslationAndRomanizationExcluded`(除外後のtext/charStartも一致)、`agentIsNotDisplayed`。
- [ ] `clockAndSecondsTimes`(00:01:02.3→62300、1.025s→1025)、`rejectsInvalidAndOverflowTimes`(秒60、負、Long超過、frame/tick)、`preservesExplicitSpacesAndEntities`(架空のA &amp; Bを正しく復号)、`nestedTimedSpanRejected`、`wordOrderAndDuplicateTimesRejected`、`lineStartAndNextLineBounds`、`overlappingLeadLinesRejected`、`endValidatedButNotStored`、`noWordsIsUnsupported`、`plainLineWithinWordSyncedSongHasNoWords`。
- [ ] `doctypeAndExternalEntitiesRejected`(外部参照ゼロ)、`malformedXmlIsInvalid`、`depthLineWordTextLimits`(64/2000/20000/16384境界と+1)、`emptyLeadIsUnsupported`、`cancelStopsParsing`。RobolectricでAndroid標準parserを使い、外部XMLライブラリを足さない。
- [ ] **Step 2: 失敗→実装→PASS**。将来allow: `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.TtmlParserTest'`。単語を時間で並べ直してcharStartとの対応を変えない。件数/失敗数を記録。司令塔レビュー・コミット。

### Task 160: AMLL の取得・ソース別キャッシュ

**Files:** Create: `media/lyrics/AmllClient.kt`、`AmllCache.kt`、`AmllTransport.kt`。Test/Create: 対応する `AmllClientTest.kt`、`AmllCacheTest.kt`、`AmllTransportTest.kt`。Modify: `media/lyrics/Lyrics.kt`(LyricsIdentity/LyricsRequest追加のみ)。Read only: 既存LyricsCache/LrclibClient、158/159。

**Interfaces:** `LyricsIdentity(appleMusicId: String? = null, isrc: String? = null)`、`LyricsRequest(query, identity)`。`AmllClient.fetch(request): AmllResult` は状態・照合理由・ソース待機・保存可否を返す。`AmllCache.get/put(request, indexGeneration, result)` は設計§6のv1。UI公開状態はまだ変更しない。AMLL transportはTTML512KiBの有界streamとキャンセルを扱う。

- [ ] **Step 1: テスト**。`verifiedIdSkipsIndex`(直接成功は索引0回/TTML1回)、`unverifiedIdUsesIndex`、`direct404TriesIndexOnce`、`directIoFailureFallsBackWithoutIndex`、`isrcPrecedesTextMatch`、`wordlessOrAmbiguousNotAdopted`、`ttmlLimit512KiB`(ちょうど可/+1不可)、`hostWaitDoesNotAffectLrclib`、`retryAfterInvalidUses60Seconds`。
- [ ] `successRoundTripsWordsAndSource`、`negativeExpiresAt24Hours`(期限直前hit/ちょうどmiss、再起動後も同様)、`generationOrIdentityInvalidatesNegative`、`noNegativeOnNetworkOrMalformedData`、`prunesOldestAt300And32MiB`(301件目/容量超過)、`entryOver1MiBNotSaved`、`corruptEntryIsMiss`、`writeFailureDoesNotLoseFetchedLyrics`、`cacheKeySeparatesIdentity`、`legacyV3IsUntouched`。偽時計・偽通信で実施。
- [ ] **Step 2: 失敗→実装→PASS**。将来allow: `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.AmllClientTest' --tests 'com.gt8d.launcher.media.lyrics.AmllCacheTest' --tests 'com.gt8d.launcher.media.lyrics.AmllTransportTest'`。AMLL即時retry無し、成功は期限なし、改訂追従は今回範囲外。件数/失敗数を記録。司令塔レビュー・コミット。

### Task 161: 再生情報・優先順位・キャンセルの統合

**Files:** Modify: `media/NowPlaying.kt`、`media/MediaWatcher.kt`、`media/lyrics/LyricsRepository.kt`、`AppGraph.kt`。Test/Modify: `media/NowPlayingTest.kt`、`media/lyrics/LyricsRepositoryTest.kt`、`ui/home/LyricsPaneTest.kt`(追加のみ)。Test/Create: `media/LyricsIdentityTest.kt`。Read only: LyricsPane/HomeMiniPlayer/MediaCardとv2.0の層・背景実装。

**Interfaces:** Consumes: 157の確認済みidentity変換、160のrequest/client、既存LRCLIB/cache。Produces: NowPlayingまでidentityを既定値付き伝播、Repositoryのソース別評価、既存 `StateFlow<LyricsState>`。AppGraphがアプリ全体で1つの索引/待機/キャッシュを共有する。未確認ID供給元はnull。

- [ ] **Step 1: テスト**。`identitySurvivesSnapshotMapping`、`unknownPackageAndConflictingIdsRejected`、`identityChangeRefetchesButPositionDoesNot`。直接経路を無効とした場合はnullになることを試験し、後日推測で有効化しない。
- [ ] Repository: `amllUpgradesLegacyLrclibCache`(v3 Synced/Plain/MissingがあってもAMLL採用)、`amllFailureUsesLrclibCache`、`sourcePriorityIsWordThenSyncedThenPlain`、`wordlessAmllUsesLrclib`、`transientAmllFailureIsNotOverallMissing`、`cacheableFalseIsPreserved`、`negativeDoesNotPoisonOtherSource`、`offMakesZeroRequestsIncludingIndex`、`offDuringReadDisconnects`、`lateResultFromAToBToAIsDropped`、`cancelDoesNotWriteCache`、`debounce600ms`(599msで0回/600msで1回)、`retry10Then30Then60Seconds`、`hostCooldownsAreIndependent`、`noAmllUpgradeTimerAfterLrclibSuccess`。
- [ ] UI追加: `ttmlWordsUseExistingFill`(開始1000/1500ms、文字位置0/2なら1250msで位置1)、`ttmlEndDoesNotChangeFillModel`、`ttmlPlainLineHasNoPerFrameLoop`。既存observerで毎フレームのcompositions/measures/placements増分0、既存リリースsemantics試験を維持。UI変更が必要と判明した場合は勝手に範囲を広げず司令塔が指示書/allowを改訂する。
- [ ] **Step 2: 失敗→実装→PASS**。将来allow: `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.*' --tests 'com.gt8d.launcher.ui.home.LyricsPaneTest'`。この時点で全テストは行わない。世代有効性を表示と保存の両方に適用。既存テストの件数/失敗数とobserver差分を記録。司令塔レビュー・コミット。

### Task 162: beta・GT8D実機・一時診断除去

**Files:** Modify: `app/build.gradle.kts`(版のみ)、`README.md`、`docs/install-v1.6.md`(現行READMEからリンクする導入手順)、本設計書/本計画。Modify: `media/MediaWatcher.kt`(157の一時的な値のログ出力だけ除去)。安全な読み取りを担う `LyricsMetadataProbe` とそのテストは残す。その他の機能修正は別途指示書/allowを用意する。

**Interfaces:** Consumes: 161合格、配布履歴、人の実機計測。Produces: `<次の版>-betaN`、増加versionCode、実機記録、一時ID値ログのない正式版候補。

- [ ] **Step 1: 文書と版準備**。README/導入手順に取得元リンク・歌詞作者への感謝、外来データの条件、オン時のGitHub/LRCLIB通信とオフで停止する説明を追加。UIラベルは追加しない。司令塔が未決と版を確定する。
- [ ] **Step 2: 一時診断を除去し関連回帰**。値のログ出力を除去し、試験は削除/緩和しない。将来allow: `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.*'`。診断除去後の成果物を使う。
- [ ] **Step 3: beta作成**。将来allow: `/home/munde/tools/gradle-run.sh :app:assembleDebug :app:assembleRelease`。人が実機導入、リリース相当のsemanticsも確認する。codexはadbを実行しない。
- [ ] **Step 4: 人が実機確認**。AppleカタログID有効/無効・他アプリ索引・未収録/曖昧・旧v3からの格上げ・初回/再起動/再取得無し・索引更新失敗・通信断/429・オフで通信停止・高速曲送り/停止/シーク・背景/翻訳除外・UTF-16文字・ホーム/分割を確認。end非保存による休符の近似を確認し、音源が違う候補を許可しない。
- [ ] **Step 5: 性能**。v1.7.1/v2.0と同じ条件で60秒×3回、初回索引更新中とキャッシュ時を分け、フレーム間隔p50/p90/p95・jank・UI/RenderThread/GPU・メモリピーク・通信回数を記録。+5ms/+5ポイント以内、層/semanticsの退行無しを確認。測れない値は未測定と明記し合格扱いしない。
- [ ] **Step 6: 不合格なら修正範囲を別途許可して対象試験→増加beta→実機再確認**。司令塔が未決の解消/見送りと最終合格を記録・コミット。

### Task 163: 正式版・全テスト・main・タグ・リリース

**Files:** Modify: `app/build.gradle.kts`(確定した正式版と増加versionCode)、README/導入手順、本設計書/本計画(最終状態/検証結果)。新規機能/新規テストなし。

**Interfaces:** Consumes: 162の実機合格・一時診断除去・未決解消または明示的見送り。Produces: 検証済み確定差分・正式版成果物・main・タグ・リリース(司令塔が実施)。

- [ ] **Step 1: 正式版準備とレビュー**。版番号を確定し、機能差分が実機合格したものと同じことを確認。README/導入手順の取得元・謝辞を確認する。
- [ ] **Step 2: 最後の全テストと成果物**。将来allow: `/home/munde/tools/gradle-run.sh testDebugUnitTest :app:assembleDebug :app:assembleRelease`。全モジュールのため`--tests`なし。実行件数・失敗数・ビルド結果を記録。失敗は無視せず修正に必要な許可を整え、影響に応じて実機も再確認する。
- [ ] **Step 3: 司令塔がmainへ取り込む**。競合解消で機能差分が生じれば対象検証/実機確認を先に行う。
- [ ] **Step 4: 司令塔が確定コミットへ `v<次の版>` タグ**。成果物・版・コミットの対応を確認。
- [ ] **Step 5: 司令塔がリリース**。AMLL取得元と謝辞、対応範囲、end/音源差の制約、オフ時通信無し、実機確認範囲を記載。

最終順序は **ベータ版で実機確認 → 全テスト → main → タグ → リリース**。本Task 156では上記コマンド、ネットワーク通信、コード変更は一切実行しない。
