#!/usr/bin/env bash
# 実機での実行は司令塔が行う。ADB は空白区切りのコマンドとして指定する。
set -uo pipefail
read -r -a adb_cmd <<< "${ADB:-adb.exe -s 192.168.137.248:5555}"
adb() { "${adb_cmd[@]}" "$@"; }
with_home=0
only=''
names=(link_cold_noTasks link_panelTaskPresent link_warm_noTasks link_homeTaskPresent link_fromYlHome set_strip_coldPanel)
while (($#)); do
    case "$1" in
        --with-ylauncher-home) with_home=1; shift ;;
        --only) [[ $# -ge 2 ]] || { echo '--only に場面名が必要です' >&2; exit 1; }; only=$2; shift 2 ;;
        *) echo "不明な引数: $1" >&2; exit 1 ;;
    esac
done
if [[ -n $only ]]; then
    valid=0
    for name in "${names[@]}"; do [[ $only == "$name" ]] && valid=1; done
    ((valid)) || { echo "不明な場面名: $only" >&2; exit 1; }
fi
out="build/device-scenarios/$(date +%Y%m%d-%H%M%S)-$$"
mkdir -p "$out" || exit 1
summary="$out/summary.md"
printf '| 場面 | 結果 | 理由 |\n|---|---|---|\n' > "$summary"
failed=0
active=''
home_saved=0
browser_saved=0
original_home=''
original_browser=''
row() {
    local reason=${3//$'\n'/; }
    reason=${reason//|/／}
    printf '| %s | %s | %s |\n' "$1" "$2" "$reason" >> "$summary"
}
capture() {
    local dir=$1 rc=0
    adb logcat -d -v time GT8D:D '*:S' > "$dir/logcat.txt" || rc=1
    adb logcat -b events -d -v time > "$dir/events-full.txt" || rc=1
    awk '/wm_/ {print}' "$dir/events-full.txt" > "$dir/events.txt"
    adb shell dumpsys activity activities > "$dir/activities.txt" || rc=1
    awk '/\* Task\{/ {print}' "$dir/activities.txt" > "$dir/tasks.txt"
    adb exec-out screencap -p > "$dir/screen.png" || rc=1
    [[ -s $dir/screen.png ]] || rc=1
    return "$rc"
}
cleanup() {
    local status=$? current
    trap - EXIT INT TERM
    if [[ -n $active ]]; then
        capture "$active" || failed=1
        row "${active##*/}" FAIL '途中終了。終了時点のログと画面を保存'
        failed=1
    fi
    if ((home_saved)); then
        if ! adb shell cmd role add-role-holder android.app.role.HOME "$original_home" 0; then
            row cleanup FAIL '既定のホームの復元コマンドが失敗'; failed=1
        fi
        current=$(adb shell cmd role get-role-holders android.app.role.HOME) || failed=1
        current=${current//$'\r'/}
        if [[ $current != "$original_home" ]]; then
            row cleanup FAIL "既定のホームの復元不一致: $current"; failed=1
        fi
    fi
    if ((browser_saved)); then
        current=$(adb shell cmd role get-role-holders android.app.role.BROWSER) || failed=1
        current=${current//$'\r'/}
        if [[ $current != "$original_browser" ]]; then
            echo '警告: 既定のブラウザが開始時から変化しました' >&2
            row cleanup WARN "既定のブラウザが変化: $original_browser → $current"
            failed=1
        fi
    fi
    echo "結果: $summary"
    ((status == 0 && failed == 0)) && exit 0
    exit 1
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
original_home=$(adb shell cmd role get-role-holders android.app.role.HOME) || exit 1
original_home=${original_home//$'\r'/}
[[ $original_home =~ ^[a-zA-Z0-9_.]+$ ]] || { row setup FAIL '現在の既定ホームを単一パッケージとして取得できない'; exit 1; }
home_saved=1
original_browser=$(adb shell cmd role get-role-holders android.app.role.BROWSER) || exit 1
original_browser=${original_browser//$'\r'/}
browser_saved=1

remove_yl_tasks() {
    local dump ids id
    dump=$(adb shell dumpsys activity activities) || return 1
    ids=$(awk '/\* Task\{/ && / A=[0-9]+:com\.gt8d\.launcher[^[:space:]}]*/ {
        for (i=1;i<=NF;i++) if ($i ~ /^#[0-9]+$/) {sub(/^#/, "", $i); print $i}
    }' <<< "$dump")
    while IFS= read -r id; do
        [[ -z $id ]] || adb shell am stack remove "$id" || return 1
    done <<< "$ids"
}
reset() {
    adb shell input keyevent KEYCODE_HOME || return 1
    remove_yl_tasks || return 1
    if [[ ${1:-} == cold ]]; then adb shell am force-stop com.gt8d.launcher || return 1; fi
    sleep 1
    adb logcat -c && adb logcat -b events -c
}
home_activity() { adb shell am start -n com.gt8d.launcher/.ui.home.HomeActivity && sleep 3; }
chatgpt() { adb shell monkey -p com.openai.chatgpt -c android.intent.category.LAUNCHER 1 && sleep 4; }
link() {
    adb shell am start -a android.intent.action.VIEW -d https://example.com/ --es android.intent.extra.REFERRER_NAME "android-app://$1"
}
# 全枠と各枠の最前面 Activity を出力。区画の境界は字下げで決める。
parse_slots() {
    awk '
    function indent(s) {match(s,/[^ ]/); return RSTART-1}
    function emit() {if (slot) print side " " (top!="" ? top : hist!="" ? hist : "EMPTY")}
    function component(s, a,n,i) {
        n=split(s,a,/[[:space:]]+/)
        for(i=1;i<=n;i++) if(a[i] ~ /^[A-Za-z0-9_.]+\/[A-Za-z0-9_.$]+$/) return a[i]
        return ""
    }
    {
        depth=indent($0)
        if(slot && depth<=slotdepth && $0 ~ /[^[:space:]]/) {emit(); slot=0}
        if(/\* Task\{/ && /type=undefined/ && /mode=multi-window/) {
            slot=1; slotdepth=depth; side="UNKNOWN"; top=""; hist=""; child=0; next
        }
        if(!slot) next
        if(/mBounds=Rect\(/) {
            s=$0; sub(/^.*mBounds=Rect\(/,"",s); sub(/,.*/,"",s)
            if(side=="UNKNOWN") side=(s+0>0 ? "RIGHT" : "LEFT")
        }
        if(/\* Task\{/ && /type=standard/ && / A=/ && !child) {child=1; childdepth=depth; next}
        if(child && depth<=childdepth && /[^[:space:]]/) child=2
        if(child==1 && /topResumedActivity=ActivityRecord\{/ && top=="") top=component($0)
        if(child==1 && /Hist #[0-9]+.*ActivityRecord\{/ && hist=="") hist=component($0)
    }
    END {emit()}' "$1"
}
reasons=()
expect_log() { if ! grep -Eq "$1" "$active/logcat.txt"; then reasons+=("ログ不足: $1"); fi; }
no_safety() { if grep -Fq 'reason=Safety' "$active/logcat.txt"; then reasons+=('reason=Safety が発生'); fi; }
browser_created() { grep -E 'wm_create_activity.*com\.gt8d\.launcher/([^], ]*\.)?FullscreenBrowserActivity' "$active/events.txt" >/dev/null; }
right_is() {
    local expected=$1 actual
    actual=$(awk '$1=="RIGHT" {print $2}' "$active/slots.txt")
    if [[ $expected == com.gt8d.launcher ]]; then
        [[ $actual == com.gt8d.launcher/.ui.panel.PanelActivity || $actual == com.gt8d.launcher/com.gt8d.launcher.ui.panel.PanelActivity ]] || reasons+=("右の最前面が PanelActivity でない: ${actual:-枠なし}")
    else
        [[ $actual == "$expected/"* ]] || reasons+=("右の最前面が $expected でない: ${actual:-枠なし}")
    fi
}
judge() {
    local name=$1 right
    parse_slots "$active/activities.txt" > "$active/slots.txt"
    case "$name" in
        link_fromYlHome)
            expect_log '\[Link\] front=Home'
            browser_created || reasons+=('wm_create_activity に FullscreenBrowserActivity がない')
            [[ ! -s $active/slots.txt ]] || reasons+=('分割の枠が残っている')
            ;;
        set_strip_coldPanel)
            expect_log '\[SetRunner\] waitBoth ready'
            right=$(sed -n 's/.*openSplit left=[^ ]* right=\([^ ]*\).*/\1/p' "$active/logcat.txt" | tail -n 1)
            if [[ $right =~ ^[a-zA-Z0-9_.]+$ ]]; then
                right_is "$right"
                [[ $right != com.gt8d.launcher ]] || no_safety
            else reasons+=('openSplit の右パッケージを取得できない'); fi
            ;;
        *)
            expect_log '\[Link\] front=Fullscreen\(pkg=com\.openai\.chatgpt\)'
            expect_log '\[SetRunner\] waitBoth ready'
            no_safety
            right_is com.gt8d.launcher
            if browser_created; then reasons+=('wm_create_activity に FullscreenBrowserActivity がある'); fi
            ;;
    esac
}
prepare_and_run() {
    local name=$1
    case "$name" in
        link_cold_noTasks) reset cold || return 1 ;;
        link_warm_noTasks)
            reset || return 1
            adb shell am start -n com.gt8d.launcher/.link.FullscreenBrowserActivity --es url about:blank || return 1
            sleep 2
            adb shell input keyevent KEYCODE_HOME && remove_yl_tasks || return 1
            # 温める操作の生成イベントを判定対象から除く前に保存する。
            mkdir -p "$active/warmup" || return 1
            capture "$active/warmup" || return 1
            adb logcat -c && adb logcat -b events -c || return 1
            ;;
        link_homeTaskPresent)
            reset cold && home_activity && adb shell input keyevent KEYCODE_HOME || return 1 ;;
        link_panelTaskPresent)
            adb shell input keyevent KEYCODE_HOME || return 1
            adb logcat -c && adb logcat -b events -c || return 1 ;;
        link_fromYlHome) reset && home_activity || return 1 ;;
        set_strip_coldPanel) reset cold && home_activity && chatgpt || return 1 ;;
    esac
    case "$name" in
        link_fromYlHome) link com.google.android.gm && sleep 6 ;;
        set_strip_coldPanel) adb shell input swipe 20 540 500 540 300 && sleep 10 ;;
        *) chatgpt && link com.openai.chatgpt && sleep 10 ;;
    esac
}
run() {
    local name=$1 suffix=$2
    active="$out/$name$suffix"
    mkdir -p "$active" || exit 1
    reasons=()
    prepare_and_run "$name" > "$active/commands.txt" 2>&1 || reasons+=('準備または操作コマンドが失敗')
    capture "$active" || reasons+=('ログ・画面の保存に失敗')
    judge "$name"
    if ((${#reasons[@]})); then
        row "$name$suffix" FAIL "$(IFS=';'; echo "${reasons[*]}")"
        failed=1
    else
        case "$name" in
            link_fromYlHome) row "$name$suffix" PASS 'front=Home、ブラウザ生成あり、分割枠なし' ;;
            set_strip_coldPanel) row "$name$suffix" PASS 'waitBoth ready、openSplit の右パッケージと右最前面が一致（YLuncher なら PanelActivity、Safety なし）' ;;
            *) row "$name$suffix" PASS 'front=Fullscreen(pkg=com.openai.chatgpt)、waitBoth ready、Safety なし、右最前面 PanelActivity、ブラウザ生成なし' ;;
        esac
    fi
    active=''
}
switch_home() {
    local actual
    adb shell cmd role add-role-holder android.app.role.HOME "$1" 0 || return 1
    actual=$(adb shell cmd role get-role-holders android.app.role.HOME) || return 1
    actual=${actual//$'\r'/}
    [[ $actual == "$1" ]]
}
switch_home com.android.launcher3 || { row setup FAIL '純正ホームへの切り替えに失敗'; exit 1; }
# 初期ログも最初の reset が消す前に保存する。
mkdir -p "$out/initial" || exit 1
capture "$out/initial" || { row setup FAIL '開始時の証跡保存に失敗'; exit 1; }
for name in "${names[@]}"; do
    [[ -z $only || $only == "$name" ]] || continue
    if [[ $name == link_panelTaskPresent && -n $only ]]; then run link_cold_noTasks '@prerequisite'; fi
    run "$name" ''
done
if ((with_home)); then
    switch_home com.gt8d.launcher || { row setup FAIL 'YLuncher ホームへの切り替えに失敗'; exit 1; }
    for name in link_cold_noTasks link_homeTaskPresent link_fromYlHome set_strip_coldPanel; do
        [[ -z $only || $only == "$name" ]] || continue
        run "$name" '@ylhome'
    done
fi
exit "$failed"
