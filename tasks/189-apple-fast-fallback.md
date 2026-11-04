最初に `tasks/RULES.md` を読み、従う。

# Task 189 Apple通信のFast Fallback・beta6

## 承認と根拠

**再開時の確定変更: 依存3本を5.3.2へ変更する。下の当初5.5.1記述よりこの追記を優先する。** 5.5.1 Android AARはminCompileSdk37が必要で、初回検証はcheckDebugAarMetadataで失敗。司令塔が公式AARを読み、5.4.0は36、5.3.2はminCompileSdk1/AGP1.0.0と確認した。5.3系の最新は5.3.2。OkHttp/MockWebServer/TLSを全て5.3.2、Okio推移依存は3.16.4、stdlib2.2.21。既存compiler2.1.0での互換性は指定テストのコンパイルで確かめ、不整合をforce/skipで隠さない。SDK/AGP/compilerの変更やAndroidにJVM artifactを強制することは不可。

公式の5.3.2ソース資料は `/tmp/yluncher-task189-reference-5.3.2`。司令塔はFastFallbackExchangeFinderの250ms競争・retryOnConnectionFailureから独立した経路試行、RetryAndFollowUpInterceptorの503 Retry-After0再送とProtocolExceptionの非再送をこの版でも照合済み。既存実装/追加テストを継続し、テストの内容や期待値は弱めない。noticeの版/推移依存/実Android artifact表記も対応させる。下の5.5.1資料は経過参照にとどめる。

ユーザーは「通信側の対策も今回進める」を承認済み。Apple歌詞だけをOkHttp 5へ移行し、IPv6失敗待ちを減らす。実機SIM OFFでもbeta5初回は公開ページの最初の301まで20,225ms、公開refresh全体22,721ms、account APIで全体35,007ms TIMEOUT。公開ページ解析20ms/asset54msで解析負荷ではない。端末curl -4は公開301/0.728秒、API root404/0.240秒、-6はいずれも接続未成立で6秒TIMEOUT。設定再表示の保存値再確認はaccount200/10,266ms・jp/SAVEDへ成功。秘密値は取得/記録していない。

## 更新可能

- app/build.gradle.kts
- panelhost/build.gradle.kts
- app/src/main/java/com/gt8d/launcher/AppGraph.kt
- app/src/main/java/com/gt8d/launcher/media/lyrics/AppleLyricsTransport.kt

## 新規作成可能

- app/src/main/java/com/gt8d/launcher/media/lyrics/AppleLyricsTransportPolicy.kt
- app/src/main/java/com/gt8d/launcher/media/lyrics/AppleLyricsOkHttpTransport.kt
- app/src/test/java/com/gt8d/launcher/media/lyrics/AppleLyricsOkHttpTransportTest.kt
- app/src/main/assets/third_party/apple_music_http.txt

既存テスト全て、Client/Provider/Session/UI、他の依存/ツール/設定は変更不可。実装担当はADB/Gitを行わない。

## 依存・版

- appだけ `implementation("com.squareup.okhttp3:okhttp:5.5.1")`。公式Maven release metadata 2026-10-22/5.5.1を司令塔確認済み。JVM POM依存はkotlin-stdlib2.1.21/okio-jvm3.18.1。Okioもstdlib2.1.21。既存compiler2.1.0と同系列。compiler/AGPなどを変更しない。解決/コンパイル不整合なら強制/skipで隠さず報告。
- テストのみ必要なら `mockwebserver3:5.5.1` と `okhttp-tls:5.5.1` (group com.squareup.okhttp3) を追加してよい。公式POM確認済み、両者も同系列依存。別の依存は追加しない。
- 本体/中継を46 / 2.3.0-beta6へ揃える。panelhostは版だけ。
- APK assetsの新noticeにOkHttp/Okioの名称/版/公式URL/Apache-2.0とライセンス本文を記録。他のnoticeは維持。公式ソース/ライセンスの読み取りは可、取得物や添付スクリプトを実行しない。

## 設計

