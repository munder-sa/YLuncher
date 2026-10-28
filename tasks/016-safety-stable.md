# Task 7b: 10 秒動いたらクラッシュの連続を切る(Task 7 の修正)

Task 7(`tasks/015-diag-safety.md`)の指示に誤りがあった。仕様 §7.2 は「起動から 10 秒以内のクラッシュが 3 回連続で退避。**10 秒を超えて動けば連続カウントを 0 に戻す**」。今の `SafetyGuard` は、クラッシュせずに長く動いた起動を記録しないため、別々の日の素早いクラッシュが連続として数えられてしまう。これを直す。

## 変更するファイル(これだけ。許可外の書込みは拒否され、そこで停止する)

- `app/src/main/java/com/gt8d/launcher/safety/SafetyGuard.kt`
- `app/src/main/java/com/gt8d/launcher/LauncherApp.kt`
- `app/src/test/java/com/gt8d/launcher/safety/SafetyGuardTest.kt`

## 仕様

- `SafetyGuard` に `fun markStable(context: Context)` を足す: 保存されている `crashes` を空にする。`fallback` は**変えない**(退避中の解除は `clear` だけ)
- `LauncherApp.onCreate` で、`recordStart` の後に `Handler(Looper.getMainLooper()).postDelayed({ SafetyGuard.markStable(this) }, 10_000L)` を登録する
- 既存の `shouldFallback`・`recordStart`・`recordCrash`・`clear`・`isFallbackActive` の挙動は変えない

## 手順(TDD)

1. `SafetyGuardTest` にテストを足す(Robolectric を使う: `@RunWith(RobolectricTestRunner::class)`、`@Config(sdk = [35])`、`ApplicationProvider.getApplicationContext<Context>()`。既存の3つのテストはそのまま):
   - `markStable_clearsStreak_keepsFallback`: `recordStart`→`recordCrash` を2回行ったあと `markStable` → さらに `recordStart`→`recordCrash` を1回行っても `isFallbackActive == false`
   - `markStable_doesNotClearActiveFallback`: `recordStart`→`recordCrash`×3→`recordStart`(ここで退避)→`markStable` のあとも `isFallbackActive == true`
   - Robolectric の `SystemClock` は進まないので、経過 ms は 0(=10000 以内)になる前提でよい
2. `/home/munde/tools/gradle-run.sh test` を実行し、新しいテストが失敗することを確認する
3. 実装する
4. `/home/munde/tools/gradle-run.sh test assembleDebug` で全部通るまで直す(最大3回。通らなければ失敗したテスト名とエラーの先頭30行を報告して止まる)

## 守ること

- テストの期待値を実装に合わせて弱めない
- 最後に、`test assembleDebug` の結果(実行件数と失敗件数)と、変更したファイル名を報告する
