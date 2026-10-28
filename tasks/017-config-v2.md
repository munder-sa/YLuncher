# Task 8: 設定 v2(比率・起動設定・v1 からの移行)

計画 `docs/superpowers/plans/2026-10-28-gt8d-launcher.md` の **Task 8**、仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §4.1・§5.4・§8 に従う。計画の Global Constraints を守る。

## 変更・作成するファイル(これで全部。許可外の書込みは拒否され、そこで停止する)

- 変更: `app/src/main/java/com/gt8d/launcher/config/Model.kt`、`ConfigCodec.kt`、`Defaults.kt`
- 変更: `app/src/test/java/com/gt8d/launcher/config/ConfigCodecTest.kt`、`DefaultsTest.kt`、`ConfigRepositoryTest.kt`
- 新規: `app/src/test/java/com/gt8d/launcher/config/SplitRatioTest.kt`

`ConfigRepository.kt` は変更しない(読み込みの規則は `ConfigCodec.decode` の変更だけで変わる)。

## 仕様(パッケージ `com.gt8d.launcher.config`)

`Model.kt` に追加・変更:
```kotlin
const val CONFIG_VERSION = 2

@Serializable
enum class SplitRatio(val label: String, val vendorValue: String) {
    R3_7("3:7", "3:7"), R4_6("4:6", "2:3"), R5_5("5:5", "1:1"), R6_4("6:4", "3:2"), R7_3("7:3", "7:3");
    companion object { val forPanel: List<SplitRatio> = listOf(R4_6, R5_5, R6_4) }
}
fun SplitRatio.allowedFor(right: Slot): Boolean   // Slot.Panel なら forPanel に含まれるときだけ true。Slot.App なら常に true

@Serializable data class BootConfig(val waitForCar: Boolean = false)
```
- `ScreenSet` に `val ratio: SplitRatio = SplitRatio.R5_5`、`val appPairLabel: String? = null` を**末尾に**足す
- `LauncherConfig` の `version` の既定を `CONFIG_VERSION` にし、`val boot: BootConfig = BootConfig()` を**末尾に**足す

`ConfigCodec.decode(json: String): LauncherConfig?` の規則を変える:
- **null を返すのは、壊れた JSON、または `version > CONFIG_VERSION` のときだけ**
- `version < CONFIG_VERSION`(=1)は**移行して返す**: 欠けたフィールドは既定値(`ratio=R5_5`、`appPairLabel=null`、`boot=BootConfig()`)で埋まり、`version` を `CONFIG_VERSION` にした値を返す
- `encode` は `version=CONFIG_VERSION` で書く(既存のまま `encodeDefaults=true`)

`Defaults.build` の画面セットに比率を入れる(仕様 §8):
- `set1` YouTube ／ Panel、`R6_4`
- `set2` YouTube ／ Apple Music、`R6_4`
- `set3` YouTube ／ Googleマップ、`R5_5`

## 手順(TDD)

1. テストを書く・直す:
   - 新規 `SplitRatioTest.ratio_vendorValues`: `SplitRatio.entries.map { it.vendorValue } == listOf("3:7","2:3","1:1","3:2","7:3")`、`label` は `listOf("3:7","4:6","5:5","6:4","7:3")`
   - 新規 `SplitRatioTest.panelRatios_only46_55_64`: `R3_7.allowedFor(Slot.Panel)==false`、`R7_3.allowedFor(Slot.Panel)==false`、`R4_6/R5_5/R6_4` は true。`Slot.App("x")` なら5つすべて true
   - `ConfigCodecTest` に `migrate_v1_to_v2_keepsSettings`: 次の v1 JSON(r1 形式。`ratio`・`appPairLabel`・`boot` 無し)を decode → null でない、`version==2`、`screenSets` の id・左右が元のまま、全セットの `ratio==R5_5`、`homeApps==listOf("a","b")`、`link.mode==LinkMode.Chrome`
     ```json
     {"version":1,"screenSets":[{"id":"s1","left":{"pkg":"a"},"right":{"type":"com.gt8d.launcher.config.Slot.Panel"}}],"homeApps":["a","b"],"panelApps":[],"dockApps":[],"idleApp":"a","link":{"mode":"Chrome","chromeDomains":[]},"gesture":{"stripWidthPx":8,"glow":true,"longPressMs":1000}}
     ```
     (`Slot.Panel` の JSON 表現は、今の `ConfigCodec.encode` が出す形に合わせてよい。合わせ方は、テストの中で `ConfigCodec.encode` の出力を読んで確かめる)
   - `ConfigCodecTest.decode_futureVersion_returnsNull` を、`version = 3` で null になるテスト(名前は `decode_version3_returnsNull`)に置き換える。`version = 2` は null にならない
   - `ConfigCodecTest.codec_roundTrip` は `version = 2` にし、`ratio` と `appPairLabel` を含むセットで等価になることを確かめる
   - `ConfigRepositoryTest` に `load_v1File_doesNotBackUp`: 上の v1 JSON を `config.json` に置いて `ConfigRepository` を作る → `config.first()` の `homeApps`(installed で絞られた後)と `screenSets` が v1 の中身で、`config.bak.json` が**存在しない**。既存の `load_corruptFile_fallsBackAndBacksUp` の「未来の version」のケースは `version = 3` に直す
   - `DefaultsTest.build_allInstalled_matchesSpec` の期待値を、上の比率つきの3セットに直す
2. `/home/munde/tools/gradle-run.sh test` を実行し、新しいテストが失敗する(コンパイルエラーを含む)ことを確認する
3. 実装する
4. `/home/munde/tools/gradle-run.sh test assembleDebug` で全部通るまで直す(最大3回。通らなければ失敗したテスト名とエラーの先頭30行を報告して止まる)

## 守ること

- テストの期待値を実装に合わせて弱めない。既存のテストを消さない(置き換えるのは上で指定したものだけ)
- 最後に、`test assembleDebug` の結果(実行件数と失敗件数)と、変更・作成したファイル名を報告する
