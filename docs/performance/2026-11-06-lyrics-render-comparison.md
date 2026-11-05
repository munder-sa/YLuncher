# 歌詞描画の軽量化 比較記録

2026-11-06。比較・採否判断と確認用ベータの実機導入まで完了。利用者の見た目確認は起床後。設計は[描画負荷軽減](../superpowers/specs/2026-11-05-lyrics-render-cost-design.md)、条件と採取手順は[Task192](../../tasks/192-lyrics-render-baseline.md)。PC接続GT8Dの通常画面1080p/238dpi/30Hzでの結果で、車載60Hzの結果ではない。

## 候補A: 点灯数が変わらない前奏・間奏のdraw依存を除く

対照4ea66f4、候補e5f78cb。同じrelease/R8・beta1/48・署名・ART speed。基準と候補を交互に3組測定。CPUは1コア100%、RTはRenderThreadのCPU。短い前奏のthread内訳には採取時点のずれがある。全試行で同一曲/PLAYING/速度1/前面Homeを確認し、熱状態は0（温度自体は取得できず）。

### 前奏: Paper Flowerの約2〜5秒

| 組 | 基準CPU | 候補CPU | 基準RT | 候補RT | 基準draw | 候補draw | 基準/候補の遅延フレーム数 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | 31.23% | 6.57% | 23.21% | 2.09% | 81 | 5 | 11 / 3 |
| 2 | 31.15% | 7.90% | 23.68% | 1.52% | 75 | 5 | 2 / 1 |
| 3 | 33.43% | 8.13% | 25.00% | 1.25% | 82 | 6 | 2 / 2 |

CPU中央値31.23→7.90%、約74.7%減。差23.33ポイントは基準の幅2.28ポイントを超え、3組とも改善。draw中央値81→5。frame clockは維持し、描く内容が不変の間だけdrawを抑制する。点中央のRGBと点灯境界/フェードはテストで旧計算と一致。

GPU p90中央値9→16ms、フレームp90 27→38ms。一方でdrawは5〜6件と少なく、遅延フレーム数の中央値は2→2。大きく異なるdraw母数の遅延率を同じ重みで比べない。GPU/遅延の差は下記の追加トレースで調べ、改善と誤認しない。

### 単語: Lemonの約41〜71秒

| 組 | 基準CPU | 候補CPU | 基準RT | 候補RT | 基準draw | 候補draw | 基準/候補の遅延フレーム数 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | 35.62% | 34.49% | 24.91% | 24.21% | 571 | 573 | 56 / 56 |
| 2 | 34.58% | 35.59% | 24.22% | 24.74% | 565 | 570 | 21 / 122 |
| 3 | 36.25% | 36.65% | 25.28% | 25.52% | 571 | 573 | 63 / 106 |

CPU中央値35.62→35.59%、有意な削減を示さない。単語区間20%の目標は未達。末尾120件の描画間隔中央値は53.10→53.11ms、GPU p90は両9ms。更新頻度を落として軽くした結果ではない。

フレームp90中央値32→36ms、遅延フレーム中央値56→106。基準3回の遅延フレーム幅42を中央値差50が超えたため、採用前に下記の追加トレースを実施した。CPUが同じという理由だけで遅延差を無視しない。

単語後PSS中央値92.62→92.47MiB、Graphics29.10→29.13MiB。前奏後PSS79.67→74.58MiB、Graphics28.63→28.62MiB。GCの時点でJava Heapが変わるため単発値を保持領域の増減とは扱わない。再インストールを挟む3試行で増加傾向はないが、同一プロセスでの長時間保持の検証とは別である。

### 遅延差の追加確認

元3組の後に同じ手順をもう1組実施し、対照CPU35.59% / 候補35.68%、遅延フレーム27/568対29/570、p90 29/30ms、GPU p90は両9msだった。元3組を除外せず、この試行は原因調査用の別枠にする。遅延率の一定した差はこの追加試行では再現しなかった。

通常CPU窓とは別に同じLemon約40〜60秒をatraceで採取した。対照/候補のDrawFramesはp50 18.435/17.817ms、p90 24.729/25.731ms、合計6,818.46/6,694.18ms（362/360回）。main doFrameはp50 4.305/4.416ms、p90 6.853/7.216ms。描画間隔中央値53.076/53.112ms。主処理の時間に大差はなく、前奏候補は単語描画経路を変更していない。遅延率変動の正確な端末側原因は未特定で、描画が速くなったとは扱わない。

前奏の約3秒も別枠トレースで確認した。対照/候補ともdoFrameは63回、間隔中央値53.028/52.957msを維持。実drawは63/4回、RenderThread DrawFrames合計1,032.19/65.92ms、p90 22.856/21.310ms。main doFrame合計267.47/114.94ms。候補ではフレーム処理自体を遅くせず、実drawのみを抑えている。

前奏で残る少数のdrawには毎秒の進捗表示や点灯変更が含まれるため、元の大量の不変フレームと1件当たりのp90の母集団が異なる。トレースで全体の処理量が減り、比較したdraw p90も悪化しなかったことから、GPU少数標本のp90上昇だけを恒常的な遅延増と判定しない。GPUクロックは窓外の停止時253MHzを確認しただけで、GPU周波数が原因と断定しない（atrace freqはCPUのみ）。

