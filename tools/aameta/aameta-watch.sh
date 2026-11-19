#!/system/bin/sh
# aameta-watch — MainAiBox の MediaInfo ログを読み、CQ5 の時点(sendCQCmd=true)で 1 組そろえて aameta send で車へ送る (Task 273)。
# MainAiBox は変わった項目だけ AT#CQ を送るが、ログの "reportMediaInfo duration=…" 行は毎回 4 項目そろっているので、それを保持する。
# 使い方(root): sh aameta-watch.sh   (止めるときは kill。何も書き換えない)
AAMETA=${AAMETA:-/data/local/tmp/aameta}
have=0 sent_conn=

send_now() {
    out=$("$AAMETA" send "$title" "$artist" "$album" "$duration" 2>&1)
    rc=$?
    log -t AAMeta "send rc=$rc why=$1 $out"
    [ $rc -eq 0 ] && sent_conn=$("$AAMETA" conn)
}

# -T 1: 起動前の古いログは使わない(前の曲が混ざらない)
logcat -v raw -s MediaInfo:D -T 1 | while IFS= read -r line; do
    case "$line" in
    "reportMediaInfo duration="*)
        # shortcut: 曲名などに「空白2つ+album=」等が入ると崩れる。実害が出たら MainAiBox 側の値を直接取る方式へ
        rest=${line#reportMediaInfo duration=}
        duration=${rest%%  title=*}; rest=${rest#*  title=}
        title=${rest%%  album=*};    rest=${rest#*  album=}
        album=${rest%%  artist=*};   artist=${rest#*  artist=}
        have=1 ;;
    "reportMediaInfo totalLen="*"sendCQCmd=true")
        [ $have -eq 1 ] && send_now cq5 ;;
    "reportMediaInfo totalLen="*"sendCQCmd=false")
        # つなぎ直し・初回・前回送れなかったときだけ送る(毎秒は送らない)
        if [ $have -eq 1 ] && conn=$("$AAMETA" conn) && [ "$conn" != "$sent_conn" ]; then send_now reconnect; fi ;;
    esac
done
