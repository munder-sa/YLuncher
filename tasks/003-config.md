# Task 2: 設定モデル・既定値・保存

計画 `docs/superpowers/plans/2026-10-28-gt8d-launcher.md` の Task 2、仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §5.4(データモデル)と §8(既定値)に従う。迷ったらその2つを読むこと。既存の Task 0 の成果物(`app/` と Gradle 設定)は、下で許可した範囲以外は変更しない。

## 作るファイル(これで全部。許可外の書込みは拒否され、そこで停止する)

- `app/src/main/java/com/gt8d/launcher/config/Model.kt`
- `app/src/main/java/com/gt8d/launcher/config/Defaults.kt`
- `app/src/main/java/com/gt8d/launcher/config/ConfigCodec.kt`
- `app/src/main/java/com/gt8d/launcher/config/ConfigRepository.kt`
- `app/src/test/java/com/gt8d/launcher/config/DefaultsTest.kt`
- `app/src/test/java/com/gt8d/launcher/config/ConfigCodecTest.kt`
- `app/src/test/java/com/gt8d/launcher/config/ConfigRepositoryTest.kt`

既存の更新が許可されているのは `gradle/libs.versions.toml` と `app/build.gradle.kts` だけ。テスト用に `org.jetbrains.kotlinx:kotlinx-coroutines-test`(バージョン 1.9.0)を `testImplementation` に足す。それ以外の依存は足さない。

## 仕様(コードの形)

パッケージは `com.gt8d.launcher.config`。

`Model.kt`: 仕様 §5.4 のデータクラスをそのまま。`LauncherConfig`、`ScreenSet(id, left: Slot.App, right: Slot)`、`Slot`(sealed。`Slot.App(pkg)`、`Slot.Panel`)、`LinkConfig`、`LinkMode`(InAppSplit, Chrome)、`GestureConfig(stripWidthPx=8, glow=true, longPressMs=1000)`。すべて `@Serializable`。`const val MAX_PANEL_APPS = 5`、`const val MAX_DOCK_APPS = 4`。

`Defaults.kt`:
- `object Defaults { fun build(installed: Set<String>): LauncherConfig }`
- `fun LauncherConfig.resolve(installed: Set<String>): LauncherConfig` … `homeApps` / `panelApps` / `dockApps` から未インストールを除外する。`screenSets` のスロットと `idleApp` は変えない(表示側で「アプリが見つかりません」にするため)。

既定値(GT8D 実機で確認済みのパッケージ名。定数にする):

| 名前 | パッケージ |
|---|---|
| Video | `com.zdj.videoplayer` |
| VLC | `org.videolan.vlc` |
| U-NEXT | `jp.unext.mediaplayer` |
| Prime Video | `com.amazon.avod.thirdpartyclient` |
| TVer | `jp.hamitv.hamiand1` |
| ABEMA | `tv.abema` |
| Apple Music | `com.apple.android.music` |
| NewPipe | `org.schabi.newpipe` |
| YouTube | `com.google.android.youtube` |
| Chrome | `com.android.chrome` |
| Googleマップ | `com.google.android.apps.maps` |

- screenSets: id は `set1`, `set2`, `set3`。(YouTube ／ Panel)、(YouTube ／ Apple Music)、(YouTube ／ Googleマップ)。インストール状況に関わらず3件とも入れる
- homeApps: Video、VLC、U-NEXT、Prime Video、TVer、ABEMA、Apple Music、NewPipe、YouTube、Chrome、Googleマップ(この順。未インストールは飛ばす)
- panelApps: Apple Music、YouTube、NewPipe、Googleマップ、Chrome(この順。未インストールは飛ばす)
- dockApps: NewPipe、Chrome、YouTube、Apple Music(この順。未インストールは飛ばす)
- idleApp: Apple Music(インストール状況に関わらず)
- link: `LinkConfig()` の既定(InAppSplit、`["accounts.google.com"]`)、gesture: `GestureConfig()` の既定

`ConfigCodec.kt`:
- `object ConfigCodec { fun encode(c: LauncherConfig): String; fun decode(json: String): LauncherConfig? }`
- kotlinx.serialization の Json。`encodeDefaults = true`。`decode` は壊れた JSON、または `version > 1` のとき `null` を返す(例外を投げない)

`ConfigRepository.kt`:
- `class ConfigRepository(file: File, installed: () -> Set<String>)`
- `val config: Flow<LauncherConfig>` … 保存値に `resolve(installed())` を適用して流す
- `suspend fun update(f: (LauncherConfig) -> LauncherConfig)`
- `suspend fun reset()` … 設定を `Defaults.build(installed())` に戻す。`lastSetIndex` は変えない
- `var lastSetIndex: Int` … 既定 0。**設定ファイルとは別のファイル**(同じディレクトリの `last_set_index.txt`)に保存する。Android の SharedPreferences は使わない(JVM テストで動かすため)。壊れた中身は 0 として扱う
- 保存は DataStore(`DataStoreFactory.create`)。Serializer の `defaultValue` は `Defaults.build(installed())`。`readFrom` で `ConfigCodec.decode` が null なら `CorruptionException` を投げる
- `corruptionHandler` は、壊れた(または未来の version の)ファイルを**同じディレクトリの `config.bak.json` にコピーして退避**してから、既定値を返す

## 手順(TDD。この順で)

1. 3つのテストファイルを作る。必須のテスト名(計画と同じ):
   - DefaultsTest: `build_allInstalled_matchesSpec`(セット3件の順と中身、home 11件の順、panel 5件の順、dock 4件の順、idleApp、link、gesture)、`build_skipsNotInstalled`(VLC を除いた installed で home に VLC が無い)、`resolve_dropsUninstalledFromLists`(home/panel/dock から消え、`screenSets` のスロットは残る)
   - ConfigCodecTest: `codec_roundTrip`(encode→decode で等価。`Slot.Panel` と `Slot.App` の両方を含める)、壊れた JSON と `"version":2` の JSON で `decode` が null
   - ConfigRepositoryTest(`kotlinx.coroutines.test.runTest`、`@get:Rule TemporaryFolder`): `load_corruptFile_fallsBackAndBacksUp`(壊れた JSON と `"version":2` の2ケースで、config が既定値になり `config.bak.json` が作られる)、`reset_restoresDefaults_keepsLastSetIndex`、`update_persists`(update → 新しい Repository で読み直して反映されている)
2. `/home/munde/tools/gradle-run.sh test` を実行し、**新しいテストが失敗する(コンパイルエラーを含む)ことを確認**する
3. 実装する。`Model.kt`、`Defaults.kt`、`ConfigCodec.kt`、`ConfigRepository.kt`
4. `/home/munde/tools/gradle-run.sh test` を実行し、すべて通るまで直す(最大3回。通らなければ、失敗したテスト名とエラーの先頭30行を報告して止まる)

## 守ること

- テストの期待値を、実装に合わせて弱めない。テストが仕様と食い違うと思ったら、直さずに報告して止まる
- Android フレームワーク(Context 等)に依存しない純粋な Kotlin にする(`android.*` を import しない)
- ライトテーマ、走行状態による分岐など、計画の Global Constraints に反するものを入れない
- 最後に、`test` の結果(実行件数と失敗件数)と、作った・変更したファイル名を報告する。確認していないことを「確認した」と書かない
