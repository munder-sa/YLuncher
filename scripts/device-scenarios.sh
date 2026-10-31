#!/usr/bin/env bash
# 実機での実行は司令塔が行う。ADB は空白区切りのコマンドとして指定する。
set -uo pipefail
read -r -a adb_cmd <<< "${ADB:-adb.exe -s 192.168.137.248:5555}"
adb() { "${adb_cmd[@]}" "$@"; }
with_home=0
only=''
browser=com.android.chrome
names=(link_cold_noTasks link_browserTaskPresent link_warm_noTasks link_homeTaskPresent link_fromYlHome set_strip_coldPanel set_panelCard_switch link_appPanelFront)
left_names=(set_leftPanel_cold set_leftPanel_warm leftPanel_launchApp leftPanel_dividerSwap leftPanel_link leftPanel_link_twice set_leftToRightPanel)
names+=("${left_names[@]}")
names+=(set_cancel_cold set_cancel_thenOpen)
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
config_saved=0
left_index=''
original_home=''
original_browser=''
logcat_pid=''
full_logcat_pid=''
stream_dir=''
stop_streams() {
    local pid
    for pid in "$logcat_pid" "$full_logcat_pid"; do
        if [[ -n $pid ]]; then
            kill "$pid" 2>/dev/null || :
            wait "$pid" 2>/dev/null || :
        fi
    done
    logcat_pid=''
    full_logcat_pid=''
}
start_streams() {
    stop_streams
    stream_dir=$active
    "${adb_cmd[@]}" logcat -v time GT8D:D '*:S' > "$active/logcat-stream.txt" &
    logcat_pid=$!
    "${adb_cmd[@]}" logcat -v time > "$active/logcat-full-stream.txt" &
    full_logcat_pid=$!
}
clear_logs() {
    stop_streams
    adb logcat -c || return 1
    start_streams
    adb logcat -b events -c
}
row() {
    local reason=${3//$'\n'/; }
    reason=${reason//|/／}
    printf '| %s | %s | %s |\n' "$1" "$2" "$reason" >> "$summary"
}
capture() {
    local dir=$1 rc=0
    stop_streams
    if [[ -n $stream_dir && $dir != "$stream_dir" ]]; then
        cat "$stream_dir/logcat-stream.txt" > "$dir/logcat-stream.txt" || rc=1
        cat "$stream_dir/logcat-full-stream.txt" > "$dir/logcat-full-stream.txt" || rc=1
    fi
    adb logcat -d -v time GT8D:D '*:S' > "$dir/logcat-dump.txt" || rc=1
    if [[ -n $stream_dir && -s $stream_dir/logcat-stream.txt ]]; then
        cat "$stream_dir/logcat-stream.txt" > "$dir/logcat.txt" || rc=1
    else
        cat "$dir/logcat-dump.txt" > "$dir/logcat.txt" || rc=1
    fi
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
    stop_streams
    if [[ -n $active ]]; then
        capture "$active" || failed=1
        row "${active##*/}" FAIL '途中終了。終了時点のログと画面を保存'
        failed=1
    fi
    if ((config_saved)); then
        if adb shell "run-as com.gt8d.launcher sh -c 'cat files/config-scenario-backup.json > files/config.json'" &&
            adb shell am force-stop com.gt8d.launcher &&
            adb shell run-as com.gt8d.launcher rm files/config-scenario-backup.json; then
            config_saved=0
        else
            row cleanup FAIL "設定の復元に失敗(退避: files/config-scenario-backup.json、$out/config-backup.json)"
            failed=1
        fi
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
adb logcat -G 16M || :
adb shell am force-stop com.gt8d.launcher || exit 1
if ! adb shell "run-as com.gt8d.launcher sh -c 'if [ -f files/config-scenario-backup.json ]; then cat files/config-scenario-backup.json > files/config.json && rm files/config-scenario-backup.json; fi'"; then
    row setup FAIL '前回の設定の復元に失敗(退避: files/config-scenario-backup.json)'; exit 1
fi
adb exec-out run-as com.gt8d.launcher cat files/config.json > "$out/config-backup.json" || { row setup FAIL '設定の退避に失敗'; exit 1; }
adb shell "run-as com.gt8d.launcher sh -c 'cat files/config.json > files/config-scenario-backup.json'" || { row setup FAIL '端末内の設定の退避に失敗'; exit 1; }
config_saved=1
# リンクの行き先の期待値は、端末の設定のブラウザ(link.browserPkg)に合わせる。無ければ Chrome。
configured_browser=$(sed -n 's/.*"browserPkg":"\([a-zA-Z0-9_.]*\)".*/\1/p' "$out/config-backup.json" | head -n 1)
[[ -n $configured_browser ]] && browser=$configured_browser
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
        sleep 3
    fi
    sleep 1
    clear_logs
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
    function emit( line) {
        if (!slot) return
        line=side " " (top!="" ? top : hist!="" ? hist : "EMPTY")
        if (line!="UNKNOWN EMPTY" && !seen[line]++) print line
    }
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
    local attempt
    adb shell am broadcast -n com.gt8d.launcher/.debug.DebugPanelReceiver -a com.gt8d.launcher.debug.PANEL --es view apps || return 1
    sleep 3
    adb logcat -d -v time GT8D:D '*:S' > "$active/restore-logcat.txt" || return 1
    grep -Fq '[DebugPanel] view=Apps saved' "$active/restore-logcat.txt" || return 1
    panel --ei open 1 || return 1
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
        sleep 1
        adb logcat -d -v time GT8D:D '*:S' > "$active/restore-open-logcat.txt" || return 1
        if grep -Fq '[SetRunner] waitBoth ready left=com.google.android.youtube right=com.gt8d.launcher.panel' "$active/restore-open-logcat.txt"; then return 0; fi
    done
    return 1
}
expect_log() { if ! grep -Eq "$1" "$active/logcat.txt"; then reasons+=("ログ不足: $1"); fi; }
no_safety() { if grep -Fq 'reason=Safety' "$active/logcat.txt"; then reasons+=('reason=Safety が発生'); fi; }
left_is() {
    local expected=$1 actual
    actual=$(awk '$1=="LEFT" {print $2}' "$active/slots.txt")
    if [[ $expected == com.gt8d.launcher.panel ]]; then
        [[ $actual == com.gt8d.launcher/.ui.panel.PanelActivity || $actual == com.gt8d.launcher/com.gt8d.launcher.ui.panel.PanelActivity ]] || reasons+=("左の最前面が PanelActivity でない: ${actual:-枠なし}")
    else
        [[ $actual == "$expected/"* ]] || reasons+=("左の最前面が $expected でない: ${actual:-枠なし}")
    fi
}
view_pattern() {
    printf 'START u0 \\{[^}]*act=android\\.intent\\.action\\.VIEW[^}]*pkg=%s([[:space:]}]|$)' "${browser//./\\.}"
}
view_sent() {
    local full=$active/logcat-full-stream.txt
    [[ -s $full ]] || full=$active/logcat-full.txt
    grep -Eq "$(view_pattern)" "$full" || reasons+=('ブラウザへの VIEW がない')
}
view_after_ready() {
    local ready views full=$active/logcat-full-stream.txt
    [[ -s $full ]] || full=$active/logcat-full.txt
    ready=$(awk '/\[SetRunner\] waitBoth ready/ {print NR; exit}' "$full")
    views=$(grep -nE "$(view_pattern)" "$full" || :)
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
panel() { adb shell am broadcast -n com.gt8d.launcher/.debug.DebugPanelReceiver -a com.gt8d.launcher.debug.PANEL "$@"; }
open_left() { panel --ei open "$left_index" && sleep 10; }
check_swap_config() {
    local side=$1 file=$2
    # JSON の文字列と括弧の深さを追い、screenSets の各オブジェクトだけを取り出す。
    awk -v side="$side" '
    {text=text $0}
    END {
        for(i=1;i<=length(text);i++) {
            c=substr(text,i,1)
            if(depth>=2) obj=obj c
            if(quoted) {if(escaped) escaped=0; else if(c=="\\") escaped=1; else if(c=="\"") quoted=0; continue}
            if(c=="\"") {quoted=1; continue}
            if(c=="{") {depth++; if(depth==2) obj="{"}
            if(c=="}") {
                if(depth==2 && obj ~ /"id"[[:space:]]*:[[:space:]]*"scenario-left"/) {
                    gsub(/[[:space:]]/,"",obj)
                    panel="\\{\"type\":\"com.gt8d.launcher.config.Slot.Panel\"\\}"
                    app="\\{\"type\":\"com.gt8d.launcher.config.Slot.App\",\"pkg\":\"com.google.android.youtube\"\\}"
                    left=(side=="Right" ? app : panel); right=(side=="Right" ? panel : app)
                    ratio=(side=="Right" ? "R6_4" : "R4_6")
                    if(obj ~ ("\"left\":" left) && obj ~ ("\"right\":" right) && obj ~ ("\"ratio\":\"" ratio "\"")) ok=1
                }
                depth--
            }
        }
        exit !ok
    }' "$file"
}
double_tap() {
    local label=$1 attempt coords x y start_line
    for attempt in 1 2; do
        adb shell dumpsys activity activities > "$active/$label-bounds-$attempt.txt" || return 1
        coords=$(awk '
        function indent(s) {match(s,/[^ ]/); return RSTART-1}
        {
            depth=indent($0)
            if(slot && depth<=slotdepth && /[^[:space:]]/) slot=0
            if(/\* Task\{/ && /type=undefined/ && /mode=multi-window/ && / visible=true([[:space:]}]|$)/) {slot=1; slotdepth=depth; next}
            if(slot && /mBounds=Rect\(/) {
                s=$0; sub(/^.*mBounds=Rect\(/,"",s); gsub(/[^0-9]+/," ",s); split(s,b,/ +/)
                if(b[1]+0==0) {lr=b[3]; top=b[2]; bottom=b[4]; l=1}
                else {rl=b[1]; r=1}
                slot=0
            }
        }
        END {if(l && r) print int((lr+rl)/2), int((top+bottom)/2)}' "$active/$label-bounds-$attempt.txt")
        read -r x y <<< "$coords"
        [[ $x =~ ^[0-9]+$ && $y =~ ^[0-9]+$ ]] || return 1
        start_line=$(awk 'END {print NR}' "$active/logcat-full-stream.txt")
        adb shell "input tap $x $y; input tap $x $y" || return 1
        sleep 3
        adb logcat -d -v time > "$active/$label-full-$attempt.txt" || return 1
        if awk -v start="$start_line" 'NR>start && /onDoubleTappedDivider/ {found=1} END {exit !found}' "$active/logcat-full-stream.txt"; then return 0; fi
    done
    reasons+=('ダブルタップが効かない')
    return 1
}
swap_round() {
    local side=$1 label=$2
    double_tap "$label" || return 1
    panel --es status 1 || return 1
    adb logcat -d -v time GT8D:D '*:S' > "$active/$label-logcat.txt" || return 1
    if [[ -s $active/logcat-stream.txt ]]; then
        cat "$active/logcat-stream.txt" > "$active/$label-logcat.txt" || return 1
    fi
    grep -Fq "[SetRunner] swapSaved id=scenario-left panel=$side" "$active/$label-logcat.txt" || reasons+=("swapSaved panel=$side がない")
    grep -Fq "[DebugPanel] status placement=$side " "$active/$label-logcat.txt" || reasons+=("placement=$side がない")
    adb exec-out run-as com.gt8d.launcher cat files/config.json > "$active/$label-config.json" || return 1
    check_swap_config "$side" "$active/$label-config.json" || reasons+=("入れ替え後の設定が不一致: panel=$side")
}
judge() {
    local name=$1 right left id top cancelled reopened ready
    parse_slots "$active/activities.txt" > "$active/slots.txt"
    case "$name" in
        set_cancel_cold|set_cancel_thenOpen)
            cancel_guard_count=$(awk '/\[SetRunner\] cancel guard home/ {count++} END {print count+0}' "$active/logcat.txt")
            cancelled=$(awk '/\[SetRunner\] opening cancelled/ {print NR; exit}' "$active/logcat.txt")
            if [[ -z $cancelled ]]; then
                reasons+=('opening cancelled がない')
            elif [[ $name == set_cancel_cold ]]; then
                if awk -v cancelled="$cancelled" 'NR>cancelled && /\[SetRunner\] waitBoth ready/ {found=1} END {exit !found}' "$active/logcat.txt"; then
                    reasons+=('取り消し後に waitBoth ready がある')
                fi
                if awk -v cancelled="$cancelled" 'NR>cancelled && /\[Notice\] show/ {found=1} END {exit !found}' "$active/logcat.txt"; then
                    reasons+=('取り消し後に通知がある')
                fi
            else
                reopened=$(awk -v cancelled="$cancelled" 'NR>cancelled && /\[DebugPanel\] open index=1([^0-9]|$)/ {print NR; exit}' "$active/logcat.txt")
                if [[ -z $reopened ]]; then
                    reasons+=('取り消し後に open index=1 がない')
                else
                    ready=$(awk -v reopened="$reopened" 'NR>reopened && /\[SetRunner\] waitBoth ready/ {print NR; exit}' "$active/logcat.txt")
                    if [[ -z $ready ]]; then
                        reasons+=('次の open index=1 後に waitBoth ready がない')
                    elif ! ((cancelled < reopened && reopened < ready)); then
                        reasons+=('取り消し・次の open・ready の順序が不一致')
                    fi
                fi
            fi
            no_safety
            if [[ $name == set_cancel_cold ]]; then
                [[ ! -s $active/slots.txt ]] || reasons+=('分割の枠が残っている')
                top=$(awk '/topResumedActivity=ActivityRecord\{/ {
                    for(i=1;i<=NF;i++) if($i ~ /^[A-Za-z0-9_.]+\/[A-Za-z0-9_.$]+$/) {print $i; break}
                    exit
                }' "$active/activities.txt")
                [[ $top == com.gt8d.launcher/.ui.home.HomeActivity || $top == com.gt8d.launcher/com.gt8d.launcher.ui.home.HomeActivity ]] || reasons+=("最前面が HomeActivity でない: ${top:-取得できない}")
            else
                split_shown; left_is com.google.android.youtube; right_is com.gt8d.launcher.panel
            fi
            ;;
        set_leftPanel_cold|set_leftPanel_warm|set_leftToRightPanel)
            expect_log '\[SetRunner\] waitBoth ready'
            no_safety; split_shown
            if [[ $name == set_leftToRightPanel ]]; then
                expect_log 'openSplit left=com\.google\.android\.youtube right=com\.gt8d\.launcher\.panel([[:space:]]|$)'
                expect_log '\[DebugPanel\] status placement=Right '
                left_is com.google.android.youtube; right_is com.gt8d.launcher.panel
            else
                expect_log 'openSplit left=com\.gt8d\.launcher\.panel right=com\.google\.android\.youtube([[:space:]]|$)'
                expect_log '\[DebugPanel\] status placement=Left '
                left_is com.gt8d.launcher.panel; right_is com.google.android.youtube
            fi
            ;;
        leftPanel_launchApp)
            split_shown; left_is com.gt8d.launcher.panel; right_is com.google.android.apps.maps
            expect_log '\[SetRunner\] panelLaunched pkg=com\.google\.android\.apps\.maps([[:space:]]|$)'
            ;;
        leftPanel_dividerSwap) ;; # 往復それぞれのログと設定は操作直後に検証する。
        leftPanel_link|leftPanel_link_twice)
            if [[ $name == leftPanel_link ]]; then
                expect_log '\[Link\] front=AppPanel\(app=com\.google\.android\.youtube, panelSide=Left\)'
            else
                expect_log '\[Link\] front=AppBrowser\(app=com\.google\.android\.youtube, browserSide=Left\)'
            fi
            expect_log 'route=SplitWithBrowser\(app=com\.google\.android\.youtube, browserSide=Left\)'
            expect_log '\[SetRunner\] waitBoth ready'
            no_safety; split_shown; left_is "$browser"; right_is com.google.android.youtube
            view_sent; view_after_ready
            ;;
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
            expect_log '\[Link\] front=AppPanel\(app=com\.google\.android\.youtube, panelSide=Right\)'
            expect_log 'route=SplitWithBrowser\(app=com\.google\.android\.youtube, browserSide=Right\)'
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
            expect_log 'route=SplitWithBrowser\(app=com\.openai\.chatgpt, browserSide=Right\)'
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
    local name=$1 attempt
    case "$name" in
        set_cancel_cold|set_cancel_thenOpen)
            reset cold || return 1
            adb shell am force-stop com.google.android.youtube || return 1
            ;;
        set_leftPanel_cold)
            clear_logs || return 1
            panel --es addLeftPanelSet com.google.android.youtube || return 1
            left_index=''
            for attempt in 1 2 3 4 5; do
                adb logcat -d -v time GT8D:D '*:S' > "$active/add-set-logcat.txt" || return 1
                if [[ -s $active/logcat-stream.txt ]]; then
                    cat "$active/logcat-stream.txt" > "$active/add-set-logcat.txt" || return 1
                fi
                left_index=$(sed -n 's/.*\[DebugPanel\] addLeftPanelSet index=\([0-9]*\).*/\1/p' "$active/add-set-logcat.txt" | tail -n 1)
                [[ $left_index =~ ^[1-9][0-9]*$ ]] && break
                sleep 1
            done
            [[ $left_index =~ ^[1-9][0-9]*$ ]] || return 1
            reset cold || return 1
            ;;
        set_leftPanel_warm)
            adb shell input keyevent KEYCODE_HOME || return 1
            clear_logs || return 1
            ;;
        leftPanel_launchApp|leftPanel_dividerSwap|leftPanel_link|leftPanel_link_twice|set_leftToRightPanel)
            clear_logs || return 1
            ;;
        link_cold_noTasks) reset cold || return 1 ;;
        set_panelCard_switch) clear_logs || return 1 ;;
        link_appPanelFront)
            adb shell dumpsys activity activities > "$active/activities-before.txt" || return 1
            home_task_ids "$active/activities-before.txt" > "$active/home-tasks-before.txt"
            adb logcat -b events -d -v time > "$active/events-before.txt" || return 1
            clear_logs || return 1
            ;;
        link_warm_noTasks)
            reset || return 1
            adb shell am start -a android.intent.action.VIEW -d https://example.com/ -p "$browser" || return 1
            sleep 2
            adb shell input keyevent KEYCODE_HOME && remove_yl_tasks || return 1
            # 温める操作の生成イベントを判定対象から除く前に保存する。
            mkdir -p "$active/warmup" || return 1
            capture "$active/warmup" || return 1
            clear_logs || return 1
            ;;
        link_homeTaskPresent)
            reset cold && home_activity && adb shell input keyevent KEYCODE_HOME || return 1 ;;
        link_browserTaskPresent)
            adb shell input keyevent KEYCODE_HOME || return 1
            clear_logs || return 1 ;;
        link_fromYlHome) reset && home_activity || return 1 ;;
        set_strip_coldPanel)
            reset cold || return 1
            adb shell "run-as com.gt8d.launcher sh -c 'mkdir -p /data/data/com.gt8d.launcher/files && echo 0 > /data/data/com.gt8d.launcher/files/last_set_index.txt'" || return 1
            adb shell am force-stop com.gt8d.launcher || return 1
            adb shell am force-stop com.gt8d.launcher.panel || return 1
            sleep 3
            home_activity && chatgpt || return 1
            ;;
    esac
    case "$name" in
        set_cancel_cold|set_cancel_thenOpen)
            panel --ei open 1 || return 1
            sleep 1.2
            panel --es cancel 1 || return 1
            if [[ $name == set_cancel_cold ]]; then
                sleep 7
            else
                sleep 2
                panel --ei open 1 || return 1
                sleep 10
            fi
            ;;
        set_leftPanel_cold|set_leftPanel_warm) open_left || return 1 ;;
        leftPanel_launchApp) panel --es launch com.google.android.apps.maps && sleep 5 || return 1 ;;
        leftPanel_dividerSwap)
            swap_round Right swap-right || return 1
            swap_round Left swap-left || return 1
            ;;
        leftPanel_link|leftPanel_link_twice) link com.google.android.youtube && sleep 10 || return 1 ;;
        set_leftToRightPanel) panel --ei open 1 && sleep 10 || return 1 ;;
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
declare -A prerequisite_rebuilt=()
declare -A scenario_failed=()
declare -A scenario_runs=()
run() {
    local name=$1 suffix=$2 version=${3-$2} prerequisite='' key prerequisite_key
    local cancel_guard_count=0 cancel_guard_note=''
    key="$name$version"
    case "$name" in
        link_browserTaskPresent) prerequisite=link_cold_noTasks ;;
        set_panelCard_switch) prerequisite=set_strip_coldPanel ;;
        link_appPanelFront) prerequisite=set_panelCard_switch ;;
        set_leftPanel_warm) prerequisite=set_leftPanel_cold ;;
        leftPanel_launchApp|leftPanel_dividerSwap|leftPanel_link|set_leftToRightPanel) prerequisite=set_leftPanel_warm ;;
        leftPanel_link_twice) prerequisite=leftPanel_link ;;
    esac
    prerequisite_key="$prerequisite$version"
    # 左パネルの前提は状態を変更する場面の後にも開き直す。--only も同じ経路。
    if [[ $name == set_leftPanel_warm || $name == leftPanel_* || $name == set_leftToRightPanel ]]; then
        if [[ -n $prerequisite ]] && [[ $last_name != "$prerequisite" || $last_suffix != "$version" || $last_pass != 1 ]]; then
            if [[ ${scenario_failed[$prerequisite_key]:-0} != 1 || ${prerequisite_rebuilt[$prerequisite_key]:-0} != 1 ]]; then
                if [[ ${scenario_failed[$prerequisite_key]:-0} == 1 ]]; then prerequisite_rebuilt[$prerequisite_key]=1; fi
                run "$prerequisite" "@prerequisite$version" "$version"
            fi
        fi
    fi
    if [[ -n $prerequisite ]] && [[ $last_name != "$prerequisite" || $last_suffix != "$version" || $last_pass != 1 ]]; then
        row "$name$suffix" SKIP "前提の $prerequisite が不合格"
        failed=1
        scenario_failed[$key]=1
        last_name=$name; last_suffix=$version; last_pass=0
        return
    fi
    scenario_runs[$key]=$(( ${scenario_runs[$key]:-0} + 1 ))
    if [[ $suffix == @prerequisite* ]]; then
        suffix="@prerequisite$version@run${scenario_runs[$key]}"
    elif [[ ${scenario_runs[$key]} -gt 1 ]]; then suffix+="@run${scenario_runs[$key]}"; fi
    active="$out/$name$suffix"
    stream_dir=''
    mkdir -p "$active" || exit 1
    reasons=()
    prepare_and_run "$name" > "$active/commands.txt" 2>&1 || reasons+=('準備または操作コマンドが失敗')
    case "$name" in
        set_leftPanel_cold|set_leftPanel_warm|set_leftToRightPanel)
            panel --es status 1 >> "$active/commands.txt" 2>&1 || reasons+=('status の取得に失敗')
            ;;
    esac
    capture "$active" || reasons+=('ログ・画面の保存に失敗')
    judge "$name"
    case "$name" in
        set_cancel_cold|set_cancel_thenOpen) cancel_guard_note="、見張りでホームへ戻した回数=$cancel_guard_count" ;;
    esac
    last_name=$name; last_suffix=$version; last_pass=0
    if ((${#reasons[@]})); then
        row "$name$suffix" FAIL "$(IFS=';'; echo "${reasons[*]}")$cancel_guard_note"
        failed=1
    else
        last_pass=1
        case "$name" in
            set_cancel_cold) row "$name$suffix" PASS "opening cancelled、取り消し後の ready・通知なし、分割枠なし、最前面 HomeActivity、Safety なし$cancel_guard_note" ;;
            set_cancel_thenOpen) row "$name$suffix" PASS "opening cancelled → 次の open index=1 → waitBoth ready、分割枠2つ、左 YouTube、右 PanelActivity、Safety なし$cancel_guard_note" ;;
            set_leftPanel_cold|set_leftPanel_warm) row "$name$suffix" PASS 'openSplit 左 panel、右 YouTube、waitBoth ready、Safety なし、左最前面 PanelActivity、右最前面 YouTube、placement=Left' ;;
            leftPanel_launchApp) row "$name$suffix" PASS '左 PanelActivity、右最前面 Maps、panelLaunched pkg=com.google.android.apps.maps' ;;
            leftPanel_dividerSwap) row "$name$suffix" PASS '往復の onDoubleTappedDivider、swapSaved、placement、設定の左右と比率が一致' ;;
            leftPanel_link) row "$name$suffix" PASS 'front=AppPanel(app=com.google.android.youtube, panelSide=Left)、route=SplitWithBrowser(app=com.google.android.youtube, browserSide=Left)、左ブラウザ、右 YouTube、VIEW は分割成立後' ;;
            leftPanel_link_twice) row "$name$suffix" PASS 'front=AppBrowser(app=com.google.android.youtube, browserSide=Left)、左ブラウザ、右 YouTube、VIEW は分割成立後' ;;
            set_leftToRightPanel) row "$name$suffix" PASS 'openSplit 左 YouTube、右 panel、waitBoth ready、Safety なし、placement=Right、右最前面 PanelActivity' ;;
            set_panelCard_switch) row "$name$suffix" PASS 'open index=2、waitBoth ready、Safety なし、分割枠2つ、左 YouTube、右 Apple Music、openSplit の左右も一致' ;;
            link_appPanelFront) row "$name$suffix" PASS 'front=AppPanel(app=com.google.android.youtube, panelSide=Right)、route=SplitWithBrowser(app=com.google.android.youtube, browserSide=Right)、waitBoth ready、Safety なし、分割枠2つ、左 YouTube、右 Chrome、VIEW は分割成立後、ホーム affinity のタスク増加なし' ;;
            link_fromYlHome) row "$name$suffix" PASS 'front=Home、route=FullscreenBrowser、ブラウザへの VIEW あり、分割枠なし、最前面 Chrome' ;;
            set_strip_coldPanel) row "$name$suffix" PASS 'next base=0 -> 1、waitBoth ready、openSplit の左 YouTube、右 com.gt8d.launcher.panel、右最前面 PanelActivity、Safety なし' ;;
            link_browserTaskPresent) row "$name$suffix" PASS 'front=Fullscreen(pkg=com.openai.chatgpt) または AppApp(left=com.openai.chatgpt, …)、route=SplitWithBrowser(app=com.openai.chatgpt, browserSide=Right)、waitBoth ready、Safety なし、分割枠2つ、左 ChatGPT、右 Chrome、VIEW は分割成立後' ;;
            *) row "$name$suffix" PASS 'front=Fullscreen(pkg=com.openai.chatgpt)、route=SplitWithBrowser(app=com.openai.chatgpt, browserSide=Right)、waitBoth ready、Safety なし、分割枠2つ、左 ChatGPT、右 Chrome、VIEW は分割成立後' ;;
        esac
    fi
    if [[ $name == set_panelCard_switch ]]; then
        if ! restore_panel_apps > "$active/restore-commands.txt" 2>&1; then
            row "$name$suffix" WARN 'パネルの「アプリ」表示を復元できない。次の場面の前提を無効化'
            last_pass=0
        fi
    fi
    scenario_failed[$key]=$((1 - last_pass))
    active=''
    stream_dir=''
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
    for name in link_cold_noTasks link_homeTaskPresent link_fromYlHome set_strip_coldPanel set_panelCard_switch link_appPanelFront "${left_names[@]}" set_cancel_cold set_cancel_thenOpen; do
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