前奏のCPU/draw削減をベータへ採用した。単語区間20%は未達のまま。主観的な表示確認と実車60Hzの検証は未完了。

### 実装とユニット検証

- 点灯数だけを等値比較付きderivedStateOfへ分離し、非current行はpositionを読まず最後の点灯数とemphasisを使う。単語の計算/時計、色、速度、fade/scroll/scaleの値は無変更。
- LyricsPaneTestは既存49件無変更＋新規8件。最適化前57件中6失敗、実装後57件成功、error/skip 0。実CanvasのreadをSnapshotStateObserverで観測し、強制draw回数とは区別。通常/詳細表示の停止・再開・シーク・曲/行変更も検証。
- release buildは候補1回1m08s、機能変更なしの対照1回52s。成功済み単体試験の再実行なし。

APK SHA256: 対照本体`6f4a6f53815b8b7a063f807f31230506469f74edad9b976a41811a50180eb08f`、候補本体`dd0f88a9b516779e229119a04e4075d2818bdad00027579fbb71ef9d004ee75f`。共有署名SHA256 `44d8c612bd3948e265840f28adff0f820d5893d0ba24d888b041ca16544f7f6d`。APKはGit管理外build/perf-lyrics、数値元データは/tmp/yluncher-render-comparison。

## 候補B1: 静止背景の描画層を分離

実験ブランチperf-lyrics-background / 3df8267。HomeMiniPlayerのSurface内にBoxを追加し、matchParentSize+graphicsLayer+既存artworkBackgroundの背景を、元のBoxWithConstraintsと兄弟要素にした。追加Offscreen/Bitmapキャッシュなし。色抽出/色/余白/他画面は変更していない。

HomeMiniPlayerTestは既存39件を維持し、新規1件を追加して40件成功（failure/error/skip 0）。新規の動的寸法テストで先行5回タイムアウトし、変更前の描画でも再現を確認。時計を手動で進めてsnapshot/描画を反映するfixtureへ修正し、寸法・色・角丸の期待値や1/255の誤差は維持した。自動時計でのタイムアウトの根因は未確定。releaseは1回成功、版/署名は対照と同じ。

候補APK SHA256 `68e1b27a829b159d1a298f3a829ab8a9350baccea9c36568c39a778208e78c8d`。対照には候補Aのe5f78cb / `dd0f88a9...` を使う。Lemonの同じ暖機/30秒窓で新しい交互3組を完了した。

| 組 | 基準CPU | 候補CPU | 基準RT | 候補RT | 基準draw | 候補draw | 基準/候補の遅延フレーム数 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | 35.23% | 35.61% | 24.60% | 25.26% | 568 | 565 | 20 / 41 |
| 2 | 36.39% | 36.93% | 25.24% | 26.07% | 575 | 565 | 112 / 34 |
| 3 | 36.12% | 36.13% | 25.32% | 25.35% | 571 | 561 | 100 / 45 |

CPU中央値36.12→36.13%、RT25.24→25.35%。全組でCPU/RT削減にならず、**B1は不採用**。GPU p90は全試行9ms、描画間隔中央値は両53.11ms。遅延フレームは一定方向の差にならず、変動する遅延値だけを根拠に採用しない。PSS中央値92.40→87.44MiB、Graphics29.39→29.14MiBはGC時点等を含むため、確定した保持メモリ削減とは扱わない。

追加Offscreen背景の比較は任意候補であり、今回のB1では描画負荷削減が確認できなかったため実施しない。背景実験は別ブランチに保持し、確認用ベータへ取り込まない。

## 候補C: RuntimeShaderによる単語の直接塗り

**今回の統合は保留し、旧方式を維持する。** 専用worktree `/tmp/yluncher-lyrics-shader` に新規helperと新規テストだけを作成し、既存KaraokeLineへの配線はしていない。ソフトウェア/Native Graphicsの検証ゲートが不成立だったため、効果測定用APKへは進めない。

4種類の試験×density 1/1.4875/2の12件がすべて `Software rendering doesn't support RuntimeShader` で失敗。Robolectric SDK35・Native Graphics・REALISTIC文字レイアウトでも、実際のCanvas描画APIが例外を返した。最初の矩形（80×64px、x=-12×density、top0、bottom32）と実Textの描画で発生し、画素差を測る前に停止している。画素不一致の証明でも、端末GPUが非対応という証明でもない。

指定テストコマンドは2回。初回はテスト内density変数の名前衝突でコンパイル失敗/実行0件、修正後12件実行・12失敗・error/skip 0。assertや1/255の基準は緩めず、例外を握りつぶさず、失敗XMLと新規2ファイルを実験worktreeに保持。main側へ実装/失敗テストを取り込まない。

ハードウェアでの画素互換、絵文字、uniform更新、端末性能はすべて未確認。進める場合はGPUで画像を照合できる別の検証方法を設計する必要があり、今回の5までの統合には含めない。[指示書](../../tasks/195-word-shader-proof.md)と[公式API](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl)はAPI候補の根拠で、今回のテスト環境や性能を保証するものではない。

