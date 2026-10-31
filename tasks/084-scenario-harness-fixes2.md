# Task 084: 場面テストの仕組みの不具合を直す・その2

最初に `tasks/RULES.md` を読み、従うこと。**adb は実行しない(司令塔が実機で流す)。**

## 背景(司令塔が実機で流した結果 `build/device-scenarios/20261031-180913-479004/summary.md`。各場面のディレクトリを読んでよい)
製品のコードの不具合は見つかっていない(左パネルの起動・アプリの起動・ダブルタップの保存は合格。「YouTube が全画面のときに左パネルのセットを開く」も司令塔が手で2回試して成立)。残りの失敗はテストの仕組みの側:
1. **ログの取りこぼし**: 端末のネットワークが切れると ES ファイルエクスプローラー(`com.estrongs.android.pop`)が毎秒千行以上のエラーを出し、main のログ(10MiB の輪)から YLuncher のログが押し出される(`build/device-scenarios/20261031-180913-479004/set_panelCard_switch/logcat-full.txt` は 18:11:30〜35 の 5 秒分・6448 行しかなく、`logcat.txt` は空)。
2. **強制停止の直後に開く**: `build/device-scenarios/20261031-180913-479004/set_leftPanel_cold@prerequisite@run2`: 準備の force-stop で分割が解けて YouTube が全画面で起こし直され(18:12:18.19〜18.40 に resume)、その直後(18.39)に open したため、成立の判定が「アプリ側の resume」を見つけられず Safety。
3. **後片付けでセット1を開き直さなくなった**(083): `link_appPanelFront` の前提は「セット1(YouTube | パネル)が開いている」ことだが、`set_panelCard_switch` の後片付けが表示を戻すだけになったため、YouTube | Apple Music のままで `front=Fullscreen` になり FAIL(`build/device-scenarios/20261031-180913-479004/link_appPanelFront@ylhome`)。
4. **前提の作り直しの制限が強すぎる**(083): 合格済みの前提でも、間に別の場面が入ると作り直せず SKIP になる(`leftPanel_link` など)。

## 変更するファイル(これで全部)
- `scripts/device-scenarios.sh`

使ってよいコマンド:
- `bash -n scripts/device-scenarios.sh`

## 仕様
1. **ログを流し続けて取る**: 各場面の準備の最初(今 `logcat -c` している所の直後。場面ごとに)に、`adb logcat -v time GT8D:D '*:S'` をバックグラウンドで `"$active/logcat-stream.txt"` へ流し始め、`capture` でそれを止めてから `logcat.txt` に使う(`adb logcat -d` の結果と、流した結果の両方を残し、判定は流した方を優先)。止め忘れないよう、cleanup でも止める。システムのログ(`onDoubleTappedDivider` を探す所)も同じように、場面の操作の間だけ `adb logcat -v time` を `"$active/logcat-full-stream.txt"` へ流し、そちらで探す。開始時に `adb logcat -G 16M` を試みる(失敗してもよい)。
2. **強制停止の後に待つ**: 準備で `am force-stop` をした後、セットを開く操作の前に 3 秒待つ(該当する全場面)。
3. **後片付けでセット1を開き直す**: `restore_panel_apps` を「`--es view apps` → `[DebugPanel] view=Apps saved` を確かめる → `--ei open 1` → `[SetRunner] waitBoth ready left=com.google.android.youtube right=com.gt8d.launcher.panel` を最大 10 秒待って確かめる」にする。どちらかが確かめられなければ WARN(今のまま)。
4. **前提の作り直し**: 前提が**不合格だった**ときだけ、同じ実行の中でその前提の作り直しを1回に限る。合格した前提は、必要になるたびに作り直してよい(今の `--only` の前提の実行と同じ形)。何重の `@prerequisite` にならないよう、作り直しの名前は `<前提>@prerequisite@runN` にする。
5. 判定の文言・場面の並び・他の場面の判定は変えない。

## この Task でしないこと
- adb の実行。アプリのコードの変更。git のコミット等。

## 報告
変更点、`bash -n` の結果、**決めたこと**。
