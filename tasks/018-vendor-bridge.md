# Task 9: メーカー入口のまとめ役と分割の抽象

計画 `docs/superpowers/plans/2026-10-28-gt8d-launcher.md` の **Task 9**、仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §5.2・§5.3 に従う。計画の Global Constraints を守る(メーカー入口の値は綴りを原文のまま使う)。

## 作成・変更するファイル(これで全部。許可外の書込みは拒否され、そこで停止する)

- 新規: `app/src/main/java/com/gt8d/launcher/split/SplitLauncher.kt`、`ActivityLauncher.kt`、`VendorBridge.kt`
- 新規: `app/src/test/java/com/gt8d/launcher/split/VendorBridgeTest.kt`
- 変更: `app/src/main/AndroidManifest.xml`(`<queries>` に `<package android:name="com.sd.factoryconfig" />` を足す。`<queries>` が無ければ作る)

## 仕様(パッケージ `com.gt8d.launcher.split`)

`SplitLauncher.kt`:
```kotlin
enum class FailReason { EntryMissing, Exception, Timeout, OneSideMissing, NotReady }
sealed interface LaunchResult {
    data object Started : LaunchResult
    data class Failed(val reason: FailReason) : LaunchResult
}
interface SplitLauncher { fun open(left: String, right: String, ratio: SplitRatio): LaunchResult }
```

`ActivityLauncher.kt`:
```kotlin
data class LaunchSpec(val pkg: String, val cls: String, val extras: Map<String, String>)
interface ActivityLauncher {
    fun canResolve(pkg: String, cls: String): Boolean
    fun start(spec: LaunchSpec)                          // 失敗は例外として投げてよい
    fun startApp(pkg: String, adjacent: Boolean): Boolean
    fun isResizeable(pkg: String): Boolean
}
class AndroidActivityLauncher(private val context: Context) : ActivityLauncher
```
- `canResolve`: `packageManager.resolveActivity(Intent().setComponent(ComponentName(pkg, cls)), 0) != null`
- `start`: `Intent().setComponent(...)` に extras を `putExtra(String, String)` し、`FLAG_ACTIVITY_NEW_TASK` で `context.startActivity`
- `startApp`: `getLaunchIntentForPackage(pkg)` が null なら false。`adjacent` なら `NEW_TASK | LAUNCH_ADJACENT | MULTIPLE_TASK`、でなければ `NEW_TASK`。`startActivity` の例外は捕まえて false
- `isResizeable`: **常に true を返す**。理由をコメントに書く:「`ActivityInfo.resizeMode` は非公開 API のため読めない。分割非対応の判定は実機確認(計画 Task 20 Step 6b)で要否を決める」。SDK のクラスを調べるコマンド(`javap` 等)は実行しない

`VendorBridge.kt`:
```kotlin
class VendorBridge(private val launcher: ActivityLauncher) {
    companion object {
        const val VENDOR_PKG = "com.sd.factoryconfig"
        const val SPLIT_CLS = "com.sd.factoryconfig.SplitActivity"
        const val EXTRA_PACKAGES = "split_shortcur_package"   // 綴りは原文のまま
        const val EXTRA_PERCENT = "split_percent"
    }
    fun splitAvailable(): Boolean                        // launcher.canResolve(VENDOR_PKG, SPLIT_CLS)
    fun openSplit(left: String, right: String, ratio: SplitRatio): LaunchResult
    val carHomeAvailable: Boolean get() = false            // 実験⑧まで false
    fun switchToCarScreen(): Boolean = false               // 実験⑧まで何もしない
}
class VendorSplitLauncher(private val bridge: VendorBridge) : SplitLauncher   // open = bridge.openSplit
```
- `openSplit`: `splitAvailable()` が false なら `Failed(EntryMissing)`(start を呼ばない)。true なら `LaunchSpec(VENDOR_PKG, SPLIT_CLS, mapOf(EXTRA_PACKAGES to "$left|$right", EXTRA_PERCENT to ratio.vendorValue))` で `start`。例外なら `Failed(Exception)`。成功なら `Started`
- 呼び出しと結果は `DiagLog.d("Vendor", ...)` に記録(`DiagLog` は既存。JVM テストで安全)

## 手順(TDD)

1. テストを書く(純粋な JUnit。テストファイル内に `FakeActivityLauncher`(呼び出しを記録し、`canResolve` の戻り値と `start` で投げる例外を設定できる)を置く):
   - `openSplit_buildsExpectedSpec`: `openSplit("a","b", SplitRatio.R6_4)` → `start` が1回、spec == `LaunchSpec("com.sd.factoryconfig", "com.sd.factoryconfig.SplitActivity", mapOf("split_shortcur_package" to "a|b", "split_percent" to "3:2"))`、戻り値 `LaunchResult.Started`
   - `openSplit_entryMissing_returnsEntryMissing_andDoesNotStart`: `canResolve=false` → `Failed(EntryMissing)`、`start` は0回
   - `openSplit_startThrows_returnsException`: `start` が `RuntimeException` → `Failed(Exception)`
   - `carHome_unavailableByDefault`: `carHomeAvailable == false`、`switchToCarScreen() == false`
   - `vendorSplitLauncher_delegates`: `VendorSplitLauncher(bridge).open("x","y", SplitRatio.R5_5)` → spec の `split_percent` が `"1:1"`
2. `/home/munde/tools/gradle-run.sh test` を実行し、新しいテストが失敗する(コンパイルエラーを含む)ことを確認する
3. 実装する
4. `/home/munde/tools/gradle-run.sh test assembleDebug` で全部通るまで直す(最大3回。通らなければ失敗したテスト名とエラーの先頭30行を報告して止まる)

## 守ること

- テストの期待値を実装に合わせて弱めない
- `com.sd.factoryconfig` の他の画面を呼ばない。`com.android.launcher3` に触れない
- 最後に、`test assembleDebug` の結果(実行件数と失敗件数)と、作成・変更したファイル名を報告する
