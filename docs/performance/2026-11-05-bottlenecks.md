# v2.3.0 負荷分析（2026-11-05）

## 結論と対象

利用者の「全体を調べて優先順位を決める」に対する現状分析。アプリ・テストの実装変更、ビルド、インストールは行っていない。

**最優先は、ホームの単語同期歌詞を描画する際のRenderThread負荷。** Lemon再生中のYLuncher CPUは歌詞ONで34.35〜35.90%、OFFで3.99〜4.01%だった。これは機能ON/OFFの差であり、見た目を維持する最適化で同じ削減率を達成できるという意味ではない。描画方式の個別の寄与は、今後の比較実験で切り分ける。

利用予定のRAV4 GRスポーツは、利用者から最終的に**1920×1080・60Hz**との訂正を受領（途中の1280×720想定は取り下げ）。現在は車に未接続でPCから確認中。以下の主計測は1920×1080のGT8D通常画面であり、**車載時の性能測定ではない**。目標のフレーム周期は60Hzの約16.7msとし、現在の通常画面の30Hz環境での約33.3msと区別する。

## 条件と方法

- ソース: main `a7951e796a9af558611831cde3152e8f07922342`。実装は正式版タグ `v2.3.0-release` / `f0e5c9d`、実機app/panelhostとも2.3.0 / 47。
- 実機: GT8D / Lito for arm64、Android 15、1920×1080、density 238、YLuncher PID 9825。ADB接続先 `192.168.137.200:40275`。
- `dumpsys display` とSurfaceFlinger: 通常のdisplay 0はsupportedModesが30.000002Hzの1種類、vsyncも30Hz。peak/min_refresh_rateはともに未設定。これは現在の接続状態での報告であり、GT8DやRAV4の全接続条件での上限とは断定しない。
- 別のscrcpy転送用仮想画面は60Hzを報告。起動引数は `-b 16M --audio-source=playback` で、通常画面をミラーリングしていた。転送用画面の60Hzをアプリの通常画面の60Hzと混同しない。
- CPU: `/proc/[pid]/stat` のutime+stime差分をCLK_TCKと端末uptime差分で割った値。**1コア100%**。端末全体使用率と分母が異なる。各場面20秒または30秒を指定し、表には実際のuptime差分を記載。
- 各場面の前後でmedia_sessionと前面Activityを確認。`dumpsys gfxinfo ... reset` 後の描画統計、`dumpsys meminfo` を取得。統計リセット以外のアプリ設定初期化は行っていない。
- scrcpy・音声転送・他の常駐アプリは維持。scrcpy本体は概ね1コア換算22〜24%を使用していた。車載単独時のCPUや電力にそのまま換算しない。
- `simpleperf` と `atrace` は端末に既存のものを使用。インストールなし。通常比較の計測窓ではプロファイラーや大容量トレース転送を重ねていない。

## 場面別の実測

| 場面 | 実測秒 | YLuncher CPU（1コア換算） | 描画数 | deadline missed | PSS MiB |
|---|---:|---:|---:|---:|---:|
| ホーム・再生停止 | 19.25 | 0.16% | 0 | 0 | 80.43 |
| ホーム・Lemon・歌詞ON（1回目） | 28.59 | 34.35% | 550 | 43 / 7.82% | 82.60 |
| ホーム・Lemon・歌詞OFF（1回目） | 28.57 | 3.99% | 30 | 13 / 43.33% | 80.36 |
| ホーム・Lemon冒頭付近・歌詞ON（再確認） | 30.22 | 35.90% | 622 | 39 / 6.27% | 85.15 |
| ホーム・Lemon冒頭付近・歌詞OFF（再確認） | 29.89 | 4.01% | 34 | 10 / 29.41% | 84.92 |
| 設定アプリが前面・Lemon再生継続・YLuncher非表示 | 20.28 | 3.35% | 0 | 0 | 60.09 |
| セット4・パネル＋Maps・Lemon再生 | 30.42 | 4.90% | 45 | 15 / 33.33% | 90.87 |

