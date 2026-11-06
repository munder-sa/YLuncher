# 追加7候補の実装・比較

2026-11-06。ユーザーの「おけ、全て進めて」により[追加候補](2026-11-06-runtime-cost.md#v231後の追加候補調査-2026-11-06)の7件を進める。司令塔Codex、基点main b76b707、実機正式2.3.1 / 51。実装はcodex-run、独立worktreeと明示allow、Gradle同時2本以下。

1. Task202: 非表示側のNetworkCallback/電波receiverを解除、権限とラジオ再読取を分離、UIのFlow/timerをSTARTEDに合わせる。見えている分割画面は維持。まず非表示側の重複を除き、singleton化は不要なら行わない。
2. Task203: AMLL/LRCLIBの整理時savedAt再解析を削減。件数・容量・破損・同時刻の順序を維持。
3. Task204予定: API35のUsageEventsQueryでRESUMEDに限定。既存結果/時計補正と実機受信件数を比較。
4. Task205予定: SetRunnerのwaitBoth pollを非同期に。照会と共有状態の確定を分離し、世代/照会順序で古い結果を拒否。retry/Safety等の期限を維持。
5. Task206予定: ProfileInstaller 1.4.1へ更新し、通常導入のprofile書込みと実効ARTを確認。
6. Task207予定: 最終セット番号の重複IO削減。永続化の順序・失敗後再試行を保つ。
7. Task208予定: 単語描画候補のハードウェア画素比較。独立した旧方式を参照、1/255基準は維持。既存失敗Shader実験を変更・隠蔽しない。画素互換が成立した候補だけ性能比較/統合へ進む。

決めたこと: 7候補をすべて検証対象とし、効果未確認/互換不成立の実験を強制採用しない。UI・描画・操作に変更が及ぶため、統合後は両アプリを次の正式版のbetaに揃え実機で検証し、利用者の見た目/操作感確認を求める。既存未追跡3件と過去の実験worktreeを保全する。性能率は実測した窓と条件を明記する。

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
