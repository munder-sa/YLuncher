最初に tasks/RULES.md を読み、従う。

# Task 117: 場面テストのスクリプトで、前提(デバッグ版・セット 1・セット 2)を先に確かめる

## 背景
- `scripts/device-scenarios.sh` の `set_panelCard_switch` は、セット 2(screenSets の 2 番目)の左右を設定から読んで期待値にする。ユーザーがセットを作り直してセット 2 が無いと、`set2_left`・`set2_right` が空のまま走り、「左の最前面が  でない」という分かりにくい FAIL になる。続く `link_appPanelFront` も「前提が不合格」で SKIP になり、全体が失敗扱いになる。
- セット 2 の枠がパネル(`{"type":"com.gt8d.launcher.config.Slot.Panel"}`、pkg なし)のとき、今の読み取りは `panel` という文字列を期待値にするので、`left_is`/`right_is`/openSplit の比較が必ず失敗する。パネルの枠は openSplit のログでも `com.gt8d.launcher.panel` と出る。
- 実機にリリース版が入っていると `run-as` が「package not debuggable」で失敗するが、今は「前回の設定の復元に失敗」という見当違いの理由で止まる。
- 多くの場面(set_strip_coldPanel、set_cancel_*、restore_panel_apps など)は、セット 1 が「左 YouTube(App com.google.android.youtube)| 右 パネル」であることを前提にしているが、確かめていない。

## 変更(scripts/device-scenarios.sh のみ)
1. **デバッグ版の確認**: 設定に触る最初の `run-as`(前回の退避の復元)より前に、`adb shell run-as com.gt8d.launcher true` を実行する。失敗したら `row setup FAIL 'com.gt8d.launcher がデバッグ版でない(run-as できない)。場面テストはデバッグ版を入れて流す'` を書いて `exit 1`。この時点では config_saved=0 なので、cleanup が設定を書き戻さないことを保つ。
2. **設定の読み取りを1か所にまとめる**: 今の `read -r set2_left set2_right < <(python3 ...)` を、次を出力する python3 に置き換える(`$out/config-backup.json` を読む)。
   - JSON として読めない、または `screenSets` が配列でないとき: setup FAIL `'設定(config.json)を JSON として読めない'` で exit 1。
   - 枠の変換: `pkg` があればその値、`type` が `com.gt8d.launcher.config.Slot.Panel` なら `com.gt8d.launcher.panel`、それ以外(読めない)なら空。
   - 出力は 1 行: `<セットの件数> <set1_left> <set1_right> <set2_left> <set2_right>`。無い・読めない値は `-` にする(空白区切りで read できるように)。
   - シェル側で `-` を空文字に戻す。
3. **セット 1 の確認**: set1 が「左 com.google.android.youtube、右 com.gt8d.launcher.panel」でなければ、`row setup FAIL "セット 1 が 左 YouTube | 右 パネル でない(左 ${set1_left:-なし}、右 ${set1_right:-なし})。場面テストの前提"` で exit 1。
4. **セット 2 の前提**: `set2_left` と `set2_right` の両方が空でないときだけ、セット 2 を使える(`set2_ok=1`)。
   - `run()` の中で、`set_panelCard_switch` かつ `set2_ok=0` のときは、操作を一切せず `row "$name$suffix" SKIP "前提: 設定にセット 2 がない、または枠を読めない(セットは N 件)"` を書く。N はセットの件数。
   - `link_appPanelFront` は前提が `set_panelCard_switch` のままでよいが、`set2_ok=0` のときは `row "$name$suffix" SKIP '前提: セット 2 がないため set_panelCard_switch を飛ばした'` を書く(今の「前提の … が不合格」の代わり)。
   - この 2 つの SKIP(セット 2 が無いことによるもの)は `failed=1` にしない。ほかの SKIP・FAIL の扱いは変えない。`scenario_failed`・`last_name`・`last_suffix`・`last_pass` は、今の SKIP と同じ更新をする(後続の前提判定が今と変わらないように)。
   - `@prerequisite` として呼ばれる経路(`switch_home` 後のループで `run set_panelCard_switch '@prerequisite…'` を呼ぶ所)でも同じ SKIP になること。
   - SKIP のときは `restore_panel_apps` を呼ばない(パネルの表示を変えていないため)。
5. **終わりの要約**: セット 2 による SKIP が 1 件でもあったら、cleanup の `echo "結果: $summary"` の前に `echo '注意: セット 2 が無いため set_panelCard_switch と link_appPanelFront を飛ばしました' >&2` を出す。
6. 上の 1〜5 以外の動き(場面の順番、判定、待ち時間、ほかの SKIP の理由)は変えない。

## 作るファイル・変えるファイル(これで全部)
- write scripts/device-scenarios.sh

## 使ってよいコマンド
- `bash -n scripts/device-scenarios.sh`
- 変換の python3 を確かめるための `python3 -c ...` は使わない(実行の許可がない)。確かめた方法は報告に書く。

## 報告
- 変えた箇所の一覧(行の目安)、決めたこと、実行したコマンドと結果。