1. 旧AppleLyricsUrlConnectionTransportは残し、既存6テストを無変更で維持。validateRequest、bounded readBody、Retry-After解析などの共通規約をinternal AppleLyricsTransportPolicyへ抽出し、旧/新が同じ検証を使用する。機能を変えない最小の抽出とし、旧transportの取消/解放構造は変えない。不要な抽象化や全媒体への展開はしない。
2. 新AppleLyricsOkHttpTransportがAppleLyricsTransportを実装。AppGraphのApple用transport生成1箇所だけ差替え、Client/Provider/Sessionと同じインスタンスを共有。通常ホームで余分なWebViewや事前要求を起動しない。
3. 固定本番client: fastFallback(true)、connect/read/write各5秒、call全体は既存上位35/30/5秒の取消を利用(追加の期限延長なし)。IPv4固定/端末設定変更/別DNSサービス/独自証明書検証は行わず、OSのDNS、標準TLS証明書/hostname検証とプロキシ選択を維持。cookieJar NO_COOKIES、cache null、authenticator/proxyAuthenticator NONE、followRedirects/followSslRedirects false、ログInterceptor/EventListenerなし。自動HTTP要求の再送は増やさない。
4. Fast FallbackのTCP接続競争とHTTP再送は区別する。5.5.1の公式FastFallbackExchangeFinderは250ms間隔でTCP接続を競争、負けを取消。retryOnConnectionFailure(false)でもTCP競争自体は有効。HTTP408再送を止めるためfalseを設定する。
5. **注意: OkHttpはretryOnConnectionFailure(false)でも503 + Retry-After:0を自動再送する**(公式RetryAndFollowUpInterceptorで司令塔確認)。これも止める。案を具体化: 本番clientのnetwork interceptorで非200応答のcode/Retry-After/公開host時のLocationだけを既存policyでDTO化し、responseを確実にcloseした上で、固定messageのprivate ProtocolException派生(秘匿した結果を運ぶ専用型)として抜ける。ProtocolExceptionはOkHttpの再試行対象外。get側でその専用型だけを通常DTOへ戻す。response bodyは空で、エラー本文をアプリから読まない。外部へ専用例外/元Request/Response/Throwableを漏らさない。非200をnormal callbackで受けた合成factoryでも同規約にする。上位の公開リダイレクトと認証更新は従来のClient/Providerだけが判断する。レスポンスの実code/Retry-After/Locationを改変しない。
6. 既存policy: https・許可2hostだけ・port既定/443・userinfo/fragmentなし・URLの厳密検証、重複/不正header拒否、Cookie禁止、Authorization/media-user-tokenはAPI host限定、Accept-Encoding identity固定。max1..8MiB、成功200だけbodyを上限+1で打ち切るUTF-8読取、上限超は空body/tooLarge。公開host以外のLocationは無視。Retry-Afterの数値/HTTP-date/桁溢れ/未来時刻の扱いを維持。Request.Builderなどが投げる例外も秘密を含み得るので外へ未処理で出さない。
7. 実要求はIO。コルーチン取消でCall.cancelとBody/Response closeを行い、接続待ち/headers待ち/body待ち/EOF/close/復帰dispatchの境界で取消を結果へ変換しない。close例外を理由に取消を失わない。response受領前後の取消レースでも必ずclose/cancelし、Job/Thread/streamを取り残さない。既存のResultを介した例外再throw等の秘匿方針を参照。生IO例外のmessage/cause/suppressedを外へ出さず固定IOExceptionへ変換。ログ/toString/stackへトークン/本文/URLを出さない。
8. テスト用Call.Factory/clock等の注入はinternalで可。本番client構築をinternal関数などで分離し、テストで実設定をassertできること。再利用clientのpoolを毎回破棄しない。callback経由かexecute+既存suspendCancellableCoroutine方式かは契約を満たす方を選び、報告へ記載する。

## 検証

