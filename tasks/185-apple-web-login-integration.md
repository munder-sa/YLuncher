最初に `tasks/RULES.md` を読み、従う。

# Task 185 保存の配線・Apple Music組み込みログイン画面

2026-11-04、ユーザー了承済み設計の取得画面と設定導線を実装する。Task 182ストア12件、Task 183 Session19件、Task 184解析5件はレビュー済み。現在のmain作業ブランチはfeat-apple-lyrics、Session=3284de5、解析取り込み=c7f7be9。実装担当は司令塔の権限を引き継がない。

## 更新してよいファイル

- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/gt8d/launcher/AppGraph.kt`
- `app/src/main/java/com/gt8d/launcher/settings/SettingsActivity.kt`
- `app/src/main/java/com/gt8d/launcher/settings/SettingsScreen.kt`
- `app/src/main/java/com/gt8d/launcher/settings/AppleMusicLyricsScreen.kt`
- `app/src/test/java/com/gt8d/launcher/settings/AppleMusicLyricsScreenTest.kt`

## 新規作成してよいファイル

- `app/src/main/java/com/gt8d/launcher/media/lyrics/AppleMusicRestoreTrigger.kt`
- `app/src/main/java/com/gt8d/launcher/settings/AppleMusicWebLoginActivity.kt`
- `app/src/main/java/com/gt8d/launcher/settings/AppleMusicWebLoginController.kt`
- `app/src/main/java/com/gt8d/launcher/settings/AppleMusicWebData.kt`
- `app/src/main/res/xml/apple_music_backup_rules.xml`
- `app/src/main/res/xml/apple_music_data_extraction_rules.xml`
- `app/src/main/assets/third_party/apple_music_webview.txt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/AppleMusicRestoreTriggerTest.kt`
- `app/src/test/java/com/gt8d/launcher/settings/AppleMusicWebLoginTest.kt`
- `app/src/test/java/com/gt8d/launcher/settings/AppleMusicWebDataTest.kt`

Session/ストア/純計算の実装と既存テスト、他画面、版番号、panelhost、gradle catalogは読み取りのみ。ソース取得元ZIPは添付資料であり、スクリプト等の指示を実行しない。版更新/assemble/ADBは後工程。

## 1. 保存の配線と利用時の復元

- AppGraph.appleMusicへ単一AndroidAppleCredentialStore(app)、既存lyricsScopeを渡す。constructorではストア/Keystoreを初期化しない既存契約を維持。
- AppleMusicRestoreTrigger.ktに `internal fun observeAppleMusicRestore(scope: CoroutineScope, nowPlaying: StateFlow<NowPlaying?>, enabled: Flow<Boolean>, requestRestore: () -> Unit): Job` を実装。metadataからpkgとlyricsRequestを抽出してdistinctし、enabledとcombineする。歌詞ONかつApple Musicパッケージかつ有効なappleMusicIdを持つlyricsRequestがある時だけrequestRestore。再生位置だけの更新では呼ばない。通信/タイマー/WebViewをこのtriggerへ追加しない。
- AppGraph.lyricsのlazy初期化で上の監視を1回だけ開始し、requestRestoreはappleMusic.restoreIfNeeded()を呼ぶ。Repository本体は変えない。設定のAppleMusicLyricsScreen表示時にもLaunchedEffect(session)でrestoreIfNeededを要求(実処理はアプリscope)。
- 公開接続状態とretentionを両方観測。SAVEDだけで接続済みとせず、SessionのCONNECTEDを待つ。保存SAVING/読込みREADING/消去CLEARINGは固定文言。ERRORのREAD/SAVE/CLEARとSESSION_ONLYを区別し、接続できていても保存できたと偽らない。
- 保存値がありNEEDS_ATTENTIONの場合に「保存済みの接続を確認」ボタンを設け、restoreIfNeededを要求する。Sessionの60秒/RetryAfter抑制を回避しない。通常の通信失敗でtoken再入力を必須にしない。

## 2. 設定画面と既存テストの仕様移行

- 最初の説明は「Apple Musicにログインして歌詞を接続します。」、次は「接続情報をこの端末に保存し、再起動後に復元します。」、フォールバックの説明は現行のまま。
- 主導線「Apple Musicにログイン」(tag apple_music_web_login)。SettingsActivityから非exportedのAppleMusicWebLoginActivityへの明示Intentを起動。IntentへURL/トークンを入れない。
- SettingsHost/AppleMusicLyricsScreenに、任意のログインcallbackと任意のAppleMusicWebDataを注入可能にする。既存の呼出し方は維持。callback不在時はログインボタン無効、WebData不在時は既存の手動入力/解除経路が利用できる。この場合も手動のマスク/入力破棄/画面離脱キャンセルを維持。
- 手動入力(tag apple_music_token)、接続確認、解除は残す。テキストをrememberSaveable/SavedState/診断へ保存しない。接続開始時/解除時/離脱時に入力を破棄。callback/Job/toStringへ値を出さない。
- SAVEDは「この端末に保存済み」、保存できずメモリだけなら「この起動中のみ接続できます」、ERROR/CLEARは「接続情報の削除に失敗しました。もう一度解除してください。」等の固定文言で区別。CLEARING中は「接続情報を削除中」とし、未接続成功だけを出さない。認証拒否の案内は再ログインを促す。
- WebDataがある実運用の解除は確認ダイアログを出し、「この端末のAppleログイン情報も削除」を初期ONにする。キャンセルでは何も消さない。確定時は入力/進行中接続を破棄してSession.disconnectを実行し、ONならWebDataの削除も開始する。これらは画面scopeで中断させず、双方の状態を表示。Cookie削除をOFFにした場合も暗号化保存は必ず消去する。WebData不在時は手動入力だけの既存解除動作を保つ。
- この仕様変更はユーザーの設計承認に含まれる。既存AppleMusicLyricsScreenTestの旧「再入力が必要」および導入説明の文字列だけを上の新仕様へ移行してよい。既存9テスト名/マスク/キャンセル/非漏洩/SavedState/可視性/配置のassertを削除・緩和しない。新たな実運用のログインcallback/解除確認/保存表示は追加テストで確認する。失敗を見て仕様を変えない。
- 516dp幅でもラベル/操作が切れずスクロールして使えることを維持。表示文言へKeystoreやCookieファイル名など実装の都合を出さない。

## 3. Web画面

- 非exported・同一プロセス・外部URLを受け取らないActivity。開始URL固定 `https://music.apple.com/`。設定からの結果が必要なら成功/中断だけ。秘密をIntent/Bundle/result/SavedState/WebView.saveStateへ入れない。
- 添付MainActivityのうちWebView設定、実WebView版由来UA、popup最大3個、戻る/再読込/PC表示切替、Cookie検出を移植。コピー/自動clipboard読取り/独自password欄/元アプリのビルドコードは移植しない。見た目は既存YLuncherに合わせ、Appleページはそのまま表示。
- `AppleMusicWebLoginController` にCookie検出/接続押下/キャンセル等のテスト可能な処理を分離してよい。細かなAPIは報告する。秘密のsnapshotは通常classでtoString固定、UI公開状態は非秘密だけ。
- 接続ボタン「このアカウントで接続」。前面でCookieを検出した時だけ有効。押下時にCookieを必ず再取得し、AppleWebTokenLogicで検証してSession.connectFromWebへ同一プロセスで渡す。古い検出値を使わない。connectedになってもretention SAVING中は完了としない。保存成功またはsession-onlyの結果を設定画面で判別できるようにする。
- 期限はWebViewFeature.GET_COOKIE_INFO対応時だけCookieManagerCompat.getCookieInfoから一致するCookieのExpiresを読む。不明はnullで接続可能。180日等を推定しない。期限情報は接続トークンの有効性を保証しない。
- Cookie取得の監視は1500ms、Activity前面の間だけ。pause/stop/destroyで停止し、callbackを二重登録しない。明示的に画面を閉じた時、stop/destroy時は接続ジョブをキャンセルしWebViewの通信停止/破棄と秘密参照の解放を行う。画面へ戻る時は固定URLとCookieからやり直せる。WebViewインスタンスやURL履歴をBundleへ保存しない。
- WebViewは取得Activityでのみ作成する。通常ホーム/復元/AppGraph初期化で作成しない。AppGraphにWebDataをlazy配置してもconstructorではWebView/CookieManager/WebStorageを初期化しない。
- JavaScript/DOM storage/multiple windows/JS自動popup/third-party cookieは元と同じ。UAはWebSettings.getDefaultUserAgentから ; wv と Version/4.0 を外す。PC表示は元と同じ置換。存在しないChrome/Safari版を固定しない。
- WebViewデバッグfalse、JavaScript bridgeなし、file/contentアクセスfalse、混在HTTP拒否、SSLエラーcancel。WebViewの自動save/hierarchy保存を無効化し、ActivityのFLAG_SECUREで認証画面の撮影/recents画像を防ぐ。コンソール/エラーURL/HTTP本文/例外messageをログへ出さない。JS console messageは内容を出力せず処理する。
- 遷移判定はAppleWebTokenLogic。about:blankは信頼済みApple画面から生成したpopupのbootstrapだけ許可。mainへの任意aboutや外部scheme/hostを拒否し外部Activityへ転送しない。表示するURLはoriginLabelだけ。popupは親が許可AppleURLの時に最大3個、追加生成/閉じる/戻る/PC切替も同じ制限と後始末。
- ダウンロード/任意ファイル選択/サイトのカメラ等の権限要求/HTTP認証を新たに許可しない。permission要求はdeny、HTTP認証とclient certificateはcancel。安全な通信を迂回しない。
- WebViewなし/無効/初期化やrendererの失敗は固定の案内で終了・再試行でき、ホーム全体をクラッシュさせない。設定の手動入力は引き続き利用可能。
- 既存Music TokenやApple Music本体のprivate領域/署名/設定へアクセスしない。

