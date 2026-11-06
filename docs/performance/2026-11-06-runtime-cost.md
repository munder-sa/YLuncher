# 通知・履歴照会の負荷削減

2026-11-06。利用者が中優先の4候補から効果のあるものを試して適用するよう依頼。基点7c06c7b、実機2.3.1-beta2 / 49。前奏の描画改善は維持し、単語描画20%削減とは別の評価とする。

## 着手順と判断

1. Task197: MediaWatcherで通知されたセッション/項目だけ更新し、アプリ名を再利用する。頻繁な位置通知時の不要なBinder/metadata/PackageManager参照削減をコール数で確認。
2. Task198: 7日分の利用履歴集計の成功結果を60秒再利用。IOでの権限確認は維持し、権限変更時は即反映。失敗時再試行は5秒、差分集計は今回不要。繰り返すホーム復帰で実集計回数を確認。
3. SetRunner: 200ms poll以外にretry/timeout/cancel guard/stock warmupも同じwatcherを読むため、世代と操作順を確認してから非同期化の範囲を確定する。タイムアウトやキャンセル保護を弱めない。
4. ArtworkBackground: 現行コードではARGB_8888の全サイズcopyは既に省略。非ARGBだけ変換copyが残る。実機の形式/サイズと互換性を確認してから追加効果を判断する。

Task197/198は別worktreeへ具体的md/allowで委譲し、Gradle最大2本。個別の回帰と処理回数をレビューし、有効な変更だけ統合して次の確認用betaへ進める。全体のCPU改善率をコール数だけから推定しない。正式版/pushは別工程。

## 変更前の確認