- 新クラスの合成テスト: 不正URL/headerがnewCall前に拒否、正常header/no-cookie/no-cache/標準TLS設定、body境界/UTF-8/上限+1、非200のbody非読取・Location制限・Retry-After、例外の秘密非漏洩、call/response解放、取消の各境界/返却dispatch。既存TransportTestの各ケース群を参考に、重要境界を落とさない。
- 実OkHttpを使うloopbackテストで、503/Retry-After0、408、401、redirectが1 HTTP要求だけで返り、変更されたcode/retryを隠さないことを確認。テスト用TLS/DNS/ポート変更はlocalhost限定で、本番clientに弱いSSL/ホスト検証を混ぜない。例: 本番client.newBuilderへ合成DNS/テスト証明書を与え、注入Call.Factoryの中だけvalidated URLのportをloopbackのportへ置換。これにより本番validateの443制限を緩めない。
- Fast Fallbackは設定assertだけにしない。合成SocketFactory/DNSでIPv6のconnectをlatch待機させ、IPv4 loopbackがIPv6の解放を待たず完了できること/負け接続closeを検証(外部サイトへ接続しない、時間待ちのみに依存せずlatchで順序を見る)。できない前提があれば推測で成功扱いしない。
- 既存Transport6件は無変更。新テストとこの6件だけ実行。コード変更なしの成功後再実行/全テスト不可。

## 許可コマンド

- 状態を変えない読み取り。公式サイト/API/ソースの読み取りは許可済み。
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.AppleLyricsTransportTest' --tests 'com.gt8d.launcher.media.lyrics.AppleLyricsOkHttpTransportTest'`
- `/home/munde/tools/gradle-run.sh :app:assembleRelease :panelhost:assembleRelease`

報告は日本語。変更/共通policyの同一性/本番client設定・取消・HTTP再送防止・Fast Fallback検証/正確な実行結果と件数・失敗・skip・版/決めたことを記録。実機で初回復元が直ったかは司令塔が後で検証するので未確認と書く。

## 初回停止と権限の具体化

- 初回 `.codex-bak/20261104-181602/` はwrapperの古い一般的な外部接続禁止と新依存取得の矛盾を報告。無変更/未実行で終了0。
- ユーザーの今回の「通信側の対策も今回進める」はOkHttp5追加を含む明示承認。既存codex-runのCODEX_NOTEで、allowのGradle2コマンドがこの固定依存と推移依存を公式repositoryから解決することだけを、一般禁止の例外として明記する。全体ネットワーク解禁/別のインストールへ広げない。wrapper/.codex.conf/forbiddenルール自体は変更しない。
- 司令塔が公式Maven sources.jarを読み取り資料として `/tmp/yluncher-task189-reference/{okhttp,mockwebserver,tls,okio}` へ展開。実装担当はこの参照資料を読み取りだけで使える。取得物のスクリプト実行は不可。
- 2回目 `.codex-bak/20261104-182029/` は許可8ファイル実装後、指定テストコマンド1回18秒でcheckDebugAarMetadata失敗。テスト実行0件/release未実行、旧6件/.codex.conf無変更。最新Android AARのSDK37要件が原因。司令塔は依存解決情報・ソース・12件の新テストを読んで、上記互換版へ指示を限定更新した。CODEX_RESUME=1で同じ8ファイルをバックアップ付き更新として再開する。

## 最終実装と司令塔レビュー