- OFFは描画が少なく、ONとdeadline missed率だけを直接比較して滑らかさを判断できない。描画0件の場面に出る4950msというpercentile値は有効な遅延測定として扱わない。
- 再確認は曲頭へ戻して計測したが、ADBや状態取得の所要時間があり、完全に同じミリ秒区間ではない。
- 分割は前面のPanelActivityとMapsを確認。ただしこの計測窓で単語塗りの表示状態を画面と照合していないため、「ホームと同じ歌詞ON描画条件」の比較には採用しない。
- 自動で次曲（クランベリーとパンケーキ）へ進んだ別の計測は14.54%、223描画/30.75秒だった。ファイル名は `home_lemon_lyrics_on_2` だが前後の曲はLemonではない。Lemonの再現試行から除外し、曲・区間によって描画負荷が変わる参考値とする。
- 初期の `home_cover_playing` は利用者操作によるApple Music→YouTube/分割への変化が入り、前面条件も揃っていないため比較から除外。

### 1080p・60Hzと報告する仮想画面での追加確認

通常画面の計測後、scrcpyの新規仮想画面（display 6）へ試験用HomeActivityを移動した。元のホームは設定アプリの背後へ隠した。Lemon歌詞ONは28.89秒でCPU35.69%、558描画、deadline missed 134件/24.01%、gfxinfo p90 38ms、GPU p90 9msだった。PSS101.50MiB、Activitiesは試験用画面を含め5で、先の3とは条件が違う。

**表示モードの報告が60Hzでも、アプリが60fpsで動いているとは確認できていない。** 最後の120件のframestatsでIntendedVsync間隔は中央値53.11ms（通常画面53.10ms）、FrameDeadline−IntendedVsyncの最頻値も約33.33msのままだった。仮想画面の作成だけで通常画面由来のフレーム供給条件まで変えられたとは扱わない。車載接続時にはAndroidがアプリへ与える周期と実描画を再確認する必要がある。

続く `virtual1080_60hz_lemon_off` はCPU0.45%だったが、Apple MusicのPLAYINGが維持されず、その後の状態確認はBUFFERINGだった。これは歌詞OFF効果の比較から除外する。追加の60Hz環境では有効なON/OFF一組は未成立。通常画面の有効な2組を結論の主な根拠とする。終了時はLemonを一時停止（84,750ms）し、歌詞ONへ戻した。

## 描画の内訳

Lemon歌詞ONの15秒CPUサンプリング（cpu-clock、200Hz、1,091 samples、lost 0）では、アプリCPUの**RenderThread 70.85%、メインスレッド24.29%**。これはアプリCPU内の構成比であり、端末CPU使用率ではない。ライブラリ別ではlibhwui 37.49%、AdrenoのlibGLESv2 16.59%。呼出し経路はDrawFrameTask → CanvasContext → SkiaOpenGLPipeline → RenderNode/DisplayListが中心だった。このサンプリングと別トレースの転送には一部重なりがあり、比率は診断の参考値として扱う。

別途取得した20秒atrace（上書きなし、1,050,789エントリー）:

| 区間 | 件数 | 中央値 | p90 | 最大 |
|---|---:|---:|---:|---:|
| メイン: Choreographer#doFrame | 377 | 4.12ms | 7.48ms | 23.27ms |
| RenderThread: DrawFrames | 374 | 17.40ms | 25.83ms | 42.30ms |
| RenderThread: flush commands | 374 | 5.26ms | 8.20ms | 15.59ms |
| RenderThread: flush layers | 374 | 2.05ms | 2.78ms | 6.91ms |

区間は入れ子のwall timeで、待機時間も含む。各行を足してCPU時間とはしない。doFrameの開始間隔は中央値53.08ms、約19回/秒。この値は画面に提示されたFPSの直接測定ではない。通常のgfxinfoでも歌詞ONのp90は31〜32ms、GPU p90は9msだった。

メインの再構成だけでなく、描画コマンドの処理と層の合成に注目する根拠がある。GPUハードウェア自体が唯一の原因、あるいは背景グラデーションだけが原因と断定する根拠はまだない。車載60Hzでの実効描画周期、CPU/GPUクロック、温度、伝送経路による差も未測定。

## 優先順位