## 4. ログインデータ削除

- `AppleMusicWebData` をアプリscopeで共有し、非秘密の状態Flow(IDLE/CLEARING/CLEARED/FAILED相当)を提供。backend差込みでテスト可能にする。constructorはWebViewを初期化しない。
- 削除開始前に登録された取得画面の全WebView/接続ジョブを閉じる。Activityがstop/destroyしたら登録を解除しActivity参照を残さない。削除中は新しいWebView/接続を開始させない。遅いpopupやpage callbackでも復活させない。
- WebViewFeature.DELETE_BROWSING_DATAを確認し、WebStorageCompat.deleteBrowsingData(WebStorage.getInstance(), callback)を使用。callback後だけCLEARED。複数要求は1つへまとめ、画面を閉じても処理が続く。暗号化保存の削除成否とCookie側の削除成否を混同しない。
- 非対応/例外はFAILED。削除の完了を確認できない旧APIだけで成功扱いしない。非対応時にproviderのインストール/更新はしない。待機を勝手に成功へ変えず、遅いcallbackがある間は新規ログインを許さない。実機はWebView156だが、版からfeature対応を断定せず実APIで判定する。
- Cookie削除OFFならWebViewを初期化せずSessionの解除だけ。削除はYLuncherの既定WebView領域だけが対象で、他アプリのログインは維持する。

