最初に `tasks/RULES.md` を読み、従う。

# Task 242: carprobe の接続先を abstract ソケット `gt8d-ipv6-guard` へ差し替え

`tools/carprobe`(独立 APK `com.gt8d.carprobe`)の `AdbClient` は現在 TCP `127.0.0.1:5555` に繋ぐが、この端末の adbd は **TCP 5555 を一切 LISTEN しない**(実機で確定)。代わりに adbd は `service.adb.listen_addrs=localabstract:gt8d-ipv6-guard` により、**abstract unix ソケット `@gt8d-ipv6-guard` で素の(非 TLS)adb トランスポート**を開いている。`ro.adb.secure=0`・SELinux Permissive なので、ここへ繋いで `CNXN`(認証なし)→ `OPEN shell:` すれば root シェルが取れる。

この方式はユーザーの別アプリ `GT8D-IPv6-Guard`(`local.gt8d.ipv6guard`、通常アプリ)で**実機実証済み**。手本として次のソースを読むこと(読み取りのみ、変更しない):
- `GT8D-IPv6-Guard/src/local/gt8d/ipv6guard/LocalAdb.java`(`LocalSocket` + abstract 接続と ADB プロトコル)
- `GT8D-IPv6-Guard/src/local/gt8d/ipv6guard/Bootstrap.java`(未起動時だけ `listen_addrs` 設定+`ctl.restart adbd`)

**YLuncher 本体(`app/`・`panelhost/`)と `GT8D-IPv6-Guard/` には一切触れない。** `tools/carprobe` の中だけで完結させる。

## 作る/変えるファイル(これ以外は触らない)
- **write** `tools/carprobe/src/main/java/com/gt8d/carprobe/AdbClient.java`
- **write** `tools/carprobe/src/main/java/com/gt8d/carprobe/MainActivity.java`(表示文言と Finish の注記だけ)
- **write** `tools/carprobe/src/main/AndroidManifest.xml`(`INTERNET` 権限を削除。他は変えない)
- **write** `tools/carprobe/src/test/java/com/gt8d/carprobe/AdbMessageTest.java`(既存 8 件を保つ。`encode`/`readMessage` の呼び方が変わった場合の追従だけ。期待値は変えない)

`ServiceSummary.java` / `ServiceSummaryTest.java` / `build.gradle.kts` / `settings.gradle.kts` は変えない。

## AdbClient.java の変更

### 接続先
- TCP `Socket` をやめ、`android.net.LocalSocket` を使う。接続先は `new LocalSocketAddress("gt8d-ipv6-guard", LocalSocketAddress.Namespace.ABSTRACT)`。定数 `SOCKET_NAME = "gt8d-ipv6-guard"`。**ソケット名は ipv6guard と共用**(同じ adbd の listen ソケット。adbd は複数接続を並行して受けられる)。
- `HOST`/`PORT` は削除。`Result.via` に入れる表示値は `"localabstract:gt8d-ipv6-guard"`。
- `LocalSocket.setSoTimeout(…)` で読み取りのタイムアウトを掛ける(現行の deadline 管理を保つ。`LocalSocket` には `connect` のタイムアウト引数が無いので、接続はそのまま呼ぶ)。

### 要件1: 無用な adbd 再起動を避ける(接続を先に試す)
`run(command, timeoutMs)` の接続処理を次の順にする:
1. まず abstract ソケットへ `connect` を試す。**成功したら、ブートストラップは一切しない**(`setprop` も `ctl.restart` も呼ばない)。現行のワイヤレス ADB セッションを切らないため。
2. `connect` が `IOException` で失敗したときだけ、ブートストラップを 1 回行う(`Bootstrap.java` と同じ手順):
   - `ProcessBuilder("/system/bin/setprop", "service.adb.listen_addrs", "localabstract:gt8d-ipv6-guard")` を実行(2 秒で打ち切り、終了コード 0 以外は失敗)。
   - `ProcessBuilder("/system/bin/setprop", "ctl.restart", "adbd")` を実行(同上)。
   - 200ms 間隔で最大 8 回、`connect` を試す(接続確認だけして閉じる)。開けば次へ。開かなければ失敗 `Result(-1, "端末内のADB待受(gt8d-ipv6-guard)を起動できません", via)`。
3. ブートストラップ後にもう一度 `connect` して本処理へ進む。
- ブートストラップの実行はプロセス内で直列化する(既存の `synchronized` で十分)。ブートストラップを行ったかどうかは、`Result.stdout` の先頭に 1 行 `[bootstrap: listen_addrs set, adbd restarted]` を付けて分かるようにする(行わなかった場合は付けない)。

### 変えないこと
- ADB プロトコル部分(`encode`/`readMessage`/`CNXN`→`OPEN shell:`→`WRTE`/`OKAY`/`CLSE`、定数、magic 検証、長さ上限、stream id 検証)はそのまま。
- 終了コードの回収は現行どおり `command + "\n" + "echo __CARPROBE_EXIT__$?"` のラップと最後のマーカー行の抽出。
- `quote(String)` はそのまま。
- CNXN の version/maxdata・`"host::\0"` は現行値のままでよい(ipv6guard は `0x01000000`/4096 だが、どちらでも adbd は受ける。変えない)。

## MainActivity.java の変更(最小)
- 疎通 OK 時の表示を `"adb: OK (uid=0 @localabstract:gt8d-ipv6-guard)"` に変える(現行の `@127.0.0.1:5555` を置換)。判定ロジック(`ok` かつ `uid=0` を含む)は変えない。
- **要件2: Finish と ipv6guard の競合の明記**。`finishAdb` の最後の画面表示(停止コマンドの応答の後)に、次の注記を 1 行加える:
  `注意: GT8D-IPv6-Guard の自動適用が有効な場合、その起動時・15分周期ジョブ・アプリ起動時に adbd と gt8d-ipv6-guard ソケットが再起動され、ADB は再び有効になります。完全に止めるには GT8D-IPv6-Guard で「自動適用を停止」してください。`
  (セッションログにも同じ文を残す。adbd 停止前に書く順序は現行の意図どおり保つ)
- それ以外(ボタン構成・コマンド文字列・`ServiceSummary` 連携)は変えない。

## AndroidManifest.xml
- `<uses-permission android:name="android.permission.INTERNET" />` を削除する(LocalSocket の abstract 接続には不要)。他の行は変えない。権限は何も付けない。

## テスト
- `AdbMessageTest` の既存 8 件(エンコードのバイト列・crc・往復・bad magic 等)が引き続き通ること。ソケットの種類はテストに関係しないので、原則このファイルは変更不要。`encode`/`readMessage` のシグネチャを変えたときだけ呼び出しを追従させる(期待値・件数は変えない)。
- ソケット接続とブートストラップ(`LocalSocket`・`setprop`)は単体テストしない(実機確認は司令塔)。

## 使ってよいコマンド
    /home/munde/tools/gradle-run.sh --offline -p tools/carprobe -Pandroid.useAndroidX=true testDebugUnitTest
    /home/munde/tools/gradle-run.sh --offline -p tools/carprobe -Pandroid.useAndroidX=true assembleDebug

## 報告
- 変えたファイル、コマンドと結果(テスト件数・失敗件数・時間)、APK のパス、決めたこと(RULES.md の 1)。
- `LocalSocket` の API の挙動で確証が持てない点があれば、RULES.md の 2(3 点セット)で止まる。
