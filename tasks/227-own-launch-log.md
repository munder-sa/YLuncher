最初に `tasks/RULES.md` を読み、従う。

# Task 227: ドックの「よく使うアプリ」の履歴をランチャー自身のファイルに残す

## 背景(司令塔が実機で確認済み)
- ドックの自動モードは `UsageHistory.launchesSince(現在−7日)` の `ACTIVITY_RESUMED` を `rankFrequent` で数えて上位4つを出す。
- GT8D では本体の利用履歴(`/data/system_ce/0/usagestats` の日・週・月・年)が**本体の起動のたびに消え**、起動より前の履歴が無い。そのため起動直後は順位が空になり、ドックは手動の一覧に戻ってしまう(ユーザーの報告「ドックのアプリが入れ替わらない」)。
- ユーザーと合意した方針: ランチャーが自分で起動の記録をファイルに保存し、7日分で順位をつける。本体の履歴が消えても影響を受けない。

## 方式
- 本体の履歴から取れた起動(`UsageLaunch`)を、取得のたびにランチャーのファイルへ**合流して保存**し、順位づけには**ファイル側の7日分**を使う。ランチャーから開いたアプリも本体の履歴に `ACTIVITY_RESUMED` として残るので、ランチャーの起動処理に別の記録は足さない(二重に数えないため)。
- `rankFrequent`・`dockAppsFor`・`FrequentAppsRefresher` の判定(TTL・権限・失敗の扱い)は変えない。

## 作るもの

### 1. `app/src/main/java/com/gt8d/launcher/apps/LaunchLog.kt`(新規)
- `internal class LaunchLog(private val io: LaunchLogIo, private val maxEntries: Int = 4000)` と、`internal interface LaunchLogIo { fun read(): String?; fun write(text: String) }`(`read` はファイルが無ければ null、読み取りの失敗は例外)。ファイル用の実装 `LaunchLogFile(file: File)` を同じファイルに置き、書き込みは `android.util.AtomicFile` で行う(途中で電源が切れても前の内容が残るように)。`LaunchLog(file: File)` の補助コンストラクタを用意する。
- 公開する関数は1つ: `fun merge(observed: List<UsageLaunch>, sinceWallMs: Long): List<UsageLaunch>`。スレッド安全(`synchronized`)。
  1. 初回だけファイルを読む(以後はメモリの内容を使う)。形式は1行1件 `wallMs<TAB>pkg`。数値でない・タブが無い・pkg が空の行は飛ばす。
  2. 読み取りが例外のときは、**ファイルを上書きしない**(壊さない)。その回は `observed` を `sinceWallMs` 以上に絞って時刻順で返し、読み込み済みにしない(次の呼び出しで読み直す)。
  3. 記録に `observed` を合流する。同じ `(pkg, wallMs)` は1件にまとめる。
  4. `wallMs < sinceWallMs` の件を捨てる。**未来の時刻(現在より後)の件は捨てない**(本体の時計が戻った場合に記録を失わないため)。件数が `maxEntries` を超えたら、`wallMs` の新しい方から `maxEntries` 件だけ残す。
  5. 内容が前回保存したものから変わったときだけ書く(変わらなければ書かない)。書き込みが例外のときは、メモリの内容は保ち、例外を外へ出さない(次の変化で再び書く)。
  6. 残した記録を `wallMs` の昇順(同時刻は pkg の昇順)で返す。

### 2. `AppGraph.kt` の配線
- `refreshFrequentApps()` 内の `FrequentAppsRefresher` 生成で、`launchesSince = { history.launchesSince(it) }` を次に変える:
  `launchesSince = { since -> history.launchesSince(since)?.let { launchLog.merge(it, since) } }`
  - `launchLog` は同じ場所で `LaunchLog(File(context.filesDir, "launch_log.tsv"))` として作る(refresher と同じ寿命)。
  - 本体の取得が null(権限なし・失敗)のときは今までどおり null(失敗の扱いは変えない)。
- それ以外の AppGraph の処理は変えない。

## テスト
### `app/src/test/java/com/gt8d/launcher/apps/LaunchLogTest.kt`(新規、JVM。偽の `LaunchLogIo` を使ってよい。`LaunchLogFile` を試す件だけ Robolectric と一時フォルダを使う)
最低限、次をそれぞれ別のテストで確かめる:
1. 保存した記録が、新しい `LaunchLog`(同じ io)の `merge(emptyList(), since)` で返る(本体の履歴が消えた後も残る)。
2. 同じ `(pkg, wallMs)` を2回合流しても1件。同じ pkg でも時刻が違えば別件。
3. `sinceWallMs` より古い件は返さず、保存からも消える。未来の時刻の件は残る。
4. `maxEntries` を超えたら新しい方だけ残る(小さい `maxEntries` で)。
5. 壊れた行(数値でない・タブなし・pkg 空)は飛ばし、正しい行は読む。
6. 読み取りが例外 → 書き込みは0回、`observed` の範囲内だけを返す。次の呼び出しで読み直し、読めれば合流する。
7. 内容が変わらない merge では書き込みが増えない。
8. 書き込みが例外でも merge は例外を出さず、返り値は合流後の内容。
9. `LaunchLogFile`: 書いた内容を別のインスタンスで読める。ファイルが無ければ `read()` は null。

### `FrequentAppsRefresherTest.kt` など既存テストは変えない。

## 作る・変えるファイル(これ以外は変えない)
- `app/src/main/java/com/gt8d/launcher/apps/LaunchLog.kt`(新規)
- `app/src/test/java/com/gt8d/launcher/apps/LaunchLogTest.kt`(新規)
- `app/src/main/java/com/gt8d/launcher/AppGraph.kt`

上記の範囲で済まない場合は止めて報告する。

## 使ってよいコマンド
    /home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.apps.*'

## 報告
- 実行したコマンドと結果(件数・失敗数)、変更の要約、「決めたこと」。
