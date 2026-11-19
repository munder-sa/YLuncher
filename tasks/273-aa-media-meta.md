最初に tasks/RULES.md を読み、従う。

# Task 273: 有線 Android Auto で曲名・アーティスト・アルバムを車へ送る道具 aameta(ptrace、何も書き換えない)

## 背景(司令塔が実機と静的解析で確認済み、2026-11-19)
- GT8D の純正 `com.carsyso.main`(MainAiBox `xq_1.0.35`)は Apple Music のメタデータを取得し、`MPUCarPlayManager.reportMediaInfo(duration, title, album, artist)` で **変わった項目だけ** `AT#CQ1..4` をシリアルへ送る。しかし `sdAutoReverse` は `CQ5` で一時バッファを消すだけで車へ送らない。DHU の Media Playback Status に曲名が出ない原因はこれ。
- `reportMediaInfo` は、呼ばれるたび(再生中は約1秒ごと)、**変化の有無に関わらず4項目そろった1行**を logcat に出す(タグ `MediaInfo`)。空の項目は `unKnow` になる。実例:
  ```
  reportMediaInfo duration=284000  title=背を向けて寝る癖  album=tulip - EP  artist=Leina
  reportMediaInfo totalLen=42  sendCQCmd=true      ← 何か変わって CQ を送ったとき(= CQ5 の時点)
  reportMediaInfo totalLen=42  sendCQCmd=false     ← 変化なし(約1秒ごと)
  ```
  区切りはどちらも **空白2つ**。
- `libSdAutoReverse.so`(SHA-256 `f2a665a8…7f612fa`、`/system/lib/` で実機と一致)に車へ送る実処理がある:
  - `MediaPlaybackStatusEndpoint::sendMsgPlaybackMeta(this, const char* song, const char* artist, const char* album, const char* s4, const char* bytes, int bytes_len, int duration_ms, int rating)`、Thumb、オフセット `0x202844`、先頭8バイト `2d e9 f0 4f 03 af 91 b0`。
    - 引数の対応は逆アセンブルで確認: r1=song(has-bit 0x1)、r2=artist(0x4)、r3=album(0x2)、スタック[0]=s4(strlen、0x8)、[1]=bytes と [2]=長さ(0x10)、[3]=duration_ms(関数内で ÷1000 して秒)、[4]=rating。
    - `this+4` の1バイトが 0 なら何も送らず戻る(準備前)。`this+5` はチャネル番号。`this+8` が送信先。
  - endpoint の取り方(公開関数 `auto_reverse_send_media_playback_meta` と同じ): `g = *(base + 0x802d60)`、`x = *(g + 0xc)`、`endpoint = *(x + 0x74)`。公開関数は null を確かめないので、**公開関数は使わず、自分で null と準備フラグを確かめてから `sendMsgPlaybackMeta` を直接呼ぶ**。
- 実機には `sdAutoReverse` が2つある(親 `-c sd_msm6350` と、その子)。接続を持つのは子で、`g` が非 null なのも子(実測: 親 1649 / 子 29204、a0call status は子で `ready=1`)。
- 手本: `tools/a0call/a0call.c`(ptrace で主スレッドを止め、レジスタを差し替えて関数を1回呼び、元に戻して detach。DHU で実証済み)。**`tools/a0call/` は実車テスト(11-22)で使うので一切変えない**。必要な部分は aameta.c に写してよい。

## 作るファイル(これ以外は作らない・変えない)
- create: `tools/aameta/aameta.c`
- create: `tools/aameta/aameta-watch.sh`