- 再委譲 `.codex-bak/20261104-183316/` 終了0。許可8ファイルのみ、SDK/AGP/compiler/既存テスト/.codex.conf無変更。OkHttp3依存5.3.2、推移Okio3.16.4。Android artifactを使用し、JVM強制やmetadata検査回避なし。
- 指定テストコマンド: 1回目2分11秒はテストfixtureのsealed interfaceコンパイル失敗で実行0件。匿名実装をJVM proxyへ置換して入力Streamの観測を維持。2回目1分19秒は18件中1失敗。HTTP/1.1の異なるhostが各接続を持つことを公式ソースと照合し、1hostの再利用検査を2hostを使う応答検査より前へ移動。比較値/検証項目を変更せず、3回目26秒で旧6+新12=18件成功、失敗/エラー/skip0。
- 指定releaseコマンド1回1分19秒で成功。司令塔は実コマンド/XML/全文/共通policyの抽出差分と取消構造、no-cookie/no-cache/標準DNS・TLS/非200のProtocolExceptionによるHTTP再送抑止をレビュー。引用符allow警告は既知の正規化差だけ、成功後再実行なし。
- 決めたこと: IO上のexecute+suspendCancellableCoroutineを維持。AtomicReferenceでCall/Responseを管理し取消はcancel→close、戻り境界でも取消優先。注入はinternal Call.Factory/clock、本番clientはcreateClientへ分離。非200はbody空DTOだけをprivate固定message例外で運ぶ。テストはloopback TLS、合成DNS/SocketのlatchでIPv6の解放前にIPv4接続成功/負けsocket closeを実証。テストfixture2修正の理由は上記。
- 両metadata 46 / 2.3.0-beta6。apksigner verify終了0・従来同署名SHA256 `44d8c612bd3948e265840f28adff0f820d5893d0ba24d888b041ca16544f7f6d`。
- app3,340,574bytes / SHA256 `603a0f0ab22a3e810c9a8e38139c9ae53409380c58b9155ae76ab7d542cdaab7`、前版比+301,736bytes。
- panel2,021,093bytes / SHA256 `a21af7342c4d795b1bf0dad4bd957b1da438256cb1fb01312089f0482531bfde`。
- APKのHTTP notice11,889bytes、版5.3.2/Okio3.16.4・Apacheライセンスを確認。compiled manifestはWebログインActivity/exported=false、startup provider/exported=false、OkHttp PlatformInitializer追加を確認。
- この時点では実機はまだbeta5。次に更新し初回復元・端末再起動後の保持を確認する。

## beta6実機・初回復元

- 実装コミット3e93af7。本体/中継install -rともSuccess、両46/beta6。PID31768→12215、署名表示2728742dとHOME/通知/Bluetooth/5通知listener/carsyso補助設定維持。
- 保存済み情報を初回から復元成功、再入力/Webログインなし。最初の公開301は1,075ms、200/2,455,875bytesまで1,453ms、公開refresh READY2,084ms、account200/2,625ms、接続全体READY2,628ms。ネイティブ画面でjp/SAVED・入力空を確認。
- beta5初回35,007ms TIMEOUTと比べ改善したが単発の計測で、全回線条件の性能保証ではない。Your LifeはApple API335ms/UNSUPPORTED→LRCLIB。実Apple単語歌詞の2曲採用はまだ未完了。
- 解除検証: 確認画面の初期ONを確認後、Webログイン削除だけOFFへ切替→接続解除。未接続/保存表示なし、force-stop/HOME後の新PID16172でも維持、AppleConnect開始ログなし。既存Webログイン画面の固定接続ボタンを押すとREADY1,941ms(公開refresh1,462ms/account2001,938ms)、Settingsでjp/SAVED・入力空を確認。Webログイン再入力なし。Cookie全文/ユーザーtokenを読み取っていない。認証済みWebデータ削除ONのケースは未確認。
- 新プロセス直後にSettingsが黒画面になったが、HOME→設定一覧の開き直しで表示。AndroidRuntime:Eなし。beta5でもあった現象で原因未特定。
- 全体再起動の確認は保留: 18:52頃に保存済み状態から `adb.exe -s 192.168.137.248:5555 reboot`、終了0。直後と18:57頃の `timeout 12s .../adb.exe connect 192.168.137.248:5555` は接続拒否10061、後続 `adb.exe devices -l` は端末なし。再起動完了・復元成功と扱わず、利用者へワイヤレスデバッグの再有効化を依頼した。再起動前のservice.adb.tcp.port=5555/persist.adb.tcp.port空と、既存導入文書の再起動ごとの有効化手順を確認済み。新たなアプリ変更/テスト再実行はない。