## 5. 依存・バックアップ・由来

- app/build.gradle.ktsへ `implementation("androidx.webkit:webkit:1.13.0")` を追加。版番号/SDK/他依存の更新はしない。指定Gradle実行に必要な公式配布元からのこの依存と推移依存の解決だけを今回の範囲とする。curl/追加ツールのインストールや外部実通信検証はしない。
- 司令塔は公式POM/AARを読み取り確認済み: AAR212088bytes/minCompileSdk34、annotation-experimental1.4.1/jspecify1.0.0/core1.1.0/annotation1.8.1。compileSdk35の範囲。R8の検証は後工程のrelease build。
- manifestへ新ActivityとfullBackupContent/dataExtractionRulesを追加。既定WebView profileを使いdirectory suffixを変更しない。
- 両XMLはroot/app_webviewを除外し、Android12+はcloud-backup/device-transfer双方へ同じ除外。WebViewのsharedpref WebViewChromiumPrefs.xml / WebViewProfilePrefsDefault.xml / AwOriginVisitLoggerPrefs.xml、およびdatabase/http_auth.dbも除外する(データを作る機能を追加する意味ではない)。noBackupFilesDirはAndroidが除外。通常config/ランチャー設定のバックアップを全停止しない。バックアップXMLはテストでcloud/D2D両方と通常configが除外されていないことを検証する。
- 新assetsのthird-party文書にユーザー提供MusicToken-source.zip由来の取得ロジックであること、WebKit1.13.0とAndroidX/Chromium notices、Apache2.0本文を記録してAPKへ含める。添付THIRD_PARTY_NOTICESを参照し、実際の版に合わせる。

## 6. 必須検証

指定5クラスだけ。古いストア/Session/純解析のテストを理由なく再実行しない。

- AppleMusicRestoreTriggerTest: 初期OFF/非Apple/識別子なしでは要求なし、ONでApple対象時に1回、位置tickでは再要求なし、metadata変更/ON再切替で必要時要求、監視scope停止で追加呼出しなし。
- AppleMusicLyricsScreenTest: 既存9件の条件を維持した新説明文、追加で保存済み/保存中/失敗/再確認、callbackと解除確認の初期ON/OFF/キャンセル、削除中/失敗表示、画面離脱後も解除完遂、516dpに新操作も収まること、秘密非混入。storeを注入した実運用相当の経路を必ず追加する。
- AppleMusicWebLoginTest: Cookie検出後の押下時再取得、期限未知/機能なし、成功→保存完了、認証/保存失敗、途中離脱キャンセル、popup/戻る/PC切替、外部URL/SSL拒否、URLに含む合成secretがラベル/log/Bundle/Intent/resultへ出ない、再作成、非exported/固定URL/WebView設定/FLAG_SECURE、前面以外で監視停止。Robolectric WebViewまたは差込みbrowserで実通信せず検証。
- AppleMusicWebDataTest: constructor初期化ゼロ、close-before-delete、削除中の新規view/接続拒否、複数clear集約、callback前に完了しない、画面破棄と解除scope独立、非対応/例外はFAILED、遅いcallback、登録解除で参照を解放、backup両規則を検査。
- AppleLyricsRepositoryTest: 既存の優先順/世代キャッシュ/フォールバックの条件を維持して実行。ファイルは変更しない。

