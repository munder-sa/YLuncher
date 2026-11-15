最初に `tasks/RULES.md` を読み、従う。

# Task 241: carprobe を loopback ADB クライアントへ差し替え

`tools/carprobe`(独立 APK `com.gt8d.carprobe`)の root 取得方式を変更する。実機で確認した結果、通常アプリ UID から `sdsu_no` / `su` は SELinux の su ドメインで拒否され、root シェル経由は使えない。

代わりに、この開発機の開発者オプション ADB(`ro.adb.secure=0`、`getenforce=Permissive`、root で動作中)に対し、**端末内のループバック `127.0.0.1:5555` へ TCP 接続し、標準 ADB プロトコル(`CNXN` → `OPEN "shell:…"` → `WRTE`/`CLSE`)で認証なしにシェルを開く最小クライアント**に差し替える。`5555` は adbd が `ro.adb.secure=0` のため AUTH を要求せず、CNXN に CNXN で応答する。

**YLuncher 本体(`app/`・`panelhost/`)には一切触れない。** `tools/carprobe` の中だけで完結させる。

## 背景(決まっていること)
- 事前に `persist.adb.tcp.port 5555` は設定済み(司令塔が実施)。adbd が次に起動すると loopback 含む `0.0.0.0:5555` を開く。
- `/data/local/tmp/a0call`(実行中 `sdAutoReverse` に ID 13 の NAV フォーカスを送る root ツール)は配置済み。`a0call status` / `a0call native` / `a0call toggle <待機ms>`。
- `/sdcard/Download/` へのログ保存、`ServiceSummary` の解析、ボタン構成は Task 240 のまま維持する。root シェルではなく ADB `shell:` 経由で同じコマンドを流すだけに変える。
- ループバックとはいえ TCP ソケットを開くには `android.permission.INTERNET` が必要。Manifest に追加する(これ以外の権限は付けない)。

## 作る/変えるファイル(これ以外は触らない)
- **create** `tools/carprobe/src/main/java/com/gt8d/carprobe/AdbClient.java`(新規。ADB プロトコルの最小クライアント)
- **create** `tools/carprobe/src/test/java/com/gt8d/carprobe/AdbMessageTest.java`(JUnit4。プロトコルの枠組みの単体テスト)
- **write** `tools/carprobe/src/main/java/com/gt8d/carprobe/MainActivity.java`(`RootShell` 参照を `AdbClient` に置換。文言から root/su を外す)
- **write** `tools/carprobe/src/main/AndroidManifest.xml`(`<uses-permission android:name="android.permission.INTERNET"/>` を `<application>` の前に追加。他は変えない)

`RootShell.java` は使わなくなる。**削除は司令塔が git で行う**ので、この Task では触らない(参照さえ消えていればよい)。`ServiceSummary.java` / `ServiceSummaryTest.java` / `build.gradle.kts` / `settings.gradle.kts` は変えない。

## AdbClient.java(ADB プロトコルの最小実装)

パッケージ `com.gt8d.carprobe`。Android に依存するのはソケットだけ。UI スレッドから呼ばない前提は従来どおり。

### 定数(リトルエンディアンの u32)
- コマンド(4 文字をワイヤ上で ASCII 順に並べる=リトルエンディアンの u32): `A_CNXN=0x4e584e43`、`A_OPEN=0x4e45504f`、`A_OKAY=0x59414b4f`、`A_CLSE=0x45534c43`、`A_WRTE=0x45545257`、`A_AUTH=0x48545541`(例: `OKAY` はバイト列 `4f 4b 41 59` なので LE u32 は `0x59414b4f`)
- 接続先 `HOST="127.0.0.1"`、`PORT=5555`
- CNXN の version=`0x01000001`、maxdata=`0x40000`(256KiB)、system-identity ペイロードは `"host::\0"`(末尾 NUL 1 バイトを含むバイト列)

### メッセージの形式(`Message` を内部クラスにしてよい)
24 バイトのヘッダ + ペイロード。ヘッダは次の u32 を**この順**でリトルエンディアンで並べる:
1. `command`
2. `arg0`
3. `arg1`
4. `data_length`(ペイロードのバイト長)
5. `data_crc32`(ペイロードの全バイトを符号なしで単純加算し `& 0xffffffff`。adbd は無視するが計算して入れる)
6. `magic` = `command ^ 0xffffffff`

