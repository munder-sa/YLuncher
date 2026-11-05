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