| 優先 | 対象 | 根拠・確度 | 次に行うこと |
|---|---|---|---|
| 1 | 単語歌詞の継続描画 | ON/OFFのCPU差を2回確認、RenderThread主体も確認 | 背景など静止部分と動く歌詞の再描画範囲、Offscreen合成の範囲を一項目ずつ比較する。見た目を維持する方針 |
| 2 | 非表示中の更新とメディア通知 | 非表示でも再生中3.35%、停止ホーム0.16%。どの処理の寄与かは未分離 | 非表示時のタイマー/購読、MediaWatcherの全セッション再読取をプロファイルで分離してから変更箇所を決める |
| 3 | セット切替中の利用履歴照会 | メインスレッドで同期照会するコードを確認。切替全体に占める時間は未測定 | 切替トレースでIPC時間と意図した待機を分離。完了判定の正確さを維持して非同期化を検討 |
| 4 | ホーム復帰ごとの7日分履歴集計 | 毎onResumeの再集計をコードで確認。IO上で実行済み | 頻繁な復帰時の実行時間を測り、更新間隔や差分集計を検討 |

### 実装上の根拠

- [LyricsPane.kt](../../app/src/main/java/com/gt8d/launcher/ui/home/LyricsPane.kt): 218行の単語塗りText、376行の領域全体にOffscreen。412〜420行のframe更新。既に可視窓だけの構築、Brushキャッシュ、描画層での状態読取を導入済み。全行構築の削減を新しい改善案として重複させない。DstIn/Clearのための層を単純に削除/Auto化すると表示が変わるので、合成の正しさを維持する設計が必要。
- [PanelScreen.kt](../../app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt): 68〜83行のcollectAsState、166〜175行の500ms更新。停止Activityに対するライフサイクル条件がこのループにはない。[LyricIndex.kt](../../app/src/main/java/com/gt8d/launcher/ui/home/LyricIndex.kt)の200ms更新も候補。ただし非表示中の描画は今回0件であり、見えない画面を描き続けていたとは報告しない。
- [MediaWatcher.kt](../../app/src/main/java/com/gt8d/launcher/media/MediaWatcher.kt): 145/149/153行の通知が178行の全セッションsnapshot再作成へ進み、202行以降でmetadata/playbackStateとアプリラベルを再取得する。ラベル/不変メタデータのキャッシュと変更セッションだけの更新が候補。支配的なCPU原因としては未確認。
- [SetRunner.kt](../../app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt): 484/682/690行の前面照会、700行以降の200ms再判定。[Time.kt](../../app/src/main/java/com/gt8d/launcher/time/Time.kt)のMainThreadSchedulerから[UsageStatsForegroundEvents.kt](../../app/src/main/java/com/gt8d/launcher/foreground/UsageStatsForegroundEvents.kt):50のqueryEventsへ同期到達する。常時ポーリングではなく切替処理中のもの。warm/coldの待機値をCPU負荷と同一視しない。
- [HomeActivity.kt](../../app/src/main/java/com/gt8d/launcher/ui/home/HomeActivity.kt):177 → [AppGraph.kt](../../app/src/main/java/com/gt8d/launcher/AppGraph.kt):139 → [UsageHistory.kt](../../app/src/main/java/com/gt8d/launcher/apps/UsageHistory.kt):28。多重実行ガードはあるが更新間隔の制限はなく、IO上で7日間のイベントを走査する。

## 今回は優先を上げないもの

- 背景色の抽出: Lemonの36ms、別曲の62msをログで観測。経過時間でありCPU占有時間ではない。Dispatchers.Default上、曲/画像変更時の処理で、結果を共有・保持している。元画像コピー削減は既に導入済み。現在の背景は最大3色の線形グラデーションで、描画自体の寄与はまだ分離していない。
- 歌詞取得: LemonはApple採用394ms、40行/277単語。ネットワーク待ちを含む初回取得時間であり、再生中の定常CPUとは別。AMLL索引の再走査候補はあるが、今回のApple再生の主因としては確認していない。
- メモリ: 主なホーム条件のPSS約80〜85MiB、非表示で約60MiB、分割約91MiB。WebViewsは全て0。長時間リーク試験やメモリ圧迫時の試験はしていないため「リークなし」とはしない。
- アプリ一覧・アイコンは共有キャッシュ/非同期読取へ移行済み。本体releaseのR8・resource shrinking・baseline profileも導入済み。既存対策を新規案として数えない。

## コマンド・証跡・未確認

