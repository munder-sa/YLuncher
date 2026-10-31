# Task 074: セットの型を左右対称にし、設定を版 3 へ移行する(v1.5 Task 1)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
v1.5 で、画面セットのパネルを左にも置けるようにする。設計書 `docs/superpowers/specs/2026-10-31-v1.5-left-panel-design.md` §3.1、計画 `docs/superpowers/plans/2026-10-31-v1.5-left-panel.md` の Task 1 を読むこと(この指示書が優先)。この Task では**型と移行だけ**を変え、左パネルの起動・表示はまだ作らない(後の Task)。右パネル・両方アプリの今の動きは一切変えない。

## 変更・作成するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/config/Model.kt`
- `app/src/main/java/com/gt8d/launcher/config/ConfigCodec.kt`
- `app/src/main/java/com/gt8d/launcher/config/ConfigRepository.kt`
- `app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt`(コンパイルを通すための分岐だけ)
- `app/src/main/java/com/gt8d/launcher/settings/SetsEdit.kt`(同上)
- `app/src/main/java/com/gt8d/launcher/settings/SetsScreen.kt`(同上)
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeSetCards.kt`(同上)
- `app/src/test/java/com/gt8d/launcher/config/ConfigCodecTest.kt`
- `app/src/test/java/com/gt8d/launcher/config/ConfigRepositoryTest.kt`
- `app/src/test/java/com/gt8d/launcher/config/SplitRatioTest.kt`
- create `app/src/test/java/com/gt8d/launcher/config/ScreenSetSidesTest.kt`
- その他、`ScreenSet` の型の変更でコンパイルが通らなくなるテストがあれば報告して止まる(勝手に増やさない)。

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.config.*' --tests 'com.gt8d.launcher.settings.*' --tests 'com.gt8d.launcher.sets.*' --tests 'com.gt8d.launcher.ui.home.*'`

## 仕様

### A. Model.kt
1. `@Serializable enum class Side { Left, Right }`。
2. `ScreenSet.left` の型を `Slot.App` → `Slot`。他の項目は変えない。
3. 拡張(同じファイル):
   - `val ScreenSet.panelSide: Side?`(右が Panel→Right、左が Panel→Left、どちらも App→null。両方 Panel は `isValid` で弾く前提で Left を返してよい)
   - `val ScreenSet.isValid: Boolean`(両方 Panel なら false)
   - `fun ScreenSet.slotAt(side: Side): Slot`、`fun ScreenSet.appPkgAt(side: Side): String?`(App なら pkg、Panel なら null)
   - `fun SplitRatio.mirrored(): SplitRatio`(R3_7↔R7_3、R4_6↔R6_4、R5_5→R5_5)
   - `fun ScreenSet.swapped(): ScreenSet` = `copy(left = right, right = left, ratio = ratio.mirrored(), appPairLabel = null)`
   - `fun SplitRatio.allowedFor(left: Slot, right: Slot): Boolean`(どちらかが Panel なら `this in SplitRatio.forPanel`、両方 App なら true)。**旧 `allowedFor(right: Slot)` は消す**。呼び出し元(SetsEdit・SetsScreen ほか)を2引数に直す(判定の結果は今と同じになるよう、左はそのセットの左を渡す)。
4. `const val CONFIG_VERSION = 3`。

### B. ConfigCodec.kt
1. `decode(json)`: `Json.parseToJsonElement` で読み、`version`(無ければ 1 扱い)が 3 未満なら、`screenSets` の各要素の `left` が JSON オブジェクトで `"type"` を持たなければ `"type":"com.gt8d.launcher.config.Slot.App"` を足してから `decodeFromJsonElement` する。版の扱い(未来の版は null、古い版は `version = CONFIG_VERSION` にする)は今と同じ。
2. 読んだ結果から `isValid == false`(両方 Panel)のセットを除く(`DiagLog` が使えれば理由を出す。使えなければ何もしない)。
3. `encode` は今のまま(版 3 では `left` にも `"type"` が付く)。

### C. ConfigRepository.kt(ファイルの移行を DataStore を作る前に行う)
1. `ConfigRepository` の初期化で、DataStore を作る**前に** `migrateFileIfNeeded()` を呼ぶ:
   - `file` が無い・読めない・JSON でない → 何もしない(今の破損の扱いに任せる)。
   - JSON の `version` が 3 以上 → 何もしない。
   - 3 未満 → ① バックアップ `File(file.parentFile, "config-v2-backup.json")`: 既にあって、その中身が `ConfigCodec.decode` で読めるなら作らない(上書きしない)。無い・読めないなら、一時ファイル `config-v2-backup.json.tmp` に元の文字列を書き → 読み直して元と同じか確かめ → `renameTo` でバックアップに。② ①でバックアップが確かにある(作れた、または既に正しいものがある)ときだけ、`ConfigCodec.decode` → `ConfigCodec.encode` した版 3 の文字列を `config.json.tmp` に書いて `renameTo` で `file` に置き換える。① が失敗したら ② をせず、ディスクは版 2 のまま(読み込みは codec が版 3 に読み替えるので動く)。
   - 例外は外に出さない(失敗は失敗として何もしない)。
