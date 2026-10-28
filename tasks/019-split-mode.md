# Task 10: 方式の選択と失敗回数

計画 `docs/superpowers/plans/2026-10-28-gt8d-launcher.md` の **Task 10**、仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §7.1 に従う。計画の Global Constraints を守る。

## 作成するファイル(これで全部。許可外の書込みは拒否され、そこで停止する)

- `app/src/main/java/com/gt8d/launcher/split/SplitModeSelector.kt`
- `app/src/test/java/com/gt8d/launcher/split/SplitModeSelectorTest.kt`

既存のファイルは変更しない。`FailReason` は既存の `split/SplitLauncher.kt` にある。

## 仕様(パッケージ `com.gt8d.launcher.split`)

```kotlin
enum class SplitMode { Vendor, AppPair, LeftOnly }

@Serializable
data class SplitModeState(
    val vendorUsable: Boolean = true,
    val consecutiveFailures: Int = 0,
    val lastFailure: FailReason? = null,
)

interface SplitModeStore { fun load(): SplitModeState; fun save(state: SplitModeState) }
class FileSplitModeStore(private val file: File) : SplitModeStore   // JSON。無い・壊れているときは SplitModeState()

class SplitModeSelector(private val store: SplitModeStore, private val dReady: () -> Boolean) {
    val state: SplitModeState
    fun current(): SplitMode
    fun onSuccess()
    fun onFailure(reason: FailReason, countable: Boolean)
    fun onBoot(entryFound: Boolean)
    fun restoreVendor()
}
```
`FailReason` が `@Serializable` でなければ、`SplitModeState` での保存のために `split/SplitLauncher.kt` を変えず、`lastFailure` を `String?`(`FailReason.name`)で持ってよい(そのときはテストもそれに合わせる)。

規則:
- `current()`: `vendorUsable` なら `Vendor`。そうでなく `dReady()` が true なら `AppPair`。どちらでもなければ `LeftOnly`
- `onFailure(EntryMissing, *)`: 即 `vendorUsable=false`(`countable` に関係なく)。`lastFailure` を更新
- それ以外の `onFailure`: `countable` のときだけ `consecutiveFailures+1`。3 以上で `vendorUsable=false`。`lastFailure` は常に更新
- `onSuccess()`: `consecutiveFailures=0`
- `onBoot(true)` と `restoreVendor()`: `vendorUsable=true`、`consecutiveFailures=0`
- `onBoot(false)`: `vendorUsable=false`
- 状態が変わるたびに `store.save`。初期値は `store.load()`

## 手順(TDD)

1. テストを書く(純粋な JUnit。メモリ上の `FakeSplitModeStore` と、`@get:Rule TemporaryFolder` を使う):
   - `threeCountableFailures_disableVendor`: Timeout(countable=true)×2 → `Vendor`、3回目で `LeftOnly`(dReady=false)
   - `uncountableFailures_ignored`: Timeout(countable=false)×5 → `Vendor`、`consecutiveFailures==0`
   - `success_resetsCount`: countable×2 → onSuccess → countable×2 → まだ `Vendor`
   - `entryMissing_switchesToLeftOnly_whenDNotReady`
   - `entryMissing_switchesToAppPair_whenDReady`
   - `onBoot_entryFound_restoresVendor`: 使えない状態・回数 2 → `onBoot(true)` → `Vendor`、回数 0
   - `onBoot_entryMissing_disablesVendor`
   - `restoreVendor_manual`
   - `state_persistsAcrossInstances`: `FileSplitModeStore` で entryMissing を記録 → 同じファイルの新しいインスタンスで `current()==LeftOnly`
   - `fileStore_corrupt_returnsDefault`: 壊れた JSON → `SplitModeState()`
2. `/home/munde/tools/gradle-run.sh test` を実行し、新しいテストが失敗することを確認する
3. 実装する
4. `/home/munde/tools/gradle-run.sh test assembleDebug` で全部通るまで直す(最大3回。通らなければ失敗したテスト名とエラーの先頭30行を報告して止まる)

## 守ること

- テストの期待値を実装に合わせて弱めない。Android の API を使わない(純粋な Kotlin)
- 最後に、`test assembleDebug` の結果(実行件数と失敗件数)と、作成したファイル名を報告する
