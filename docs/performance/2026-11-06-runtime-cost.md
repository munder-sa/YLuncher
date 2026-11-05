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
