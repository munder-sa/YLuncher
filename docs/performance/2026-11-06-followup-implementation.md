# パフォーマンス改善の実装・比較

## 第二段階: beta2後の6候補 (2026-11-06)

ユーザーの「おけ、進めて」で[6候補](2026-11-06-runtime-cost.md#beta2後の効率化候補調査-2026-11-06)へ着手。基点perf-followup / 510187e。beta2の操作感は利用者が「問題なし。実機の比較作業へ進めてよい」と回答。前奏テストの旧毎フレーム増加要求を新仕様へ更新することも明示承認済み。新しい正式版/pushはこの時点で行わない。

| Task | 現在の状態 | 確認範囲 |
|---|---|---|
| 216 前奏/間奏の境界更新 | 4e7c049→a92a618で取り込み | 対象85件成功（既存65＋新規20）、実機未確認 |
| 217 時計 | 6ca3db6→4802768で取り込み | 対象45件成功（既存32＋新規13）、実機未確認 |
| 218 初回アイコン | db72f8e→166c343で取り込み | 対象59件成功（既存44＋新規15）、実機の時間未測定 |
| 219 描画後マスク | 13f5b9f→f729db1、独立プローブのみ | offline Debugビルド1回28秒成功、実GPU未実行 |
| 220 上下端フェード | aefa8e2→0ed8310、独立プローブのみ | 統合offline Debugビルド1回10秒成功、GPU未実行 |
| 実収録プロファイル | Task221専用収録APKを準備 | 実機接続待ち、収録/profile変更なし |

Task217は表示中の分/時刻変更/タイムゾーンbroadcastで更新し、STOP/dispose時に解除、STARTで最新へ同期。時計サイズの探索結果と有界128候補の寸法cacheを保持し、非関連compositionでは計測を省く。非同期font完了、fontScale/制約/時刻/日付変更も試験済み。サイズ探索の2sp刻み/overflow判定は維持。初回compileはテストでsealed interfaceを匿名継承して失敗し、AndroidFontの実非同期読込fixtureへ修正。次は新規6件が親領域の境界設定で失敗、高さ/余白だけを直して45件成功。既存テスト変更なし。成功後の再実行なし。

Task218は実際の画面に使う候補だけを初回info待ちの対象とする。ページ/スクロール内の細分化はせず、表示画面の候補全体を準備する。全内容を消すpreparedリセットはcatalog交換時だけ。背景の要求は2frame待ち、少なくとも1描画機会を空ける。Deferredで非表示infoを止めても表示対象完了後に画面が出ること、config/view/now/catalog変更とクリック先を検証。初回58件中、新規14件がNative Graphics未指定による画像作成NPEで失敗。fixture修正と予約キャンセル1件追加後は59件成功。既存テスト変更なし。

Task216は既存interludeLitDotsの計算を判定条件に、次に点灯数が変わる整数msを探索し速度換算して予約する。0速度/終端は休止、負/非有限速度は旧frame補間へfallback。単語塗り/行indexの200ms/introの200ms/行移動アニメは維持。対象85件を1回1m12sで成功。既存変更は承認済みの毎frame増加assert2箇所と、仮想再生時計とdelayの時間を合わせるfixtureだけ。境界で1回更新されることを既存試験にも追加、画素/点灯数/保持alphaは維持した。XML/出力はbuild/perf-next/tests/216-interlude。

3件の統合後、`/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.*'` を1回実行し、**6m53s、50クラス/1,083件成功、failure/error/skip0**。個別試験の再実行目的ではなく、PanelScreen/Home/時計/歌詞を合わせたUIの相互作用を確認した。アプリ全テスト・Release全テストはこの段階では実行していない。ログ `/tmp/yluncher-next-ui-integration.txt` と全XMLを `build/perf-next/tests/ui-integration/` へ保存、対象外クラス0を確認した。

Task222の同版比較APKも準備。対照は機能基点075f919＋版差分の5bb6874、候補は3改善込みa92a618＋版差分の6535c74（UI統合成功後に1edd47fとしてrootへ取り込み）。両app/panelhostは2.3.2-beta3 / 54、release build各1回47秒/48秒成功。非debuggableと既存と同じ署名44d8c612…7f6dを確認した。`build/perf-next/{control,candidate}/` にAPK、SHA256SUMS.txt、source.jsonを保存。候補app SHA256は `2db786ff7ee6fe445a9a6782deed95706e7ff6b1560da0757fe3f74b197e6ece`、panelは `bf5ca15685adfc35c7efa7cd7629c9f6e0b728a52307fa9764a61c073192d440`。未インストール。

Task221は候補と同じソース/版から専用worktreeのminify/shrinkだけfalseにし、app release1回58秒成功。専用branchの253682c、rootへ未取り込み。non-debuggable/同署名とDEX内の元のInterludeUpdatePlan descriptorを確認。44,256,120 bytes、SHA256 `0eeea215c25ca0f6ba1625fea325a0fa29dca23630a83a4263000a5c923accd3`。成果物は `build/perf-next/profile-capture/app-capture.apk` で、通常適用する候補APKとは別。プロファイルの実収録・抽出・採用判断は未実施。

対象コマンドは[Task217](../../tasks/217-clock-work.md)・[Task218](../../tasks/218-initial-icons.md)のとおり。最終XMLと全Gradle実行の終了code/出力を `build/perf-next/tests/{217-clock,218-icons}/` に保存（runs.json、summary.json）。実行は各3回/2回、失敗を成功として集計しない。両codex-runの変更ファイルはallowと一致。command警告は--tests引用符の正規化差で、events.jsonl内の実行文字列が指定と一致することを確認した。

Task219は通常Textを描いたRenderNode内容へRuntimeShaderを適用する別方式。旧text_shaderと663ケース/1成分差1/255を保存。新mode=post_maskでも同じケースを使い、保持したeffectのuniform更新後と再生成後を比較する追加取得を12回含む。全体のケース数は663のまま。effectを更新ごとに再生成するためallocationがあり、Offscreenも残る。ビルド成功は画素互換/性能成功を意味しない。コマンドは `/home/munde/tools/gradle-run.sh --offline -p tools/renderprobe -Pandroid.useAndroidX=true assembleDebug`、ログ `/tmp/yluncher-post-mask-build.txt`。

Task220は1回recordした内容を上下端/中央の互いに重ならないclipで再生し、両端だけをsaveLayer/DstInする試験。3回の描画命令再生が増えるため、採用を先に決めない。全体合成/範囲限定/フェードなし診断を同じ内容で比較する。フェードなしは寄与診断専用。画素一致が成立した候補だけ本体へ検討する。

Task220はManifest追加と新規プローブ/説明文書の3ファイルのみ。216ケースを定義し、元のword用663ケース/1成分差1/255は維持。2framecommit、ROIだけのPixelCopy、timeout時の遅延bitmap回収も照合。新描画コードの取り込み後に同じofflineコマンドで1回10秒成功、ログ `/tmp/yluncher-edge-fade-build.txt`。両プローブ入りAPKは `build/perf-next/probes/renderprobe.apk`、22,510,135 bytes、SHA256 `9792e0666aaf462fd240d5de81d8fbc1e2c5f93fd41bccface09282bbf14aeda`。実行0件のため「663＋216件合格」とは報告しない。プローブは本体から参照していない。

実収録profileは[Android公式の手動生成](https://developer.android.com/topic/performance/baselineprofiles/manually-create-measure)に沿えばMacrobenchmark追加依存なしで可能。非debuggableかつ非R8の専用収録APKが必要で、現在の最適化済みbeta2からそのままソース名のprofileを生成できるとは扱わない。独立worktreeで収録用APK→プロファイル保存/抽出→最適化済み候補APKへの復元までを1工程とし、通常導入と強制compileの比較条件を分ける。未収録の架空ルールは追加しない。

11時台の実機確認: `adb.exe devices -l` は最初192.168.137.200:40275 device、その後package/activity/idの読み取り時にclosed/offline。再connectは10061接続拒否、devices一覧とmdnsサービス一覧は空になった。利用者へ新接続先を質問済み。今回の画面操作・インストール・端末設定変更はまだ0回で、最後に導入確認済みなのは両2.3.2-beta2 / 53。接続復帰までPC上の作業を継続する。

PC側の実装/レビュー/対象・統合テスト/比較APK準備を完了。実行中Taskなし。最後に標準ADBポート5555への接続も拒否され、devices一覧は空のまま。残る実機比較、プロファイル収録、候補の実機適用は接続先の回答待ち。今回の新しいCPU削減率は主張しない。決めたことは各Task・上記の実装/比較条件に記録し、正式化/push/公開はまだ行っていない。

## 第一段階: 追加7候補

2026-11-06。ユーザーの「おけ、全て進めて」により[追加候補](2026-11-06-runtime-cost.md#v231後の追加候補調査-2026-11-06)の7件を進める。司令塔Codex、基点main b76b707、実機正式2.3.1 / 51。実装はcodex-run、独立worktreeと明示allow、Gradle同時2本以下。

1. Task202: 非表示側のNetworkCallback/電波receiverを解除、権限とラジオ再読取を分離、UIのFlow/timerをSTARTEDに合わせる。見えている分割画面は維持。まず非表示側の重複を除き、singleton化は不要なら行わない。
2. Task203: AMLL/LRCLIBの整理時savedAt再解析を削減。件数・容量・破損・同時刻の順序を維持。
3. Task204予定: API35のUsageEventsQueryでRESUMEDに限定。既存結果/時計補正と実機受信件数を比較。
4. Task205予定: SetRunnerのwaitBoth pollを非同期に。照会と共有状態の確定を分離し、世代/照会順序で古い結果を拒否。retry/Safety等の期限を維持。
5. Task206予定: ProfileInstaller 1.4.1へ更新し、通常導入のprofile書込みと実効ARTを確認。
6. Task207予定: 最終セット番号の重複IO削減。永続化の順序・失敗後再試行を保つ。
7. Task208予定: 単語描画候補のハードウェア画素比較。独立した旧方式を参照、1/255基準は維持。既存失敗Shader実験を変更・隠蔽しない。画素互換が成立した候補だけ性能比較/統合へ進む。

決めたこと: 7候補をすべて検証対象とし、効果未確認/互換不成立の実験を強制採用しない。UI・描画・操作に変更が及ぶため、統合後は両アプリを次の正式版のbetaに揃え実機で検証し、利用者の見た目/操作感確認を求める。既存未追跡3件と過去の実験worktreeを保全する。性能率は実測した窓と条件を明記する。

## 採否と確認範囲

| 候補 | 判断 | 確認できたこと |
|---|---|---|
| 非表示中の監視・UI更新 | 採用 | 同曲の非表示再生20秒×3でCPU中央値4.213→0.198%（単一core換算）。NetworkCallbackはHOMEで2→1、全画面非表示で2→0 |
| 歌詞キャッシュの整理 | 採用 | AMLL上限内のsavedAt読取0、超過時は各ファイル1回。LRCLIBも比較ごとのJSON再読取を削減。既存の削除順序を維持 |
| 履歴イベントのOS側絞込み | 採用 | 実機の7日分7499→771件。新旧の必要イベントは6組とも一致。query中央値約18→7ms（検証プロセス） |
| 分割待機の周期照会 | 採用 | waitBothのpollをIOへ移動。遅延・キャンセル・順序逆転を単体検証、実機で左右パネル・連続切替・キャンセル・リンクを確認 |
| ProfileInstaller | 採用 | 1.4.1で通常起動時のprofile書込みを確認。直後ARTはverifyで、通常導入直後のAOT完了は未確認 |
| 最終セット番号の重複IO | 採用 | 同値保存の省略、失敗後の再保存と同期順序を単体検証。実機のプロセス再起動後も番号4を保持 |
| 単語描画のShader化 | 不採用 | 実GPU全663ケース中360不一致、最大154/255。画素互換を満たさず、本体の描画方式を維持 |

実機は両 **2.3.2-beta2 / 53**。PC接続1920×1080の実効30Hzであり、実車60Hzの性能・長時間の発熱/電力は未測定。自動検証は完了、利用者の表示/操作感確認は回答待ち。作業ブランチは `perf-followup`、正式版への昇格・main取り込み・push・Release公開は未実施。

## 個別検証の経過

- Task203 `/tmp/yluncher-cache-pruning/.codex-bak/20261106-090903/`: 2実装+新規9試験、既存試験無変更。指定コマンド1回で31件/失敗error skip 0。上限内savedAt読取0、超過時各ファイル1回、同時刻/破損/削除失敗/300件/32MiB境界を検証。差分とXML照合後751edd7をe99a2c8として取り込み。内部観測callbackは既定null、FileとLongの組だけ保持。
- Task202 `/tmp/yluncher-visible-ui/.codex-bak/20261106-090901/`: 許可10ファイル（実装7+helper1+新規テスト2）、既存試験無変更。指定コマンド1回で382件/失敗error skip 0（新規12件）。callback/receiverはSTARTED〜STOP、登録世代で旧通知を拒否。既存collectAsStateWithLifecycleとrepeatOnLifecycleを使用し追加依存なし。9547c5cをc7dc8e9へ取り込み。実機表示・比較はこれから。
- 上記2委譲のallow警告は既知の--tests引用符正規化差のみ。実行イベントのコマンドは指定文字列、成功後の再試験なし。
- Task206 `/mnt/f/YLuncher/.codex-bak/20261106-091705/`: profileinstallerを1.3.1→1.4.1の1行だけ変更。ビルド/実機確認前で未確定。
- 実機変更前の探索窓: `/tmp/yluncher-followup-baseline.json`。正式2.3.1 / PID19652でホーム再生中19.79秒CPU36.736%（単一core換算）、Apple画面でランチャー非表示19.69秒4.215%。両条件callback2件。これは各1窓の診断で、厳密な曲区間固定比較や削減率ではない。終了時に再生停止しHOMEへ戻した。
- Task207 `/tmp/yluncher-last-index/.codex-bak/20261106-091651/`: 指定1回30件（既存14+新規16）、失敗error skip0。保存確認済み値とcacheを分離し、失敗時は同値でも再保存、同期順序とthread安全を確認。単一production writer、実機scenarioの外部書込後はアプリ再起動を確認。aeb13f0→d682919で取り込み。
- Task204は新規fixtureの時計固定で3回とも29件中1失敗（既存26件は成功）。Task211でUsageHistoryの既定System時計をinternal注入境界にし、fixtureだけ120000msを明示。期待値/全イベント/旧API参照/アサーション無変更。1回29件成功、error skip0。ログ `/tmp/yluncher-usage-filter/.codex-bak/20261106-092222/`、d605c64→1224a4dで取り込み。Task205はこのworktreeから委譲開始。
- Task210のJava→DEXは各コマンド1回成功。D8のAPI35警告は出たが、実機app_processでAPI35の呼出し成功を確認した。root/system contextのAppOps=allowed、OS設定変更なし。`adb.exe ... push F:/YLuncher/build/usageprobe/dex/classes.dex /data/local/tmp/yluncher-usage-probe.dex` → `shell 'CLASSPATH=/data/local/tmp/yluncher-usage-probe.dex app_process /system/bin UsageEventsProbe'` を各1回実行、終了0。
- 同じ固定期間で旧/新を各3組比較。24時間:全1671件→RESUMED312件、7日:全7499件→RESUMED771件。6組とも種別/package/class/timeの順序付きdigestが一致、新経路の他種別0。query時間msは7日旧18.048646/17.779583/19.791875、新7.357031/7.172448/7.595209。解析（digest含む）は旧10.645104/7.892292/7.984114、新6.755052/6.803802/7.525938。rootの検証プロセスでの比較であり、全体CPU/実アプリの操作遅延率に換算しない。
- Task209（092600）両52/2.3.2-beta1へ、release1回1m09s成功。同署名SHA256=44d8c612…7f6d。`build/perf-followup/beta1/` 本体a9a0021ba3fa9296caed2b90215e6d11236a4ff61fc4f31e65ac9db1cec66bca、中継59dc9d34f0a3e3f424352fd3d24ab0647e89a9d6b1dcfa3a371c2ca3301c2909。実機両install-r成功、HOME/通知listener/accessibility保持、PID20177。
- ProfileInstaller1.4.1は通常起動7秒後09:29:36にInstalling profileを出し、profileWrittenFor_lastUpdateTime.datとprimary.profの更新を確認。直後のARTはverify/reason=installで、自動AOT済みとはしない。旧版との比較条件を揃えるため、その後 `cmd package compile -m speed-profile -f com.gt8d.launcher` を実施（Success）。
- beta1でセット4（パネル＋Maps）→HOMEを確認。HOMEのcallbackは2→1、非表示時は2→0。旧版の非表示20秒×3は4.291/4.096/4.213%、beta1の同じ曲・前景の3窓は0.000/0.297/0.198%（単一core換算、中央値4.213→0.198%、約95.3%減）。0.000はtick分解能で増分0を意味し消費電力ゼロではない。beta1の別の試行で途中選曲1件と別曲1件を記録し、同曲比較から除外。取り直しはドラッグで怪獣127.617秒へ合わせた。原本 `/tmp/yluncher-followup-baseline-hidden-{1,2,3}.json` と `beta1-matched-{1,2,3}.json`。非表示中だけの比較であり、単語描画/全体CPU/実車60Hzへ一般化しない。
- Task205（092459）は初回225件中LinkEntry6件失敗。executorをwatcherに結び付けてdeps.copyのFake互換を保ち、既存期待値無変更のまま再試験226件成功/error skip0。51a39a4→23efeecへ取り込み。共有request番号・操作/poll世代と確定前権限確認、実worker終了まで1枠を保持する。beta1には未含有、実機は次betaで検証する。
- Task208/212 GPUプローブは独立した旧Text/マスク対照、663ケース、1/255基準。Task212ビルド1回成功。ただしwrapperは541件の生成物を逸脱と検出（tools/renderprobe/.gradle13件/build528件）。司令塔の新規subprojectに対する除外指定漏れであり、全件を分類照合した。実装変更は許可ProbeActivity.ktのみ。ログを維持し、.codex.confへこのsubprojectの生成物3ディレクトリだけを追加、アプリ/テスト検査を無効化しない。成功ビルドは再実行しない。実GPU比較はこれから。

## 統合検証

- Task213（GPU worktree、094142）は描画中に登録したframe-commit callbackのため、次frameを直ちに要求するよう修正。Android15のViewRootImplはdraw前にcallbackを採取するため、callback到着後だけinvalidateする旧プローブでは待ち続けた。[AOSPの実装](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/core/java/android/view/ViewRootImpl.java)と照合した。現コンテンツの実commitを2回待つ条件、全画素/全ケース/1÷255基準は維持。指定ビルド1回14秒成功、逸脱なし。
- GPU run1は司令塔が検証アプリのみ停止。画素結果は得られておらず、互換性の合否には使わない。修正後run2の実機比較を実行中。原本 `/tmp/yluncher-gpu-proof-run{1,2}.txt` を保持し、失敗も記録する。
- Task214は版2ファイルのみ、両 **2.3.2-beta2 / 53**。6項目の実装をすべて含む。releaseビルド1回50秒成功、逸脱なし。`build/perf-followup/beta2/` 本体 `ccd5277fadb413d9eaba41869c2f59bca8b4a80be745def8c2489180a663e751`、中継 `14f3f9baa106b5bbfac01b5710e8a57b6403a97b7194a3bdd1a471bacf84fa12`。両署名は従来と同一（SHA256 44d8c612…7f6d）。
- 広い変更の統合確認として、司令塔がrootで `codex-verify` を1回開始。既定の `$HOME/tools/gradle-run.sh test assembleDebug`、全Debug/Release単体とDebugビルド。個別成功分の無目的な再実行ではなく、6項目を合わせた初回の全体検証。終了ログは `build/perf-followup/tests/verify.txt`、現在実行中。

### GPU候補の判定: 不採用

- run2は全 **663件、成功303/画素不一致360、未実行0、hardware=1**。描画/PixelCopy例外、レイアウト・fixture不一致は0、失敗理由はすべてpixel_mismatch。1成分の最大差154/255（許容1/255）。通常文字だけでも最大23、結合文字145、カラー絵文字154。fill<=0でも絵文字の候補表示が残る条件があった。合成と文字描画の画素互換が成立しないため、本体へ適用しない。
- emphasis=0の210件はすべて同一であり、成功303件を可視状態の互換率として示さない。owner_Bの非更新比較15件は同一だが、旧方式とのseed比較3件は不一致。uniform更新・所有者隔離が動くことと旧表示への互換性を区別する。
- 現行の描画コード/閾値/旧Software Canvasの失敗12件は変更なし。候補のCPU測定は実施しない（画素比較に失敗したため）。独立プローブは8962f10→bb935edとして保存、本体のsettings.gradleには追加しない。使い方は `tools/renderprobe/README.md`。原本 `/tmp/yluncher-gpu-proof-run2.txt`、全数値 `/tmp/yluncher-gpu-proof-run2-cases.json`。

### 統合テストのfixture修正

- 初回codex-verifyは10m40sで終了1。app Release **2,105件中54失敗**、error/skip0。すべて旧private field `lastSetFile` に依存した準備処理（HomeActivityTest12/HomeActivityUiTest9/PanelActivityTest32/SettingsActivityTest1）。残る2,051件は成功。Debug全件はこの実行では未実行。
- Task215（別worktree、095345）はtest-only LastSetIndexFixtureで実ファイルの存在/bytesと旧store objectを退避し、試験中だけ新しいstoreを使い、終了時に双方復元する。アプリの実装は変更せず、既存4クラスのsetup/cleanupだけ変更。実ディスクの"4"を確認する4箇所を含む全assert/テスト名/期待値を差分照合で維持。
- 指定コマンド1回で対象Debug60/Release60成功、failure/error/skip0。e1c6114を作業ブランチへ取り込み。初回Releaseの全XMLと修正後の対象XMLを `build/perf-followup/tests/{initial-release,fixture-debug,fixture-release}/` へ保存。対象外のRelease成功分は再実行しない。
- 未実行範囲を `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest :panelhost:testDebugUnitTest :panelhost:testReleaseUnitTest :app:assembleDebug :panelhost:assembleDebug` で補完中。ログ `build/perf-followup/tests/remaining.txt`。個別検証や失敗修正の重複分は最終の総件数へ加算しない。
- 補完は **10m19sで成功**。app Debug2,105件、panelhost Debug/Release各5件、failure/error/skip0。両Debugビルドも成功。panelhostのテストはGradleのUP-TO-DATE判定を含む。初回Releaseの変更外2,045件＋Task215の60件を、クラスとtestcase名の集合一致で照合し、Release全2,105件の合格を確認した。最終範囲は **4,220件（variant別）、未検証のtestcaseなし**。
- 全体コマンドを最初から再実行してはいない。初回失敗54件の原本はそのまま保存し、修正対象の4クラスだけを再検証した。`release-coverage.json` に各クラスの根拠XML、`coverage-summary.json` にvariant別集計を記録。Debug全件とpanelhostのXMLも同じtests配下へ退避。初回NGを成功ログで上書きしない。
- 最終の差分/署名/版/非debuggable/実機APKハッシュ/既存assertを照合。Task215はテストのみなのでbeta2 release APKの再ビルド・再インストールは不要。本体ソースは適用物から変更していない。委譲とGradleはすべて終了。残る作業は利用者の見た目/操作感評価、必要な不具合対応と正式化の判断。

### beta2の実機確認

- 本体/中継のinstall-r成功、実機base.apkと上記ハッシュ一致、既定HOME/通知リスナー/既存ユーザー補助を保持。PID15774、最終プロセス再起動後26830。設定JSONのSHA256は全場面で `4f48e426218f3c90963fb16b495117bee748cbb26b53760b70f10a681c2b6c50` のまま。SIM・回線・表示設定を変更しない。
- 左パネル＋Maps: 初回ready約3.33秒、再表示約0.33秒。右YouTube Morphe＋パネル: 初回約2.20秒、再表示約0.59秒。これはログ上の各1操作で、旧版に対する速度向上率ではない。全場面で対象2つがtopResumed、見えているパネルのcallbackは1件。
- 右セットの起動中1.29秒で実際の「ホームへ戻る」をタップ。Cancelled/`opening cancelled`、遅れて到着したpanel/appをcancel guardがホームへ戻し、その後6秒で旧ready・Safetyなし。HOME単独、番号0を確認。
- 起動中に左端をスワイプし次セット2を指定。`open deferred pending=2`→旧セットready→次セットの起動/readyの既存順序を維持。最終はApple Music＋Maps、番号2、callback0。古い結果で前セットへ戻る動きなし。
- 左パネルからはBrave＋Mapsの前景2つを確認したが、初回ブラウザ起動の経路ログは取得できなかった。右パネルからはAppPanel/Right→SplitWithBrowser/Right→waitBoth readyをログと前景で確認。初回ログ欠落を調べる追加streamでも右の経路が一致。既存設定はInAppSplit/Brave、設定変更なし。
- セット4→HOME→本体force-stop→HOMEでPID変更、番号4/設定ハッシュを保持。ホームで怪獣の歌詞・単語の塗り・背景、再生→停止を確認。最終怪獣PAUSED/209,573ms、歌詞ON、HOME、callback1。検証窓の同PID AndroidRuntimeエラーなし。実際の取得済み画面は確認後削除。
- beta2の通常導入でもprofileWrittenFor_lastUpdateTime.datとprimary.profが更新。最後のARTはverify/reason=install。beta2では強制compileを実行せず、初回AOTを主張しない。
- 原本は `/tmp/yluncher-followup-scenes/` の数値・対象ログのみ。検証用renderprobeアプリ、端末上のUsageEvents DEX、一時画像3枚を削除済み。実機の表示/操作感を利用者へ確認依頼し、回答まで司令塔の画面操作を停止。非画面操作の全体テスト/記録を継続する。