司令塔の一時計測スクリプト `/tmp/yluncher-perf-sample.py` が、上記proc差分、media_session、activity、gfxinfo、meminfoを採取。各結果は `/tmp/yluncher-perf-20261105/` の場面名.json、-gfx.txt、-mem.txt。生トレースは同ディレクトリ `home-on.atrace`、区間集計は `home-on-trace-summary.json`、CPUのシンボル/呼出し経路は `home-on-simpleperf.txt` / `home-on-callgraph.txt`。一時ファイルは永続的なGit証跡ではないので、主要数値と解釈を本書に残す。認証情報・歌詞本文・画面画像はGitに含めない。

実行した主な診断コマンド（ADBはWindows SDKの既存adb.exeを使用）:

```text
adb.exe devices -l
adb.exe -s 192.168.137.200:40275 shell dumpsys display
adb.exe -s 192.168.137.200:40275 shell dumpsys SurfaceFlinger
adb.exe -s 192.168.137.200:40275 shell dumpsys media_session
adb.exe -s 192.168.137.200:40275 shell dumpsys activity activities
adb.exe -s 192.168.137.200:40275 shell dumpsys gfxinfo com.gt8d.launcher reset
adb.exe -s 192.168.137.200:40275 shell dumpsys gfxinfo com.gt8d.launcher framestats
adb.exe -s 192.168.137.200:40275 shell dumpsys meminfo com.gt8d.launcher
adb.exe -s 192.168.137.200:40275 shell atrace -b 16384 -t 20 -a com.gt8d.launcher gfx view sched freq binder_driver am -o /data/local/tmp/yluncher-home-on-20261105.atrace
adb.exe -s 192.168.137.200:40275 shell simpleperf record -p 9825 -e cpu-clock -f 200 --duration 15 -g -o /data/local/tmp/yluncher-home-on-20261105.perf
```

診断取得は成功。途中のproc一覧取得で終了済みプロセスの読取エラーが出た1回は破棄し、終了1でも取得済みの結果があり対象PIDを含む場合だけ扱うよう一時スクリプトを修正した。アプリのビルド/単体テストは0回（変更なし）。ホーム復帰時間の定量比較、切替の内訳、長時間熱/電力、車載接続は未測定。

scrcpy 5.0.1で720p仮想画面を試した後、利用者の訂正に合わせ1080pへ再起動した。GT8D標準のサブ画面ランチャーとナビゲーションバーが表示されたため、`--no-vd-system-decorations` を追加して正常終了/再起動。試験用HomeActivityのroot task 3318を新displayへ移動し、通常display 0は設定アプリで覆って二重描画を避けた。display 6で1920×1080・60.0Hz・density 238を確認し、画像でも右端の重なりが消えたことを確認した。アプリコードを変更して直したものではない。

最終scrcpy引数は `-s 192.168.137.200:40275 --new-display=1920x1080/238 --max-fps=60 -b 16M --audio-source=playback --no-vd-destroy-content --no-vd-system-decorations --print-fps`。238dpiは現端末の値を維持する仮条件で、車載側のdpiは不明。max-fpsは転送上限であり、表示モードはdumpsysで別途確認した。仮想画面での分割・HOME導線は通常displayと異なる可能性があり、車載の代替合格とはしない。

方法の参考: [Android dumpsys](https://developer.android.com/tools/dumpsys)、[描画性能](https://developer.android.com/topic/performance/issues/render)、[Composeの描画段階](https://developer.android.com/develop/ui/compose/performance/phases)、[AOSP simpleperf](https://android.googlesource.com/platform/system/extras/+/master/simpleperf/doc/README.md)、[scrcpy仮想画面](https://github.com/Genymobile/scrcpy/blob/master/doc/virtual-display.md)、[scrcpy映像/FPS](https://github.com/Genymobile/scrcpy/blob/master/doc/video.md)。

## 決めたこと

- 改善実装に着手する前に場面別測定と描画プロファイルから優先順位を付ける。
- 歌詞ON/OFFを各2回確認し、曲違い・操作混入の計測を同条件の比較から除外する。
- 1920×1080・30Hzの現状値と、利用予定1920×1080・60Hzの目標条件を分ける。
- 再描画/合成を最優先、非表示更新を次点、切替照会/復帰集計は追加計測対象とする。
- 現行の背景色、歌詞の単語塗り/送り/フェードを維持する方針とし、具体的な削減方式や効果は未確定とする。
- ネットワーク/SIM/IPv6、通常画面の解像度/Hz設定、APK、アプリコード、テストは変更しない。利用者と合意したscrcpy仮想画面の起動条件と一時的なActivity配置のみ調整する。