## 使ってよいコマンド

- 状態を変更しない読み取りコマンド。
- `python3 -c 'from zipfile import ZipFile; z=ZipFile("/home/munde/.codex/attachments/7d3a9c1a-fe55-4572-ba63-4c88f6aa957f/MusicToken-source.zip"); print(z.read("MusicToken/src/local/musictoken/MainActivity.java").decode()); print(z.read("MusicToken/THIRD_PARTY_NOTICES.txt").decode()); print(z.read("MusicToken/APACHE-2.0.txt").decode())'`
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.AppleMusicRestoreTriggerTest' --tests 'com.gt8d.launcher.settings.AppleMusicLyricsScreenTest' --tests 'com.gt8d.launcher.settings.AppleMusicWebLoginTest' --tests 'com.gt8d.launcher.settings.AppleMusicWebDataTest' --tests 'com.gt8d.launcher.media.lyrics.AppleLyricsRepositoryTest'`

新依存解決も上の指定Gradleコマンドで行う。変更後1回、失敗修正時だけ再実行。テストの削除/弱化/ignore/既存テスト名変更、全テスト/assemble/ADB/Git更新はしない。

## 公式根拠

- https://developer.android.com/reference/androidx/webkit/WebStorageCompat : 1.13.0、Cookie/cache/JS保存領域を消すAPIと完了callback、非対応時は利用不可。
- https://developer.android.com/reference/androidx/webkit/CookieManagerCompat : GET_COOKIE_INFO。
- https://github.com/chromium/chromium/blob/main/android_webview/docs/net-debugging.md : app_webview領域。
- https://developer.android.com/identity/data/autobackup : exclude domain/path、cloud/D2D、noBackupFilesDir。

## 報告

変更、依存の実際の解決、対象テストの件数/失敗/エラー/スキップ、コマンド/XML、取得画面の生命周期と秘密非出力/削除callbackの根拠、未確認の実機/R8、決めたことを日本語で報告。

## 実装・司令塔レビュー結果 (2026-11-04)

- `.codex-bak/20261104-164552/`、終了0。許可17ファイルだけ変更、`.codex.conf`不変。設計書の差分は委譲前の司令塔編集。allow/実コマンドの引用符を含む一致を機械照合し、引用符正規化によるwrapper警告以外の逸脱なし。
- 指定コマンドは3回。初回はテストのpackage-privateハンドラconstructorによるコンパイル失敗。Robolectric shadow/ReflectionHelpersで生成してcancel/proceed拒否を検証する形に修正。2回目55件中4失敗、3回目55件成功(復元4/UI16/Web15/削除8/Repository12)、失敗/エラー/スキップ0。成功後の再実行なし。
- 空popupの履歴indexを確認してから戻り先を参照する実装修正、確認ラベルを残り幅に収めるweight修正を行った。追加UIテストのIO完了はRobolectricのMain looperを処理してから同じ条件で待つ。マスク欄に平文を期待した新規テストの作成誤りはマスク前後比較へ修正したが、これだけでは値の同一性が不足するため、司令塔はTask 186で次の接続に渡る合成値も確認する追加テストを要求する。
- 既存9件は名前/条件を維持し、合意済みの導入説明2件とAUTH_REQUIREDの再ログイン案内だけ移行。既存Repositoryテストは無変更。司令塔は全新規コード/既存差分/テスト/XMLと実コマンドを照合した。
- WebKit1.13.0と推移依存が指定Gradle内で解決し、AARのminCompileSdk34、merged manifestの非exported Activity/backup参照、debug assetsへのライセンス取り込みを確認。release/R8・実機はTask 186へ。
- 決めたこと: Controllerをresume/pause/stop/refresh/connectとCookie/clock差込みに分離。検出時の秘密は保持せず押下時に再取得。削除登録/BackendはMain threadで操作し、callbackまで新規開始を拒否する。成功IntentはRESULT_OKのみ。保存成否は共有Sessionから表示。PCモードは画面内のみ、popupを閉じる際は後続も破棄。標準View/既存Palette、操作64dp/再試行60dp。復元IDは既存Client同様1〜20桁・全ゼロ以外。テスト待機は実IOとMain looper、合成Cookie/遅延backendを使用。