## 統合した確認用ベータと実機確認

本体・中継とも **2.3.1-beta2 / 49**。採用コードはAだけ。B1/Cを取り込まず、単語の既存描画方式を維持した。Task196の版2ファイル限定委譲 `.codex-bak/20261106-005509/` でreleaseを1回ビルド（47秒、成功）。成功済みの対象57件は再実行しない。全テストは正式化の別工程で行う。

| APK | SHA256 |
|---|---|
| `build/perf-lyrics/final-beta2/app.apk` | `186dd05b0a10e8078537cd2876fbfb0dfc85de9617012e8b896aaf3a93772c1c` |
| `build/perf-lyrics/final-beta2/panelhost.apk` | `b66a5defb99effa62e1c01b2017026812530c31a26762447b4b4096b0f72d249` |

実機 `192.168.137.200:40275` へ両方 `install -r` 済み。実機base.apkのSHA256、両versionCode/Name、署名を照合。本体packageは `com.gt8d.launcher`、中継は `com.gt8d.launcher.panel`。HOME、通知リスナー5件、accessibilityの既存設定は導入前と一致した。

初回のspeed-profileコンパイル直後はARTがverifyだった。既存ProfileInstallReceiverへINSTALL_PROFILEを通知し、再コンパイル後の `cmd package art dump com.gt8d.launcher` で **status=speed-profile / reason=cmdline** を確認した。比較窓のART speedとは条件が違うため、以後の場面確認値を上の性能比較へ混ぜない。

実機で確認した範囲:

- 保存接続の復元: 最終PID30219でCONNECT/READY（1,510ms）。再入力なしでLemonのApple歌詞をcache=missで取得（40行/277語、328ms）。怪獣もcache=missで取得（46行/318語、357ms）。トークン値や歌詞本文は記録しない。
- Lemonで停止37.876秒→前方シーク144.857秒→後方54.458秒→再開。PlaybackStateで停止/位置/再開を照合し、戻った位置で単語塗りと複数行の送りを画面確認。
- 歌詞OFFでジャケット、ONで同期歌詞に戻り、背景色を維持。異なるアルバムの怪獣へ切り替えて曲名・歌詞・青〜茶の背景への更新を確認。
- セット4（パネル＋マップ）でPanelActivityとMapsActivityが前面になり、パネルの1行歌詞を表示。HOMEで単一HomeActivityへ戻る。パネルは別の描画経路なので、ホームの単語塗り性能と混同しない。
- 採用版の対象57件が成功。実機で再現しにくいPlain/Loading、幅・font・zero-size、点灯境界/間奏の遷移、フェードは既存/追加の人工ケースで確認。実機ですべての人工ケースを再現したとは扱わない。
- 確認中のPID変更なし、同PIDのAndroidRuntimeエラー出力なし、熱状態0。怪獣を停止したホームで19.08秒測定し、CPU0.21%（1コア100%）、描画0回。描画0件のgfxinfoの4950ms表示は無効な分位値として評価しない。

実行した主なコマンド（テストはすべて実装担当、ADBは司令塔）:

- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.LyricsPaneTest'`: 実装後57件成功、failure/error/skip 0。
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.HomeMiniPlayerTest'`: 背景実験40件成功、failure/error/skip 0。背景は性能基準未達で不採用。
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.LyricWordBrushTest'`: Shader実験12件中12失敗。上記の環境制約を保持して保留。
- `/home/munde/tools/gradle-run.sh :app:assembleRelease :panelhost:assembleRelease`: 最終beta2成功。
- `adb.exe -s 192.168.137.200:40275 install -r F:/YLuncher/build/perf-lyrics/final-beta2/app.apk` と同ディレクトリの `panelhost.apk`: 両版更新後の実機ハッシュで照合。
- `adb.exe -s 192.168.137.200:40275 shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE -n com.gt8d.launcher/androidx.profileinstaller.ProfileInstallReceiver` → `shell cmd package compile -m speed-profile -f com.gt8d.launcher`: Success、実効フィルタを確認。
- `/tmp/yluncher-compare-run.py` でA/B各3組とAの追加1組、`/tmp/yluncher-trace-run.py` で単語/前奏を各2版採取。`python3 /tmp/yluncher-perf-sample.py beta2-final-paused 20` で最終停止状態を確認。

決めたこと: Aだけを採用し、効果のないB1と検証未成立のCは独立実験として残す。追加Offscreen案は今回は進めない。確認版は両49/beta2。端末のネットワーク・SIM・解像度・Hz設定を変えず、通常画面のミラー条件を維持。終了時は歌詞ON・怪獣を一時停止したホームにする。

## 未達・未確認と次の一手

単語区間のCPU20%削減は未達。次にShaderを追うなら、GPUで旧方式との画素を照合できる検証方法の設計から行う。今回の失敗試験を無効化して通さない。

利用者によるベータ2の見た目・操作感と、実車1920×1080/60Hzでの確認は未完了。正式版/pushはこの実験の範囲に含めない。既存の `.mcp.json` / Task170は触らず、公開前の全試験も今回は行っていない。
