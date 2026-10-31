#!/usr/bin/env bash
# 実機での実行は司令塔が行う。ADB は空白区切りのコマンドとして指定する。
set -uo pipefail
read -r -a adb_cmd <<< "${ADB:-adb.exe -s 192.168.137.248:5555}"
adb() { "${adb_cmd[@]}" "$@"; }
with_home=0
only=''
browser=com.android.chrome
names=(link_cold_noTasks link_browserTaskPresent link_warm_noTasks link_homeTaskPresent link_fromYlHome set_strip_coldPanel set_panelCard_switch link_appPanelFront)
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
    adb logcat -d -v time > "$dir/logcat-full.txt" || rc=1
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
    if [[ ${1:-} == cold ]]; then
        adb shell am force-stop com.gt8d.launcher || return 1
        adb shell am force-stop com.gt8d.launcher.panel || return 1
        adb shell am force-stop "$browser" || return 1
    fi
    sleep 1
    adb logcat -c && adb logcat -b events -c
}
home_activity() {
    local current
    current=$(adb shell cmd role get-role-holders android.app.role.HOME) || return 1
    current=${current//$'\r'/}
    if [[ $current == com.gt8d.launcher ]]; then
        adb shell input keyevent KEYCODE_HOME || return 1
    else
        adb shell am start -n com.gt8d.launcher/.ui.home.HomeActivity || return 1
    fi
    sleep 3
}
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
        if(/\* Task\{/ && /type=undefined/ && /mode=multi-window/ && / visible=true([[:space:]}]|$)/) {
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
restore_panel_apps() {
    # 途中失敗でも現在の番号に依存しない、既存のセット1起動手順を使う。
    prepare_and_run set_strip_coldPanel || return 1
    adb logcat -d -v time GT8D:D '*:S' > "$active/restore-logcat.txt" || return 1
    grep -Eq '\[SetRunner\] open index=1([^0-9]|$)' "$active/restore-logcat.txt" || return 1
    grep -Fq '[SetRunner] waitBoth ready' "$active/restore-logcat.txt" || return 1
    adb shell dumpsys activity activities > "$active/restore-activities.txt" || return 1
    parse_slots "$active/restore-activities.txt" > "$active/restore-slots.txt"
    grep -Eq '^LEFT com\.google\.android\.youtube/' "$active/restore-slots.txt" || return 1
    grep -Eq '^RIGHT com\.gt8d\.launcher/(\.ui\.panel\.PanelActivity|com\.gt8d\.launcher\.ui\.panel\.PanelActivity)$' "$active/restore-slots.txt" || return 1
    adb shell am broadcast -n com.gt8d.launcher/.debug.DebugPanelReceiver -a com.gt8d.launcher.debug.PANEL --es view apps || return 1
    sleep 3
    adb logcat -d -v time GT8D:D '*:S' > "$active/restore-logcat.txt" || return 1
    grep -Fq '[DebugPanel] view=Apps saved' "$active/restore-logcat.txt"
}
expect_log() { if ! grep -Eq "$1" "$active/logcat.txt"; then reasons+=("ログ不足: $1"); fi; }
no_safety() { if grep -Fq 'reason=Safety' "$active/logcat.txt"; then reasons+=('reason=Safety が発生'); fi; }
left_is() {
    local expected=$1 actual
    actual=$(awk '$1=="LEFT" {print $2}' "$active/slots.txt")
    [[ $actual == "$expected/"* ]] || reasons+=("左の最前面が $expected でない: ${actual:-枠なし}")
}
view_pattern() {
    printf 'START u0 \\{[^}]*act=android\\.intent\\.action\\.VIEW[^}]*pkg=%s([[:space:]}]|$)' "${browser//./\\.}"
}
view_sent() {
    grep -Eq "$(view_pattern)" "$active/logcat-full.txt" || reasons+=('ブラウザへの VIEW がない')
}
view_after_ready() {
    local ready views
    ready=$(awk '/\[SetRunner\] waitBoth ready/ {print NR; exit}' "$active/logcat-full.txt")
    views=$(grep -nE "$(view_pattern)" "$active/logcat-full.txt" || :)
    if ! awk -F: -v ready="${ready:-0}" '
        NF>1 {if (!ready || $1<=ready) early=1}
        END {exit early ? 1 : 0}
    ' <<< "$views"; then
        reasons+=('VIEW が分割の成立より前')
    fi
}
split_shown() {
    if ! grep -q '^LEFT ' "$active/slots.txt" || ! grep -q '^RIGHT ' "$active/slots.txt"; then
        reasons+=('分割の枠が2つない')
    fi
}
right_is() {
    local expected=$1 actual
    actual=$(awk '$1=="RIGHT" {print $2}' "$active/slots.txt")
    if [[ $expected == com.gt8d.launcher || $expected == com.gt8d.launcher.panel ]]; then
        [[ $actual == com.gt8d.launcher/.ui.panel.PanelActivity || $actual == com.gt8d.launcher/com.gt8d.launcher.ui.panel.PanelActivity ]] || reasons+=("右の最前面が PanelActivity でない: ${actual:-枠なし}")
    else
        [[ $actual == "$expected/"* ]] || reasons+=("右の最前面が $expected でない: ${actual:-枠なし}")
    fi
}
home_task_ids() {
    awk '/\* Task\{/ && / A=[0-9]+:com\.gt8d\.launcher / {
        for (i=1;i<=NF;i++) if ($i ~ /^#[0-9]+$/) {sub(/^#/, "", $i); print $i}
    }' "$1"
}
judge() {
    local name=$1 right left id top
    parse_slots "$active/activities.txt" > "$active/slots.txt"
    case "$name" in
        set_panelCard_switch)
            expect_log '\[DebugPanel\] view=Sets saved'
            expect_log '\[DebugPanel\] open index=2'
            expect_log '\[SetRunner\] open index=2([^0-9]|$)'
            expect_log '\[SetRunner\] waitBoth ready'
            no_safety
            split_shown
            left_is com.google.android.youtube
            right_is com.apple.android.music
            left=$(sed -n 's/.*openSplit left=\([^ ]*\) right=.*/\1/p' "$active/logcat.txt" | tail -n 1)
            right=$(sed -n 's/.*openSplit left=[^ ]* right=\([^ ]*\).*/\1/p' "$active/logcat.txt" | tail -n 1)
            [[ $left == com.google.android.youtube ]] || reasons+=("openSplit の左が YouTube でない: ${left:-取得できない}")
            [[ $right == com.apple.android.music ]] || reasons+=("openSplit の右が Apple Music でない: ${right:-取得できない}")
            ;;
        link_appPanelFront)
            expect_log '\[Link\] front=AppPanel\(left=com\.google\.android\.youtube\)'
            expect_log 'route=SplitWithBrowser\(left=com\.google\.android\.youtube\)'
            expect_log '\[SetRunner\] waitBoth ready'
            no_safety
            split_shown
            left_is com.google.android.youtube
            right_is "$browser"
            view_sent
            view_after_ready
            home_task_ids "$active/activities.txt" > "$active/home-tasks-after.txt"
            while IFS= read -r id; do
                if ! grep -Fxq "$id" "$active/home-tasks-before.txt"; then
                    reasons+=("com.gt8d.launcher affinity のタスクが増えた: $id")
                fi
            done < "$active/home-tasks-after.txt"
            ;;
        link_fromYlHome)
            expect_log '\[Link\] front=Home'
            expect_log 'route=FullscreenBrowser'
            view_sent
            [[ ! -s $active/slots.txt ]] || reasons+=('分割の枠が残っている')
            top=$(awk '/topResumedActivity=ActivityRecord\{/ {
                for(i=1;i<=NF;i++) if($i ~ /^[A-Za-z0-9_.]+\/[A-Za-z0-9_.$]+$/) {print $i; break}
                exit
            }' "$active/activities.txt")
            [[ $top == "$browser/"* ]] || reasons+=("最前面が $browser でない: ${top:-取得できない}")
            ;;
        set_strip_coldPanel)
            expect_log '\[SetRunner\] next base=0 -> 1'
            expect_log '\[SetRunner\] waitBoth ready'
            left=$(sed -n 's/.*openSplit left=\([^ ]*\) right=.*/\1/p' "$active/logcat.txt" | tail -n 1)
            [[ $left == com.google.android.youtube ]] || reasons+=("openSplit の左が com.google.android.youtube でない: ${left:-取得できない}")
            right=$(sed -n 's/.*openSplit left=[^ ]* right=\([^ ]*\).*/\1/p' "$active/logcat.txt" | tail -n 1)
            [[ $right == com.gt8d.launcher.panel ]] || reasons+=("openSplit の右が com.gt8d.launcher.panel でない: ${right:-取得できない}")
            if [[ $right =~ ^[a-zA-Z0-9_.]+$ ]]; then
                right_is "$right"
                [[ $right != com.gt8d.launcher && $right != com.gt8d.launcher.panel ]] || no_safety
            else reasons+=('openSplit の右パッケージを取得できない'); fi
            ;;
        *)
            if [[ $name == link_browserTaskPresent ]]; then
                expect_log '\[Link\] front=(Fullscreen\(pkg=com\.openai\.chatgpt\)|AppApp\(left=com\.openai\.chatgpt, )'
            else
                expect_log '\[Link\] front=Fullscreen\(pkg=com\.openai\.chatgpt\)'
            fi
            expect_log 'route=SplitWithBrowser\(left=com\.openai\.chatgpt\)'
            expect_log '\[SetRunner\] waitBoth ready'
            no_safety
            split_shown
            left_is com.openai.chatgpt
            right_is "$browser"
            view_sent
            view_after_ready
            ;;
    esac
}
prepare_and_run() {
    local name=$1
    case "$name" in
        link_cold_noTasks) reset cold || return 1 ;;
        set_panelCard_switch) adb logcat -c && adb logcat -b events -c || return 1 ;;
        link_appPanelFront)
            adb shell dumpsys activity activities > "$active/activities-before.txt" || return 1
            home_task_ids "$active/activities-before.txt" > "$active/home-tasks-before.txt"
            adb logcat -b events -d -v time > "$active/events-before.txt" || return 1
            adb logcat -c || return 1
            ;;
        link_warm_noTasks)
            reset || return 1
            adb shell am start -a android.intent.action.VIEW -d https://example.com/ -p "$browser" || return 1
            sleep 2
            adb shell input keyevent KEYCODE_HOME && remove_yl_tasks || return 1
            # 温める操作の生成イベントを判定対象から除く前に保存する。
            mkdir -p "$active/warmup" || return 1
            capture "$active/warmup" || return 1
            adb logcat -c && adb logcat -b events -c || return 1
            ;;
        link_homeTaskPresent)
            reset cold && home_activity && adb shell input keyevent KEYCODE_HOME || return 1 ;;
        link_browserTaskPresent)
            adb shell input keyevent KEYCODE_HOME || return 1
            adb logcat -c && adb logcat -b events -c || return 1 ;;
        link_fromYlHome) reset && home_activity || return 1 ;;
        set_strip_coldPanel)
            reset cold || return 1
            adb shell "run-as com.gt8d.launcher sh -c 'mkdir -p /data/data/com.gt8d.launcher/files && echo 0 > /data/data/com.gt8d.launcher/files/last_set_index.txt'" || return 1
            adb shell am force-stop com.gt8d.launcher || return 1
            adb shell am force-stop com.gt8d.launcher.panel || return 1
            home_activity && chatgpt || return 1
            ;;
    esac
    case "$name" in
        set_panelCard_switch)
            adb shell am broadcast -n com.gt8d.launcher/.debug.DebugPanelReceiver -a com.gt8d.launcher.debug.PANEL --es view sets || return 1
            sleep 3
            adb exec-out screencap -p > "$active/sets-view.png" || return 1
            [[ -s $active/sets-view.png ]] || return 1
            adb shell am broadcast -n com.gt8d.launcher/.debug.DebugPanelReceiver -a com.gt8d.launcher.debug.PANEL --ei open 2 || return 1
            sleep 10
            ;;
        link_fromYlHome) link com.google.android.gm && sleep 6 ;;
        link_appPanelFront) link com.google.android.youtube && sleep 10 ;;
        set_strip_coldPanel) adb shell input swipe 20 540 500 540 300 && sleep 10 ;;
        *) chatgpt && link com.openai.chatgpt && sleep 10 ;;
    esac
}
last_name=''
last_suffix=''
last_pass=0
run() {
    local name=$1 suffix=$2 version=${3-$2} prerequisite=''
    case "$name" in
        link_browserTaskPresent) prerequisite=link_cold_noTasks ;;
        set_panelCard_switch) prerequisite=set_strip_coldPanel ;;
        link_appPanelFront) prerequisite=set_panelCard_switch ;;
    esac
    if [[ -n $prerequisite ]] && [[ $last_name != "$prerequisite" || $last_suffix != "$version" || $last_pass != 1 ]]; then
        row "$name$suffix" SKIP "前提の $prerequisite が不合格"
        failed=1
        last_name=$name; last_suffix=$version; last_pass=0
        return
    fi
    active="$out/$name$suffix"
    mkdir -p "$active" || exit 1
    reasons=()
    prepare_and_run "$name" > "$active/commands.txt" 2>&1 || reasons+=('準備または操作コマンドが失敗')
    capture "$active" || reasons+=('ログ・画面の保存に失敗')
    judge "$name"
    last_name=$name; last_suffix=$version; last_pass=0
    if ((${#reasons[@]})); then
        row "$name$suffix" FAIL "$(IFS=';'; echo "${reasons[*]}")"
        failed=1
    else
        last_pass=1
        case "$name" in
            set_panelCard_switch) row "$name$suffix" PASS 'open index=2、waitBoth ready、Safety なし、分割枠2つ、左 YouTube、右 Apple Music、openSplit の左右も一致' ;;
            link_appPanelFront) row "$name$suffix" PASS 'front=AppPanel(left=com.google.android.youtube)、route=SplitWithBrowser(left=com.google.android.youtube)、waitBoth ready、Safety なし、分割枠2つ、左 YouTube、右 Chrome、VIEW は分割成立後、ホーム affinity のタスク増加なし' ;;
            link_fromYlHome) row "$name$suffix" PASS 'front=Home、route=FullscreenBrowser、ブラウザへの VIEW あり、分割枠なし、最前面 Chrome' ;;
            set_strip_coldPanel) row "$name$suffix" PASS 'next base=0 -> 1、waitBoth ready、openSplit の左 YouTube、右 com.gt8d.launcher.panel、右最前面 PanelActivity、Safety なし' ;;
            link_browserTaskPresent) row "$name$suffix" PASS 'front=Fullscreen(pkg=com.openai.chatgpt) または AppApp(left=com.openai.chatgpt, …)、route=SplitWithBrowser(left=com.openai.chatgpt)、waitBoth ready、Safety なし、分割枠2つ、左 ChatGPT、右 Chrome、VIEW は分割成立後' ;;
            *) row "$name$suffix" PASS 'front=Fullscreen(pkg=com.openai.chatgpt)、route=SplitWithBrowser(left=com.openai.chatgpt)、waitBoth ready、Safety なし、分割枠2つ、左 ChatGPT、右 Chrome、VIEW は分割成立後' ;;
        esac
    fi
    if [[ $name == set_panelCard_switch ]]; then
        if ! restore_panel_apps > "$active/restore-commands.txt" 2>&1; then
            row "$name$suffix" WARN 'セット1とパネルの「アプリ」表示を復元できない。次の場面の前提を無効化'
            last_pass=0
        fi
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
    if [[ -n $only ]]; then
        case "$name" in
            link_browserTaskPresent) run link_cold_noTasks '@prerequisite' '' ;;
            set_panelCard_switch) run set_strip_coldPanel '@prerequisite' '' ;;
            link_appPanelFront)
                run set_strip_coldPanel '@prerequisite' ''
                run set_panelCard_switch '@prerequisite' ''
                ;;
        esac
    fi
    run "$name" ''
done
if ((with_home)); then
    switch_home com.gt8d.launcher || { row setup FAIL 'YLuncher ホームへの切り替えに失敗'; exit 1; }
    for name in link_cold_noTasks link_homeTaskPresent link_fromYlHome set_strip_coldPanel set_panelCard_switch link_appPanelFront; do
        [[ -z $only || $only == "$name" ]] || continue
        if [[ -n $only ]]; then
            case "$name" in
                set_panelCard_switch) run set_strip_coldPanel '@prerequisite@ylhome' '@ylhome' ;;
                link_appPanelFront)
                    run set_strip_coldPanel '@prerequisite@ylhome' '@ylhome'
                    run set_panelCard_switch '@prerequisite@ylhome' '@ylhome'
                    ;;
            esac
        fi
        run "$name" '@ylhome'
    done
fi
exit "$failed"