## 仕様: aameta.c(root で動かす単独の実行ファイル)
1. 使い方: `aameta status` / `aameta conn` / `aameta send <title> <artist> <album> <duration_ms>`。引数違いは usage を stderr に出して終了コード 2。
2. 対象の選び方: `/proc/*/comm` が `sdAutoReverse` で、maps に `libSdAutoReverse.so`(offset 0 の行)があるプロセスのうち、`/proc/<pid>/mem` で読んだ endpoint が非 null のもの。複数あれば pid が大きい方。無ければ「未接続」を stderr に出して終了コード 1(send は何もしない)。
3. 版の確認: `base + 0x202844` の先頭8バイトが上記と違えば、何もせず終了コード 1。
4. `status`: `pid=… base=… endpoint=… ready=… channel=…` を1行で出す。終了コード 0。
5. `conn`: 準備済み(ready≠0)なら `<pid>:<endpointの16進>` だけを1行で出して 0。準備前・未接続なら何も出さず 1。(watch がつなぎ直しを見分けるのに使う)
6. `send`:
   - 準備フラグが 0 なら何もせず終了コード 1。
   - 文字列が `unKnow`(MainAiBox の空の印)なら空文字列にする。
   - 各文字列は最大 512 バイト。超えたら UTF-8 の文字の途中で切らないように切り詰める。
   - `duration_ms` は 10 進の整数。0 未満・数でない場合は usage で 2。
   - 主スレッド(tid = 選んだ pid)を ptrace で止め、`sendMsgPlaybackMeta(endpoint, title, artist, album, "", "", 0, duration_ms, 0)` を1回呼ぶ。システムコール中の再開の扱い・戻り先 `0xdead0001` の SIGSEGV で戻りを検出・想定外のシグナルで中止は a0call と同じ。
   - 文字列と5つのスタック引数は、止めたスレッドの sp より下の領域に置く(sp を十分下げ、8バイト境界)。書く前にその領域の元のバイトを保存し、呼び出し後(失敗時も)に書き戻してから、レジスタを戻して detach する。書き込みは `/proc/<pid>/mem`(O_RDWR)か PTRACE_POKEDATA のどちらでもよい(決めたことに書く)。
   - 成功したら `sent title=… artist=… album=… duration_ms=…` を stdout に出して 0。
7. ファイルシステムには一切書かない。
8. 冒頭コメントに: 目的、使い方、前提の版、ビルドコマンド(下の「使ってよいコマンド」と同じ)、「何も書き換えない・プロセスを再起動すれば痕跡は残らない」。

## 仕様: aameta-watch.sh(root の `sh` で動かす。Android の mksh/toybox で動く POSIX sh の範囲)
1. 先頭で `AAMETA=${AAMETA:-/data/local/tmp/aameta}`。
2. `logcat -v raw -s MediaInfo:D -T 1` を読み続ける(過去のログは使わない = 古い曲が混ざらない)。
3. `reportMediaInfo duration=` で始まる行: `duration` `title` `album` `artist` を取り出して「現在の組」として保持する(区切りは空白2つ + `title=` / `album=` / `artist=`。取り出しはパラメータ展開で、外部コマンドを使わない)。
4. `reportMediaInfo totalLen=` を含み `sendCQCmd=true` で終わる行(= CQ5 の時点): 現在の組があれば `"$AAMETA" send "$title" "$artist" "$album" "$duration"` を実行。成功したら、そのときの `"$AAMETA" conn` の出力を「送った接続」として覚える。
5. `sendCQCmd=false` で終わる行(約1秒ごと): 現在の組があり、`"$AAMETA" conn` が成功して、その出力が「送った接続」と違うとき(つなぎ直し・初回・前回送れなかった)だけ、4 と同じく送る。同じならなにもしない(毎秒は送らない)。
6. 送った/失敗したことを `log -t AAMeta "…"` で logcat に1行残す(send の出力か終了コードを含める)。
7. 曲名に空白2つ + `album=` などが入る特殊な場合は崩れうる。その制限を `shortcut:` の形の1行コメントで書く。

## 使ってよいコマンド(書いたとおりに)
- `env ZIG_GLOBAL_CACHE_DIR=/tmp/aameta-zig ZIG_LOCAL_CACHE_DIR=/tmp/aameta-zig /home/munde/bin/zig cc -target arm-linux-musleabihf -static -Os tools/aameta/aameta.c -o /tmp/aameta-zig/aameta`
- `sh -n tools/aameta/aameta-watch.sh`

実機(adb)は使わない。実機での確認は司令塔が行う。

## 報告
- ビルドとシェルの文法確認の結果(警告も含めて)。
- 「決めたこと」(RULES.md の 1)。
