最初に `tasks/RULES.md` を読み、従う。

# Task 229: よく使うアプリの数え方で、ホームに戻ったことを区切りにする

## 背景(司令塔が実機で確認済み)
- `rankFrequent` は eligible 外(ランチャー自身を含む)を除いてから、連続する同じアプリを1回にまとめる。そのため「ホーム → YouTube → ホーム → YouTube」が何回でも1回になる。実機では記録37回の YouTube(Morphe)が8回前後としか数えられず、ドックに上がらなかった。
- ユーザーと合意した新しい数え方:
  - **ランチャーのホーム画面(`com.gt8d.launcher.ui.home.HomeActivity`)の RESUMED を区切りにする**。ホームをはさんで同じアプリを開き直したら、それぞれ1回と数える。
  - パネル(`PanelActivity`)・中継(panelhost)・その他の eligible 外のアプリは、今までどおり区切りにしない(分割の操作で数が増えないように)。
  - ホームをはさんだ開き直しでも、そのアプリの直前の RESUMED から **2,000ms 未満**なら1回にまとめる(誤タップや画面の切り替え)。

## 変えること

### 1. ホームの印を記録に残す(`UsageHistory.kt`)
- `UsageHistory` に定数 `const val HOME_MARKER = "com.gt8d.launcher#home"` を companion に置く(タブ・改行を含まないこと)。
- 実機の照会の経路(`query == null` の分岐)で、`ACTIVITY_RESUMED` の `packageName` が `context.packageName` と同じで、`className` が `com.gt8d.launcher.ui.home.HomeActivity`(`HomeActivity::class.java.name` を使ってよい)なら、`UsageLaunch(HOME_MARKER, timeStamp)` として追加する。それ以外は今までどおり `UsageLaunch(packageName, timeStamp)`。
- この判定は、単体で試せる internal の関数に切り出す(例: `internal fun usageLaunchOf(pkg: String?, className: String?, timeStamp: Long, ownPkg: String): UsageLaunch?`。pkg が null なら null)。
- 印は `LaunchLog` にもそのまま保存される(`LaunchLog` は変更しない)。印は eligible に入らないので、ドックの候補にならない。

### 2. 数え方(`FrequentApps.kt` の `rankFrequent`)
- 引数・戻り値の型は変えない。時刻順に並べるのは今までどおり(同時刻は入力順)。
- 処理(1件ずつ):
  - `pkg == UsageHistory.HOME_MARKER` のとき: `beforeHome = previous`、`previous = null` にして次へ(数えない)。
  - pkg が eligible 外のとき: 飛ばす(previous を変えない。今までどおり)。
  - eligible のとき:
    - `pkg != previous` なら数える。ただし `pkg == beforeHome` かつ `last[pkg]` があって `wallMs - last[pkg] < 2000` のときは数えない。
    - `pkg == previous` なら数えない(今までどおり)。
    - どの場合も `last[pkg] = wallMs`、`previous = pkg`、`beforeHome = null`。
  - 2,000 は `FrequentApps.kt` の private な定数にする。
- 並べ方(回数の多い順 → 最後の時刻の新しい順 → 名前順)と上限は変えない。
- 回数に入らない場合でも、そのアプリが一度でも数えられていれば `last` は更新される(今までどおり)。一度も数えられていない pkg が結果に出ないことも今までどおり。

## テスト
### 既存のテストは変えない(`FrequentAppsTest.kt`・`UsageHistoryTest.kt`・`FrequentAppsRefresherTest.kt`・`LaunchLogTest.kt`)。
### `FrequentAppsTest.kt` に追加(別々のテスト):
1. `a@0, HOME@1000, a@5000, HOME@6000, a@10000` → a は 3。
2. `a@0, HOME@500, a@1500` → a は 1(2秒未満)。`a@0, HOME@500, a@2000` → a は 2(ちょうど 2,000ms は数える)。
3. `a@0, panel(eligible 外)@1000, a@5000` → a は 1(ホーム以外は区切りにしない)。
4. `a@0, HOME@1000, b@3000, a@3500` → a は 2、b は 1(ホームの後に別のアプリを開けば 2 秒の判定はしない)。
5. `HOME` の印だけ・印が先頭でも例外にならず、印は結果に出ない。
### `UsageHistoryTest.kt` に追加:
6. `usageLaunchOf`: 自分のパッケージの HomeActivity → 印、自分のパッケージの別の Activity(例 `com.gt8d.launcher.ui.panel.PanelActivity`)→ そのままの pkg、他のアプリ → そのままの pkg、pkg が null → null。

## 作る・変えるファイル(これ以外は変えない)
- `app/src/main/java/com/gt8d/launcher/apps/UsageHistory.kt`
- `app/src/main/java/com/gt8d/launcher/apps/FrequentApps.kt`
- `app/src/test/java/com/gt8d/launcher/apps/FrequentAppsTest.kt`
- `app/src/test/java/com/gt8d/launcher/apps/UsageHistoryTest.kt`

上記の範囲で済まない場合は止めて報告する。

## 使ってよいコマンド
    /home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.apps.*'

## 報告
- 実行したコマンドと結果(件数・失敗数)、変更の要約、「決めたこと」。