- `encode(command, arg0, arg1, payload)` → byte[](ヘッダ+ペイロード)を返す静的メソッド。`payload` が null のときは長さ 0・crc 0。
- `readMessage(InputStream)` → ヘッダ 24 バイトを**ちょうど**読み(足りなければ `EOFException`)、`command` と `magic` の整合(`magic == (command ^ 0xffffffff)`)を検査し、不一致なら `IOException("bad magic")`。続けて `data_length` バイトを**ちょうど**読んでペイロードにする。`command`/`arg0`/`arg1`/`payload` を保持する小さな値オブジェクトを返す。
- バイトの詰め出しは `ByteBuffer.order(LITTLE_ENDIAN)` を使ってよい。`readFully` 相当(戻り値 -1 で `EOFException`)を自前で用意する。

### Result(従来と互換の形)
`public final int exitCode; public final String stdout; public final boolean ok; public final String via;` を持つ。`ok` は `exitCode == 0`。`via` は経路表示用に `"127.0.0.1:5555"` を入れる(MainActivity の `describe` がそのまま使える)。

### run(String command, int timeoutMs)
1 回の呼び出しで 1 本のソケットを張り、ハンドシェイク→1 本の `shell:` ストリーム→クローズまでを行い、`Result` を返す。`synchronized` でよい(呼び出し側の単一スレッド実行と二重の安全)。

手順:
1. `timeoutMs <= 0` なら `Result(-1, "timeoutMs must be positive", via)`。スレッドが割り込み済みなら `Result(-1, "Interrupted", via)`。
2. `Socket` を作り、`connect(new InetSocketAddress(HOST, PORT), timeoutMs)`、`setSoTimeout(timeoutMs)`、`setTcpNoDelay(true)`。接続できなければ理由を `stdout` に入れた失敗 `Result` を返す(`exitCode=-1`)。
3. **ハンドシェイク**: `encode(A_CNXN, version, maxdata, "host::\0")` を送る。`readMessage` で応答を読む。
   - `A_CNXN` が返れば接続成立(次へ)。
   - `A_AUTH` が返ったら、この端末は認証なしの想定なので鍵交換はせず、`Result(-1, "device requires ADB auth (expected ro.adb.secure=0)", via)` を返して終了する(推測で鍵を作らない)。
   - それ以外は `IOException("unexpected handshake: 0x…")`。
4. **ストリームを開く**: `localId = 1`、`remoteId = 0`。実際に流すコマンドは、終了コードを回収するため次のように**ラップ**する(`shell:` v1 は終了コードを返さないため):
   `wrapped = command + "\n" + "echo " + EXIT_MARKER + "$?"`
   ただし `EXIT_MARKER = "__CARPROBE_EXIT__"`。`encode(A_OPEN, localId, 0, "shell:" + wrapped + "\0")` を送る。
5. 応答を読む。`A_OKAY(arg0=remoteId, arg1=localId)` なら `remoteId = msg.arg0` を保持。`A_CLSE` なら即クローズ(出力なし)。それ以外やホスト不一致は `IOException`。
6. **本体の読み取りループ**(`deadline = System.nanoTime() + timeoutMs`):
   - `readMessage`。
   - `A_WRTE` なら `payload` を出力バッファ(`ByteArrayOutputStream`)へ追記し、受領応答として `encode(A_OKAY, localId, remoteId, null)` を送る。
   - `A_CLSE` なら `encode(A_CLSE, localId, remoteId, null)` を送って読み取り終了。
   - `A_OKAY` は無視(フロー制御)。
   - `System.nanoTime() > deadline` を超えたら、ソケットを閉じて `Result(124, out + "\nTimed out after … ms", via)`。