- 実機beta2/49、PID30219。同じApple Musicアルバム画面からHOMEへ10回、各復帰後2秒待機。7日集計ログは**10回**、対象イベント数702〜720。再生は停止したまま。数値原本 `/tmp/yluncher-runtime-control.json`、再現script `/tmp/yluncher-runtime-baseline.py`。初回にAppleのMAIN/LAUNCHER起動が解決せず0回で終了したため、既存のVIEW/deeplinkに直して測定。失敗試行は集計に混ぜない。
- Lemonの実画像は**256×256 / ARGB_8888**、抽出18ms。現行の `sampleArtworkColors` はこの形式を原寸copyせず32pxへ縮小する。過去のcopy削減コミットは6a2c20c。今回の追加画像変更はこのケースに効果がないため優先しない。非ARGB/HARDWARE形式を今回実機で確認したわけではない。
- [AOSPのMediaController](https://android.googlesource.com/platform/frameworks/base/+/master/media/java/android/media/session/MediaController.java)でcallback引数と破棄通知の契約を確認。[Bitmap API](https://developer.android.com/reference/android/graphics/Bitmap)ではHARDWAREの画素読取/変換に制約があるため、形式を無視してcopyを削除しない。

## SetRunnerを次段階へ分ける判断

読み取り専用のcodex-ask（`.codex-bak/ask-20261106-073224-63136/answer.md`）でも共有状態と制御順序を確認した。今回の適用対象はまずTask197/198の2件とする。SetRunnerは未実装であり、非同期化できたとは扱わない。

- ForegroundWatcher.lastQuerySucceededとUsageStatsForegroundEventsのskewは照会時に更新される。単にIOへ移し、後から操作世代を見て結果を捨てても、この副作用は消えない。同じwatcherをStockLauncherWarmupとLinkEntryActivityも使う。
- retry/Safetyの新しい照会後に古いpollが届く順序も考慮が必要。操作世代だけでなく共有照会の順序管理、照会と状態確定の分離が必要である。初回probeを遅らせると監視可否/カーテン開始順、Safetyを待たせると4秒の保護期限が変わる。
- 次の最小候補はwaitBoth pollだけを同時1件のbackground読取にし、不変結果と補正値をmainで世代・順序確認後に確定するもの。既存の初回/retry/Safety/waitSingle/guardは維持する設計から検証する。速度改善の保証や試験済みの案ではない。
- 必要な回帰はSetRunner、ForegroundWatcher、UsageStatsForegroundEvents、StockLauncherWarmup、LinkEntryActivity。照会保留中のキャンセル/別セット、旧成功・旧失敗・旧skew、retry逆転、Safety跨ぎ、権限失効/回復を追加する。cold/warm・左右パネル・リンク・退避・キャンセル後の遅延前面化を実機で確認する。

狙う効果は切替中のメインスレッドの待ち時間短縮であり、照会回数や総CPUの削減とは別。今は照会時間の実機比較値がなく、2件の検証に混ぜて効果を主張しない。

## 先行2件の実装・検証

- Task197: `/tmp/yluncher-media-cache/.codex-bak/20261106-072915/`、ソース0ce1edc。許可3ファイルのみ。指定コマンド1回で**27件成功**（新規MediaWatcherTest10、NowPlaying12、LyricsIdentity1、LyricsMetadataProbe4）、failure/error/skip 0。
- Task198: `/tmp/yluncher-frequent-cache/.codex-bak/20261106-072917/`、ソースe70c494。許可3ファイルのみ。指定コマンド1回で**60件成功**（新規FrequentAppsRefresherTest18、FrequentApps8、UsageHistory3、PanelScreen31）、failure/error/skip 0。
- 両者のXML/実行イベントと差分を司令塔が照合。既存テスト無変更、試験の再実行なし。allow警告は既知の--tests引用符の正規化差だけで、実行イベントのコマンドは指示どおりだった。

実行コマンド:

```
/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.MediaWatcherTest' --tests 'com.gt8d.launcher.media.NowPlayingTest' --tests 'com.gt8d.launcher.media.LyricsIdentityTest' --tests 'com.gt8d.launcher.media.LyricsMetadataProbeTest'
/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.apps.FrequentAppsRefresherTest' --tests 'com.gt8d.launcher.apps.FrequentAppsTest' --tests 'com.gt8d.launcher.apps.UsageHistoryTest' --tests 'com.gt8d.launcher.ui.panel.PanelScreenTest'
```

処理回数の検証では、3セッション＋100件の位置callbackで初期化後のmetadata/state/label読取が各0回。全セッションを読む旧コードでは同じ入力に各300回の読取が必要な構造だったが、旧APKに100件を注入した実機測定ではない。選択用snapshot走査とNowPlaying更新は維持する。metadata/画像のcopy/recycleは追加しない。null、favorite、pause/resume/seek、同一package複数、破棄、旧callback、stop/startを確認。Robolectric MediaSessionの配線試験はIPC部分をShadowで模擬した。

頻出アプリは初回＋10回の要求でquery1回、保留中20要求も1回。60秒は成功完了からの単調時計、失敗再試行は5秒。権限確認は各要求でIO実行し、失効/再許可時は期限を解除する。権限確認順序とquery世代の両方を確認し、AppGraphのApplication変更・override/reset・旧job遅延完了も検証した。順位の更新は最大約60秒＋次の要求まで遅れる。

決めたこと:

- MediaSessionCache.Accessをコール数の境界とし、集合変更時に再構築。破棄したentryはcallbackが残ってもbitmap/controllerを保持しない。lastActivePkgは従来どおり保持し、transport操作時の直接読取を維持。
- ラベルは集合更新/再startで破棄。locale/configuration変更で全件、package変更で当該分を無効化。2つのfilterを持つreceiverをstop/開始失敗で解除。metadata診断はセッション別の初回/タイトル変更時のみ。snapshot読取例外は該当entryを除外し、他を再選択。
- FrequentAppsRefresherが専用scopeを保持。権限例外は使用不可。空ランキングも成功とし、更新中は前回値を表示、null/例外/同世代cancelは手動fallbackへ戻す。キャンセルは再送出し、旧世代は公開しない。
- 同じApplicationでhelperを保持、context/override/reset変更でclose。公開とcloseは同じlock、読取対象とAppCatalogはそのApplicationに固定。常駐timer、差分集計、新規依存は追加しない。
- 2件を統合して確認版 **2.3.1-beta3 / 50** を作成する。SetRunner/追加画像変換は今回は適用しない。

## 確認用ベータ3

Task200 `.codex-bak/20261106-073852/` は版2ファイルのみ変更。`/home/munde/tools/gradle-run.sh :app:assembleRelease :panelhost:assembleRelease` を1回実行、**1分17秒で成功**。成功済みテストの再実行なし。本体/中継は50/2.3.1-beta3。署名SHA256は既存と同じ `44d8c612bd3948e265840f28adff0f820d5893d0ba24d888b041ca16544f7f6d`。

| APK | SHA256 |
|---|---|
| `build/runtime-cost/beta3/app.apk` | `faca03ff569bab3fa8e31287768d015d83a44bb204182efbcc51c7f283942405` |
| `build/runtime-cost/beta3/panelhost.apk` | `18e8b6c7be62721800a6063d31c32a96e5efea367b1cfdee94f45decbfc706e2` |

両APKのinstall -r成功、実機base.apkのハッシュ一致、HOME/通知リスナー/accessibilityの導入前後一致を確認。PID32366。APKはGit管理外、旧beta2は `build/runtime-cost/control/`、8クラス分のテストXMLは `build/runtime-cost/tests/` に保存。

初回のspeed-profileコンパイル直後はART verify、通常操作後に再compileして**speed-profile / cmdline**を確認した。同梱ProfileInstaller 1.3.1のINSTALL_PROFILE通知はresult=3で、既存AARのjavapから **RESULT_UNSUPPORTED_ART_VERSION** と確認。通知成功と混同しない。新たな依存更新は行わず、最終の実効コンパイル状態だけを確認した。CPUの前後比較はこの実行条件から主張しない。

実機のセッション切替では、Apple Musicの停止状態からYouTubeへ切替し、ホームの再生ボタンでYouTubeだけがPLAYING、もう一度押してPAUSEDになることをmedia_sessionと画面で照合した。Appleはその間4.580秒で停止を維持。Appleを再開するとLemonへ表示/背景/歌詞が戻り、シーク51.843秒→停止54.638秒の操作が反映された。

YouTubeの古い停止セッションへのplayだけではmetadataが空のままSTOPPEDだったため、アプリ画面から動画を選び直して検証した。この準備段階をセッション切替成功と混同しない。セッション別操作は実機 `cmd media_session monitor` のhelpでplay/pause/next/previous/qを確認して使用。新規ツールやAPKの追加導入はない。

## 利用履歴の実機比較

同じ通常画面1920×1080/238dpi/30Hz、同じApple Musicアルバム画面からHOMEへ10回、各復帰後2秒待機。候補は最後の操作から62秒以上待ち、成功キャッシュが期限切れの状態から開始した。頻出アプリの既存ログで実集計を数え、PIDが窓内で変わらないことも確認。

| 条件 | ベータ2 / 49 | ベータ3 / 50 |
|---|---:|---:|
| ホーム復帰 | 10回 | 10回 |
| 7日分の実集計 | 10回 | 1回 |
| 実集計ログの対象イベント数 | 702〜720件 | 728件 |
| PID | 30219 | 32366 |

**この短い連続復帰で全期間集計回数を90%削減**。試行の間に端末履歴は増えており、同じイベント配列での処理時間比較ではない。アプリ全体のCPU/フレーム時間や消費電力を90%減らしたという意味ではない。権限確認は各復帰で続ける。採用判断は実機での照会回数減少と、既存順位・権限・世代競合のテスト成功による。

再現: `python3 /tmp/yluncher-runtime-baseline.py control` / `candidate`。数値は `/tmp/yluncher-runtime-control.json` / `yluncher-runtime-candidate.json`。candidateの開始は端末時刻07:45:53、実効ARTはspeed-profile。更新頻度を制限したランキングの主観的な使い勝手は、利用者の普段の操作で確認待ち。

## 最終の場面確認と残る事項

- ホームの次曲操作で、同じAppleセッションのLemon→クランベリーとパンケーキの曲名/画像/進捗/歌詞更新を確認。さらに異なるアルバムの怪獣へ切り替え、青〜茶の背景と歌詞へ更新した。
- 保存接続は再入力なしでCONNECT/READY（3,888ms）。Lemon/怪獣のApple歌詞はcache=missで受理された。クランベリーはAppleがINVALID、AMLLは404後にLRCLIBキャッシュへfallbackして表示した。Appleの3曲成功とは扱わず、INVALIDの原因は今回調査しない。取得順序/クライアント/TTML処理は変更していない。
- セット4のパネル＋Mapsへ移行し、曲名/背景/1行歌詞を表示。ホームへ復帰して怪獣16.574秒で停止。YouTubeも停止。PID32366は導入後の確認中に変化せず、同PIDのAndroidRuntimeエラー出力なし。
- `python3 /tmp/yluncher-perf-sample.py runtime-beta3-paused 20`: 実測18.50秒、CPU0.22%（1コア100%）、描画0回。連続drawが増えていないことの確認で、旧版とのCPU改善率は算出しない。描画0件の分位値4950msは無効値として評価しない。
- 実機の見た目は司令塔が一時画面で確認、利用者の主観的な操作感は確認待ち。スクリーンショット8件は確認後削除し、歌詞本文/認証値をGitへ保存しない。

今回適用したのはMediaWatcherと頻出アプリ集計の2件。SetRunner非同期化は上記の次段階設計、非ARGB画像変換は実機での追加効果未確認として残す。歌詞の単語描画20%削減、実車60Hz、全体CPU/消費電力改善率は今回の成果ではない。正式版/pushは未実施。採用2件の対象87件とreleaseビルドは成功済みで、全テストは正式化時の別工程にする。

## v2.3.1後の追加候補調査 (2026-11-06)

前節はbeta3時点の履歴。その後2.3.1 / 51を正式化し、全4,098件成功、実機適用、main/tagのpush、GitHub Release作成を完了した。今回の基点はmain `2e92e63`。ユーザーの「他にパフォーマンス向上に寄与できそうな点」に対する調査で、アプリ実装・テスト・依存は変更していない。

### 今回の実機確認

- ADB `192.168.137.200:40275` はdevice、本体2.3.1 / 51、PID19652。実効ARTはspeed-profile / cmdlineを維持。
- HomeActivityはRESUMEDかつvisible、PanelActivityはSTOPPEDかつvisible=false。ホームだけが見えている状態で、同PIDの現在のNetworkCallback登録が **2件**（callbackRequest 1125/1131）残存。dumpsysの過去REGISTER/RELEASEや同じ要求の重複表示を数えず、現行uid/pidの登録行から数えた。どの登録が何%のCPUを使うかは未測定。
- 歌詞のファイル本文は取得せず、件数・サイズだけを集計。LRCLIBは **46件 / 46,404 bytes**、AMLLは **1件 / 8,046 bytes**。AMLL検索表 `lyrics/amll-index/table.jsonl` は現在存在しない（wcはNo such file/終了1）。索引の全走査を現端末の常時負荷とは扱わない。
- 実行した読み取り: `adb.exe ... get-state`、`shell dumpsys package com.gt8d.launcher`、`shell pidof com.gt8d.launcher`、`shell dumpsys package dexopt`、`shell dumpsys activity activities`、`shell dumpsys connectivity`。キャッシュは対象ディレクトリの `find ... -maxdepth 1 -type f -name '*.json' -exec stat -c %s {} \;`、索引は `wc -l -c` で確認した。
- 今回は画面操作・設定変更・トレースの新規採取をしていない。新しいCPU削減率、電力、60Hzでの改善は未測定。以下の数値根拠は既存の比較記録と区別する。

### 優先して調べる候補

| 候補 | 狙う場面と根拠 | 変更リスク・次の検証 |
|---|---|---|
| **次の小規模計測: 画面の重複監視と非表示更新** | 停止したパネルを残したHOME/他アプリ表示。現在もネットワーク登録2件、電波通知から権限照会までmainで実行。200ms/500msのUIタイマーにも停止条件がない | 中。監視の共有・表示中だけのUI更新を分けて比較。復帰時に即時同期し、分割で見えている画面は停止しない |
| **最大の負荷候補: 単語歌詞の描画・合成** | 直近の同条件比較で単語区間CPU35.62→35.59%と改善なし。RenderThread主体という既存の診断もある | 高。現行と画素を照合できるGPU検証を先に作り、マスク用Offscreenや文字の二重描画の削減を一項目ずつ比較 |
| **中: 利用履歴の取得イベントを絞る** | 切替監視と7日集計は全イベントを受信後、ACTIVITY_RESUMEDだけを使う。API35のイベント指定を活用可能 | 低〜中。同じ期間の旧/新結果を実機で照合し、受信件数・IPC時間・解析時間を比較。パッケージ範囲や時計補正は維持 |
| **中: SetRunnerの履歴照会をmainから分離** | 切替中の200ms周期で同期queryEvents。タップ/カーテン更新と競合し得る | 高。上記の共有skew/成功状態/世代/照会順序の設計が必要。まず実機traceで同期待ち時間を測る。総CPU削減とは別 |
| **導入直後向け: ProfileInstallerのAPI35対応** | 依存1.3.1はINSTALL_PROFILE result3。現端末はADBでspeed-profileにしたが、通常のAPK更新だけで同等とは限らない | 低〜中。1.4.1を候補とし、手動最適化なしで更新→profile書込み→実効ARTを検証。現端末への即時追加効果は未確認 |
| **後順位: AMLL/LRCLIBキャッシュ整理** | AMLLは削除不要でも全件ソートし、比較関数内で全文JSONを再解析。蓄積時に増えるが現端末AMLLは1件 | 低〜中。上限内ならソート前に終了し、上限超過時のsavedAt読取りを1ファイル1回へ。300件/32MiB/破損時/削除順を維持 |
| **後順位: セット番号の同期ファイルIO** | HOME/セット切替の操作経路でlast_set_index.txtを直接read/write。小さいファイルだがmainのIOになる | 中。まず遅延を計測。同じ番号の重複書込み抑制から検討し、異常終了・再起動時の保存順序を崩さない |

#### 重複監視・非表示更新の具体箇所

[PanelEnvironment.kt](../../app/src/main/java/com/gt8d/launcher/ui/panel/PanelEnvironment.kt) 61〜94行はCompositionごとにNetworkCallback/電波receiverを登録し、解除はonDispose。STOPでは解除しない。refresh（62〜65行）はWi-Fi/BTと全権限をまとめて読むため、RSSI/capabilities通知でも `Settings.canDrawOverlays`、UsageStatsのAppOps確認まで進む（132〜140行）。新旧2つのoverloadがあり、通常のHome/PanelはAppCatalogState版、設定画面はAppCatalog版を使う。共有化では両方の使用箇所を確認する。

まずラジオ状態と権限確認を分離し、変更通知の情報で足りる箇所の再照会を減らす。権限は復帰時と適切な変更検知で即時更新できることを確かめ、古い許可表示を残さない。次に表示クライアントで共有する監視を検討する。2登録を1つにできても、アプリCPUが半分になるという意味ではない。

[PanelScreen.kt](../../app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt) 68〜83行のcollectAsState、166〜175行の500ms再生位置更新、[LyricIndex.kt](../../app/src/main/java/com/gt8d/launcher/ui/home/LyricIndex.kt) 22〜27行の200ms行検索、[PanelContent.kt](../../app/src/main/java/com/gt8d/launcher/ui/panel/PanelContent.kt) 110〜114行の時計が候補。公式もAndroidでは[collectAsStateWithLifecycle](https://developer.android.com/develop/ui/compose/state)を推奨する。ただしFlow収集の置換だけで独立したdelayループが止まるわけではなく、各producerにも表示条件が必要。MediaWatcher自体を停止して選択先/transport/復帰時の情報を失わないようにする。

通常の非表示Composeのフレーム更新とdelayを同一視せず、「見えない画面を描画し続けている」と断定しない。既存の非表示中3.35%はv2.3.0の測定であり、MediaWatcher改善後の2.3.1へそのまま適用しない。検証はHomeのみ/分割/全画面非表示それぞれで、登録数、callbackと権限照会の回数、CPU、復帰時の表示を比較する。

#### 利用履歴・導入時最適化の具体箇所

[UsageHistory.kt](../../app/src/main/java/com/gt8d/launcher/apps/UsageHistory.kt) 28〜35行、[UsageStatsForegroundEvents.kt](../../app/src/main/java/com/gt8d/launcher/foreground/UsageStatsForegroundEvents.kt) 50〜76行はいずれもACTIVITY_RESUMEDだけを結果に使う。[UsageEventsQuery.Builder.setEventTypes](https://developer.android.com/reference/android/app/usage/UsageEventsQuery.Builder)はAPI35から利用できるため、minSdk35の本アプリで候補にできる。変更後のGT8Dでの互換・短縮量は未確認。特にForeground側は自アプリのRESUMEDを使う時計補正も保つ。まずイベント種別だけを絞り、非同期化やパッケージ限定と混ぜず検証する。

[gradle/libs.versions.toml](../../gradle/libs.versions.toml) 6行はProfileInstaller 1.3.1。[公式リリースノート](https://developer.android.com/jetpack/androidx/releases/profileinstaller)では1.4.0でAPI35以降のprofile取込み/書込み対応、安定版は1.4.1。以前のresult3と対応する具体的な更新候補である。これはJava/Kotlinの実行・起動の最適化を通常導入でも成立させる案で、RenderThread主体の描画負荷を解決したという意味ではない。既存baseline-prof.txtは広い手書き指定のため、その後に実際のHOME/パネル/曲変更を収録したプロファイルへ改善する余地もあるが、追加効果は別計測にする。

#### 後順位の根拠と除外した候補

- [AmllCache.kt](../../app/src/main/java/com/gt8d/launcher/media/lyrics/AmllCache.kt) 91〜95行はソート評価後に上限判定。比較関数でJSONを読むため、大量保存時のコストは単なる1回の全件走査より大きくなり得る。[LyricsCache.kt](../../app/src/main/java/com/gt8d/launcher/media/lyrics/LyricsCache.kt) 71〜79行は上限超過時だけだが同じ比較関数内読取りがある。両方IO上であり、今の件数から描画負荷の主因には挙げない。
- [AmllIndexStore.kt](../../app/src/main/java/com/gt8d/launcher/media/lyrics/AmllIndexStore.kt) 44〜48行と[AmllIndex.kt](../../app/src/main/java/com/gt8d/launcher/media/lyrics/AmllIndex.kt) 68〜74行は検索ごとに全表のJSON解析とdigest計算を行う。索引作成時の検索キー/世代情報の再利用は候補だが、現在表が存在せずApple直取得中心のため保留。曖昧一致の検出や索引更新後の無効化を省略しない。
- [ConfigRepository.kt](../../app/src/main/java/com/gt8d/launcher/config/ConfigRepository.kt) 36〜46行のセット番号IOは、[SetRunner.kt](../../app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt) 245/454行等から同期到達する。通常の設定DataStoreはIO上であり、設定保存全体がmainにあるとはしない。
- 長い曲名の[MarqueeText.kt](../../app/src/main/java/com/gt8d/launcher/ui/layout/MarqueeText.kt)は描画ごとに端のGradient Brushを生成する。drawWithCache化は小候補だが1周だけのスクロールで、歌詞の連続描画より低優先。使用頻度と割当を測ってから扱う。
- 全セッション再読取、ホーム復帰ごとの7日再集計、ARGB_8888画像の原寸コピーは改善済み。背景だけの層分離は[既存比較](2026-11-06-lyrics-render-comparison.md)で効果なし。これらを新規の改善として重複計上しない。
- 単語のRuntimeShader案はソフトウェアCanvasのテストで不成立。端末GPUが非対応と確定したわけではない。文字自体のBrush描画なども含め、GPU上で同時刻の旧/新画像を照合できる検証を先に設計する。実験の失敗テストを消す・画素差基準を緩めることはしない。

決めたこと: 今回は候補調査のみ。次の小規模実験は重複監視/非表示更新、次点は履歴のイベント絞込みを推奨する。大きな描画改善はGPUでの互換確認から別に進める。キャッシュ整理・索引は実機の現在件数から順位を下げ、ProfileInstallerは通常導入時の再現性として分離する。テスト/ビルド/インストール/端末設定変更は0回で、新しい性能改善率は主張しない。

## beta2後の効率化候補調査 (2026-11-06)

前節の7候補は、その後6件を採用し **2.3.2-beta2 / 53** へ統合した。Shader Brushによる単語塗りはGPU画素互換を満たさず不採用。詳細は[実装・検証記録](2026-11-06-followup-implementation.md)。今回の基点は `perf-followup / 075f919`、依頼は「他の効率化の方法を探りましょう」。以下はコードと既存測定の再分析であり、新しい実機性能測定ではない。

### 残る対象と優先順位

非表示再生のCPU中央値4.213→0.198%は前回の採用分で達成済み。今回の候補へ再計上しない。表示中の単語歌詞は、以前の比較でCPU約35.6%・RenderThread約25%が残っていた。ただしbeta2の新規測定値ではない。まずこの継続描画と、初回表示・前奏・時計更新を分けて検討する。

| 優先 | 候補 | 根拠・狙う場面 | 未確認事項 |
|---|---|---|---|
| 描画の本命 | **上下端フェードの合成範囲を限定** | LyricsPane全体のOffscreen＋DstInを、端のフェードが必要な領域へ限定する案。単語更新中の合成面積を減らす | 端の行の拡大・移動・重なりを含む画素一致、描画命令の増加と相殺されないこと |
| 小変更の先行候補 | **前奏・♪の点灯境界だけ時刻を更新** | 描画抑制済みでもwithFrameMillisは継続。次の点灯時刻まで毎フレームの位置計算を休止する | シーク・速度・停止再開時の再予約と、従来同等の点灯時刻 |
| 小変更の次候補 | **時計の文字計測を再利用し、分単位で更新** | HomeClockHeaderとFitClockでサイズ探索が重複。分表示に1秒/10秒のタイマーを使用 | フォント・幅・拡大率・時刻変更に追随し、文字欠けや日付の配置変化を生まないこと |
| 起動時の候補 | **見えるアプリのアイコンから準備** | 初回画面表示を、非表示セットを含む全requiredのアイコン取得完了まで待つ | 初回表示時間への実寄与、仮アイコンから置換する際の見た目・操作性 |
| 描画の別実験 | **通常描画した文字へ後から単語マスクを適用** | 失敗した文字Shader Brushを繰り返さず、通常Textの描画結果を入力にする | 画素互換、毎フレームのオブジェクト生成、実効GPU時間。Offscreen自体は残る |
| 起動時の補助候補 | **実際の操作からBaseline Profileを収録** | 既存12行の広い手書き指定と、HOME/パネル/歌詞の実行経路を比較 | 既存プロファイルに対する追加効果。RenderThreadの合成負荷とは別 |

### 1. 歌詞領域全体のフェードを分解する

[LyricsPane.kt](../../app/src/main/java/com/gt8d/launcher/ui/home/LyricsPane.kt) 400〜409行は、歌詞領域全体をOffscreenへ描き、上下32dpのフェード用DstInをかける。単語塗り側の行単位Offscreenとは別の外側の合成である。まず独立プローブで外側フェードの寄与を測り、中央と端で処理を分ける価値があるか確かめる。診断だけでフェードを外した比較は可能だが、見た目を変えたものを採用版や改善実績とはしない。

候補は、通常の文字描画と行のレイヤーを保ち、端に入った部分だけに画面座標基準のフェードを適用する方式。行ごとの単純なalpha乗算は、行同士が重なったときに全体合成後のマスクと一致するとは限らない。全内容を中央・上端・下端へ3回描く方式も、命令数増加で逆効果になり得るため既定案にはしない。移動途中、拡大、複数行、背景色、クリップ端を含めて画素比較を先に行う。

既に試した「背景だけを兄弟graphicsLayerへ分離」はCPU/RenderThreadの改善なし。本案は背景分離の再試行ではなく、歌詞側の外側マスクの範囲を対象とする。レイヤー追加だけでラスタ結果が永続キャッシュされるとは扱わない。[Compose公式の合成仕様](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers)に照らして、描画命令の再利用とOffscreenの描き直しを区別する。

### 2. 前奏・間奏の毎フレーム計算を止める

[LyricsPane.kt](../../app/src/main/java/com/gt8d/launcher/ui/home/LyricsPane.kt) 436〜448行では、前奏・単語歌詞・「♪」をまとめて毎フレーム更新している。前奏の点は既にderivedStateOfで点灯数が変わるときだけ描画するが、位置算出とwordFillPositionの呼出しは続く。既存の約3秒traceでもdoFrameは63回のまま、実drawだけ63→4になった。

次の点灯境界を求め、近づいた時点でフレームに合わせて更新する方式を候補とする。単語塗りと行移動は現在の滑らかさを維持する。今のposition共有先も確認し、前奏・「♪」以外に必要な更新を止めない。再生速度、シーク、トラック変更、ON_START、停止再開、最終区間の長さを予約の無効化条件に含める。0以下の速度や不明な区間長の扱いは実装前に明示する。追加効果は前回の描画削減より小さい可能性があり、CPUとframeUpdatesの両方で判定する。

### 3. 時計の計測と更新をまとめる

[HomeClockHeader.kt](../../app/src/main/java/com/gt8d/launcher/ui/home/HomeClockHeader.kt) 47〜60行は150spから40spまで2sp刻みで候補を探索し、候補ごとに「00:00」と現在時刻を計測。その後[FitClock.kt](../../app/src/main/java/com/gt8d/launcher/ui/layout/FitClock.kt) 39〜80行でも文字計測と探索を行う。毎回56候補を実行するわけではなく、条件成立で終了する。実呼出し回数・時間は未測定。

幅/高さ、density/fontScale、フォント、スタイル、min/max、実際の時刻文字列を適切なキーにして選択結果を再利用する。親と子では制約や自然幅の扱いが異なるため、単に同じ値を渡すだけで同等とは決めない。フォント差による欠けを防ぐ現在文字列の検証は残す。

ホームは毎秒、パネルは10秒ごとの更新だが、表示はどちらも分単位。ホームの同じ分の代入はStateの等値判定で再描画されないため、「毎秒時計全体を再描画」とはしない。表示中だけ[毎分のACTION_TIME_TICK](https://developer.android.com/reference/android/content/Intent#ACTION_TIME_TICK)を動的登録し、時刻/タイムゾーン変更と復帰時の即時更新を組み合わせれば、不要な起床を減らせる。Task202の非表示停止を維持し、receiverの解除・表示が複数ある場合の所有者も検証する。

### 4. 初回のアイコン待ちを短くする

[PanelScreen.kt](../../app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt) 89〜100行のrequiredには、ホーム・パネル・dock・idle・全セット左右・頻出・再生中・設定が含まれる。初回のpreparedがtrueになるまで画面全体がreturnする。取得自体はAppCatalogStateのIO上であり、mainで全アイコンを同期取得しているという問題ではない。またpreparedは一度trueになれば戻らないため、毎回のHOME復帰の待ちとは区別する。

最初に表示する画面の必要アイコンを優先し、残りを後から取得する案を比較する。仮表示を使う場合もタイル寸法・並び順・タップ先を固定し、未インストール表示、パッケージ変更による無効化、復帰後の更新を保つ。コールド起動の初回内容表示時刻、全アイコン完成時刻、IO並列数を分けて記録する。常時CPUや大きなメモリ削減はこの案の主目的ではない。

### 5. 単語マスクの別方式と既存GPU結果

既存663ケースの生データ `/tmp/yluncher-gpu-proof-run2-cases.json` を再集計した。通常のreference_shaderケースのうち、非colorEmoji・emphasis>0・塗り切りEND/ABOVE_ENDでは **72/72件不一致、最大差23**。colorEmoji・emphasis>0・未塗りBELOW_ZERO/ZEROでも **12/12件不一致、最大差154**。したがって塗り境界付近だけの問題ではない。文字Shader、アンチエイリアス、色付きグリフ等のどの経路が原因かは未確定。

別案は通常Textを描いた結果にマスクを適用する。[RenderEffect.createRuntimeShaderEffect](https://developer.android.com/reference/android/graphics/RenderEffect#createRuntimeShaderEffect(android.graphics.RuntimeShader,%20java.lang.String))はRenderNodeの描画内容をshader入力にできる。ただし[ComposeのRenderEffectはOffscreenを必ず使用](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers)するため、「Offscreenをなくして高速化」とは言えない。まず全面通過/全透明の互換、次に境界とuniform連続更新を検証し、663ケースと全体フェード・行移動の追加ケースが成立してから実機時間を比較する。失敗した旧テスト・画素許容差1/255は維持する。

### 6. 実行経路に沿うプロファイルと小候補

[baseline-prof.txt](../../app/src/main/baseline-prof.txt)は存在し、ProfileInstallerのAPI35対応も採用済み。次は「ないものを導入」するのではなく、既存の広い手書き指定と[重要な操作を収録して生成する方式](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile)を比較する。HOME→パネル→復帰、歌詞取得後の表示/曲変更/シークを対象に、通常導入からの起動時間・mainの処理時間を測る。強制compile済みの条件で通常導入の効果を主張しない。新しいベンチマーク依存が必要なら別の指示書に具体化する。

既出の小候補としてMarqueeTextの描画ごとのGradient Brush生成をdrawWithCacheへ移す余地も残る。ただし1周の曲名スクロール限定で、単語歌詞の継続負荷より後順位。MediaWatcherのメタデータ分離、履歴のIO分離・更新頻度制限、ARGB画像コピー削減、歌詞キャッシュ整理は採用済みとして扱う。

### 今回の確認範囲と決めたこと

- 実行: `git status --short --branch`、`git log -1 --oneline`、対象Kotlin/文書の `rg` / `sed -n` / `nl -ba` による読取、既存GPU結果のPython標準ライブラリでの集計、Android公式資料の閲覧。変更後の `git diff --check` は成功、文書差分と既存未追跡3件の維持を確認した。
- 新しいアプリ/テスト変更、ビルド、テスト実行、インストール、端末設定変更、画面操作は0回。新規の性能値・電力値は取得していない。実車1920×1080/60Hzは引き続き未確認。
- **決めたこと**: 今回は調査と記録まで。着手順は前奏の境界更新→時計の再利用→初回アイコン待ちを推奨し、大きな効果を狙う歌詞全体フェードは独立プローブで先に寄与/画素互換を判定する。単語の別Shader方式と実収録プロファイルはその次とし、効果が測れないものを一括採用しない。
- beta2の見た目/操作感の利用者回答は未受領。この調査依頼を承認の代わりに扱わず、回答まで画面操作を停止する方針を維持する。
