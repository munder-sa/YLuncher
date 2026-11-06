最初に tasks/RULES.md を読む。

# Task210 実機用UsageEvents比較プローブ

あなたは実装担当。API35のイベント絞込みを実機で照合する検証用のJava単一クラスUsageEventsProbeをtools/usageprobe/UsageEventsProbe.javaに作る。アプリ本体へ含めない。ADB/実機実行/Gitは司令塔が担当する。

- Android標準app_processで動くmain(String[])。Looper.prepareMainLooperしActivityThread.systemMain/getSystemContextはreflectionで取得、ContextのUsageStatsManagerを使用。rootのshellで動くがアクセス許可は実行して確認し、不許可なら明示失敗。権限回避・端末設定変更はしない。
- 24時間と7日について、固定begin/end（開始時のnow-2秒をend）でqueryEvents(begin,end)とqueryEvents(new UsageEventsQuery.Builder(begin,end).setEventTypes(ACTIVITY_RESUMED).build())を交互3組。時刻範囲とpackageは同じ。各query呼出しと列挙/解析のelapsedRealtimeNanosを分けて記録。
- 旧方式の全取得件数とRESUMED件数、新方式件数、旧RESUMEDと新結果のSHA256（event type/package/class/timeを長さ区切りで順序付きdigest）を比較し、一致/不一致をJSON行でstdoutへ出す。nullを成功空一覧にしない。actual package/class/event内容は出力・ファイル保存しない。アプリ名/歌詞/認証値を一切出さない。
- 比較不一致/例外は非0終了。新方式でRESUMED以外が含まれるなら失敗。各窓の最初をwarmup扱いで捨てない（全3組出す）。日時は数値だけ。JSONは標準org.jsonまたは固定安全文字列で生成し、新規依存不要。nested classを使ってもよいが下記D8対象に含まれるようにする。

許可ファイルは新規Java1つ。許可コマンド（順に各1回。実装修正を伴う失敗再実行のみ可）:

`mkdir -p build/usageprobe/classes build/usageprobe/dex`

`/home/munde/tools/jdk17/bin/javac -source 8 -target 8 -classpath /home/munde/tools/android-sdk/platforms/android-35/android.jar -d build/usageprobe/classes tools/usageprobe/UsageEventsProbe.java`

`/home/munde/tools/jdk17/bin/java -cp /home/munde/tools/android-sdk/build-tools/35.0.0/lib/d8.jar com.android.tools.r8.D8 --min-api 35 --lib /home/munde/tools/android-sdk/platforms/android-35/android.jar --output build/usageprobe/dex build/usageprobe/classes/*.class`

ビルドのみで実機成功を主張しない。コードと実行結果/決めたことを報告。