7. **終了コードの抽出**: 出力を UTF-8 文字列にし、**末尾付近の最後の** `__CARPROBE_EXIT__<数字>` 行を正規表現で探す。見つかれば `exitCode` にその数値、その行(とそれ以降)を出力文字列から取り除く。見つからなければ `exitCode = 0`(ストリームが正常クローズしたので成功扱い)にしつつ、出力はそのまま。先頭/末尾の余分な改行だけ整える。
8. `IOException` / `SocketException` / `SecurityException` は捕捉し、`Result(-1, これまでの出力 + "\n" + 例外, via)`。`finally` でソケットを閉じる。`InterruptedException` 相当の割り込みは `Thread.currentThread().interrupt()` を立てて失敗 `Result`。

### quote(String)
従来 `RootShell.quote` にあったシングルクォート安全化をそのまま `AdbClient.quote` に持つ(`'` を `'\"'\"'` に置換して全体を `'…'` で囲む)。MainActivity が使う。

### 注意
- 大きな出力(`logcat -b all`)はシェル側で `>> ファイル` にリダイレクトされるため、ソケットを渡るのは終了マーカーなど僅かだけ。クライアント側で巨大データを抱えない設計を保つ。
- `exec` は使わない(`shell:` が毎回新しいシェルを張るので不要。終了マーカーが動くよう `exec` で置き換えない)。

## MainActivity.java の変更
- `RootShell.` の参照を全て `AdbClient.` に置換する(`run` / `quote` / `Result`)。
- コマンド文字列から先頭の `exec ` を外す(`"exec id"`→`"id"`、`"exec " + FILTERED_LOG`→`FILTERED_LOG`、`saveBootLog` の `"exec logcat … >> …"`→`"logcat … >> …"`、`runA0`/`finishAdb` の `"exec " + …`→`…`)。リダイレクトや引数はそのまま。
- 起動時の疎通表示(`checkRoot` 相当)は `AdbClient.run("id", …)` を実行し、`uid=0` を含むかで判定。文言は root/su を使わず次のようにする:
  - 成功: `"adb: OK (uid=0 @127.0.0.1:5555)"`
  - 失敗: `"adb: NO (" + 理由 + ")"`
  - メソッド名・変数名(`rootView` 等)はそのままでよいが、ユーザーに見える文言だけ上記に合わせる。ボタンの有効化条件(疎通 OK かつ非 busy)は維持する。
- `Finish & Disable ADB`(ボタン 5)はそのまま維持してよい。ただし `adbd` を止めると自分の接続も切れるため、**最後の `setprop ctl.stop adbd` を実行する前に、画面とログへ結果を書き終えてから送る**従来の順序を保つ(240 の実装の意図どおり)。

## AdbMessageTest.java(JUnit4、端末不要で動くものだけ)
`AdbClient` のうち端末に依存しない部分を検査する。ソケットは張らない。
1. `encode(A_CNXN, 0x01000001, 0x40000, "host::\0")` のヘッダ 24 バイトを検証: 先頭 4 バイトが `43 4e 58 4e`(リトルエンディアンで `0x4e584e43`)、`data_length` がペイロード長、`magic` が `command ^ 0xffffffff`、末尾ペイロードが `host::\0` のバイト列。
2. `data_crc32` が「ペイロード各バイトの符号なし加算 `& 0xffffffff`」に一致すること(非 ASCII を含む短い payload で確認。例: バイト 0x80 を含める)。
3. `encode` の結果を `readMessage` に通すと、`command`/`arg0`/`arg1`/`payload` が往復で一致すること(round-trip)。
4. `magic` を 1 バイト壊したバイト列を `readMessage` に渡すと `IOException`(bad magic)になること。
- `encode` / `readMessage` は `static` か、テストから呼べる可視性にする。ソケット接続を伴う `run` はテストしない(実機確認は司令塔)。

## 使ってよいコマンド
    /home/munde/tools/gradle-run.sh --offline -p tools/carprobe -Pandroid.useAndroidX=true testDebugUnitTest
    /home/munde/tools/gradle-run.sh --offline -p tools/carprobe -Pandroid.useAndroidX=true assembleDebug

## 報告
- 変えた/作ったファイル、コマンドと結果(テスト件数・失敗件数・ビルド時間)、APK のパス、決めたこと(RULES.md の 1)。
- プロトコルの解釈で確証が持てない点があれば、RULES.md の 2(3 点セット)で止まって報告する。