2. テストのために、バックアップの書き込みを失敗させられる差し込み口が要るなら、コンストラクタに既定値付きの引数を足してよい(例 `backupWriter: (File, String) -> Unit = { f, s -> f.writeText(s) }`)。足したら報告する。

### D. 他のファイル(コンパイルを通すだけ。動きは変えない)
- `set.left.pkg` を使っている箇所は、`when (val l = set.left) { is Slot.App -> ...; Slot.Panel -> ... }` などで分ける。左が Panel の場合は、**例外を出さず**、その場所で一番無難な形(表示なら「パネル」、起動なら今の右パネルの経路に影響しない形)にする。左パネルを本当に扱うのは後の Task なので、凝らない。どう分けたかを箇所ごとに報告する。
- `SetsEdit.SetDraft` の `left: String?` はこの Task では変えない(`from(set)` で左が Panel のときは null にする)。

### E. テスト(足す。既存は弱めない)
1. `ScreenSetSidesTest`: `mirrored` の5通り。`ScreenSet("s", Slot.App("y"), Slot.Panel, SplitRatio.R6_4, "p").swapped()` == `ScreenSet("s", Slot.Panel, Slot.App("y"), SplitRatio.R4_6, null)`。2回 `swapped()` で左右と比率が元に戻る。`panelSide`(右 Panel→Right、左 Panel→Left、両方 App→null)。`isValid`(両方 Panel→false、それ以外 true)。`allowedFor`(左 Panel で R3_7→false・R4_6→true、右 Panel で R7_3→false、両方 App は5通りとも true)。`slotAt`・`appPkgAt`。
2. `ConfigCodecTest`: `decode_v2_leftWithoutType_becomesApp`(`{"version":2,"screenSets":[{"id":"s1","left":{"pkg":"a"},"right":{"type":"com.gt8d.launcher.config.Slot.Panel"}}], ...}` → `ScreenSet("s1", Slot.App("a"), Slot.Panel, ...)`、`version == 3`)、`roundTrip_leftPanel`(`ScreenSet("s", Slot.Panel, Slot.App("y"), SplitRatio.R4_6)`)、`decode_dropsBothPanelSets`(両方 Panel のセットだけ除かれ、他は残る)。既存の版 1 の読み込みテストは期待する版を 3 に。
3. `ConfigRepositoryTest`:
   - `migrateFromV2_writesBackupThenV3`: 版 2 の JSON を `file` に置いて `ConfigRepository` を作り、**`update` を呼ばずに**最初の設定を読む → バックアップファイルの中身が元の JSON と同じ、`file` の中身が `"version":3` を含み、`left` に `"type"` が付いている。
   - `migrate_secondLoad_keepsBackup`: 作り直しても(版 3 になっているので)バックアップの中身が変わらない。
   - `migrate_backupFails_keepsV2OnDisk`: バックアップの書き込みを失敗させると、`file` は版 2 の元の中身のまま、読んだ設定は版 3 のセットとして使える。
   - `migrate_corruptBackup_isRecreated`: 読めないバックアップが既にあれば作り直す。
   - 既存のテストで「移行後の version が 2」を期待するものは 3 に、「未来の版(3)を退避」するものは `CONFIG_VERSION + 1` にする(未来の版を拒否して退避する検証そのものは残す)。
4. `SplitRatioTest`: 1引数の呼び出しを2引数に(判定の内容は同じ)。
5. 既存の `settings.*`・`sets.*`・`ui.home.*` のテストがそのまま通ること。

## この Task でしないこと
- 左パネルの起動・表示・設定画面での選択(Task 2〜5)。`Defaults` の変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、D の箇所ごとの分け方、**決めたこと**。

## 補足(点検の反映)
1. 移行の順番: `createDataStore` の中の「前の Job の `cancelAndJoin`」の**後**、`DataStoreFactory.create` の**前**に `migrateFileIfNeeded()` を行う(前のインスタンスが止まってからファイルを書き換える)。
2. 差し込み口を足す場合は、`installed` を**最後の引数のまま**にする(既存の `ConfigRepository(file) { emptySet() }` の呼び出しを壊さない)。例: `ConfigRepository(file, backupWriter = ..., installed)` の順、または `backupWriter` を持つ別のコンストラクタ。
3. `ConfigCodecTest` の既存テストで、版 2 を往復比較するもの(54 行付近)は入力を `CONFIG_VERSION` に、版 3 を未来として拒否するもの(119 行付近)は `CONFIG_VERSION + 1` に変えてよい(拒否する検証は残す)。`ConfigCodecTest.kt` は変更の許可に入っている。
4. 既存のバックアップを残す条件は「`ConfigCodec.decode` で読め、**かつ生の JSON の `version` が 3 未満**」。版 3 以上のバックアップは作り直す。テスト `migrate_v3Backup_isRecreated` を足す。
5. `SetsEdit`・`SetsScreen` の比率の判定で、左が未選択(`left == null`)のときは `Slot.App("")`(仮のアプリ)を渡して判定する(右だけで決まる今の結果と同じになる)。`canSave` の `left != null` は残す。
