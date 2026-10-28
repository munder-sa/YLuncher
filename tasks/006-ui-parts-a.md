# Task 6a: テーマ・メディアカード・アイコン一覧

計画 `docs/superpowers/plans/2026-10-28-gt8d-launcher.md` の Task 6 の前半。仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §4.4(パネル)と §9(見た目)も読むこと。Task 0・2・3・5 の成果物は、下で許可した範囲以外は変更しない。後半(Dock・PanelContent)は次の指示書で行うので、ここでは作らない。

## 作るファイル(これで全部。許可外の書込みは拒否され、そこで停止する)

- `app/src/main/java/com/gt8d/launcher/ui/theme/Theme.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelModels.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/MediaCard.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/IconGrid.kt`
- `app/src/test/java/com/gt8d/launcher/ui/theme/PaletteTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/MediaCardTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/IconGridTest.kt`

既存の更新が許可されているのは `app/src/main/java/com/gt8d/launcher/media/NowPlaying.kt` と `app/src/test/java/com/gt8d/launcher/media/NowPlayingTest.kt` だけ(下の「NowPlaying の修正」)。

## NowPlaying の修正(計画の抜けの補正)

`NowPlaying` は再生位置を持たないため、進行バーを描けない。次を足す:
- `NowPlaying` に `val positionMs: Long = 0L`、`val positionUpdatedAt: Long = 0L`、`val speed: Float = 1f`、**末尾に**追加(既定値つき。既存の呼び出しは壊さない)
- `SessionSnapshot.toNowPlaying()` で、この3項目を `SessionSnapshot` の同名の値から入れる
- `fun NowPlaying.positionAt(nowMs: Long): Long` … 既存の `positionAt(SessionSnapshot, Long)` と同じ補間と丸め(`playing` なら `speed` で補間。`durationMs` が null のときは 0 以上に丸めるだけ)。`nowMs` は `SystemClock.elapsedRealtime()` 基準(`positionUpdatedAt` と同じ時間軸)
- `NowPlayingTest.kt` に `nowPlaying_positionAt_interpolates` を足す(再生中、speed 1、更新から 3000ms、position 1000、duration 10000 → 4000。停止中は補間しない)。既存のテストは変更しない

## 仕様

パッケージ: `com.gt8d.launcher.ui.theme` / `com.gt8d.launcher.ui.panel`。

`Theme.kt`:
```kotlin
object Dims { val MinTouch = 72.dp; val BodyText = 20.sp }
object Palette {
    val Background0 = Color(0xFF0B0B0F); val Background1 = Color(0xFF17171D)
    val Panel = Color(0xFF2E2E38); val Accent = Color(0xFF8FE63B)
}
@Composable fun LauncherTheme(content: @Composable () -> Unit)
```
Material3 の `darkColorScheme` を `Palette` で組む(背景は Background0/Background1、surface はパネル、primary は Accent)。**ダーク固定**。ライトテーマ、`isSystemInDarkTheme()` による分岐は作らない。

`PanelModels.kt`:
```kotlin
data class AppInfo(val pkg: String, val label: String, val icon: ImageBitmap?, val installed: Boolean)
enum class MediaAction { Play, Pause, Next, Prev, Favorite }
data class DockStatus(val wifiOn: Boolean, val bluetoothOn: Boolean?)  // null = Bluetooth 許可なし(アイコン非表示)
```

`MediaCard.kt`:
```kotlin
@Composable fun MediaCard(
    now: NowPlaying?, idleApp: AppInfo?, positionMs: Long,
    onAction: (MediaAction) -> Unit, onSeek: (Long) -> Unit, onLaunch: (String) -> Unit,
)
```
`positionMs` は呼び出し側が計算して渡す(この関数内では時刻を読まない)。計画のシグネチャに `positionMs` と `onSeek` を足したもの。
- `now == null`(停止中): `idleApp` のアイコンとラベル(タップで `onLaunch(idleApp.pkg)`)と、**特大のデジタル時計**(`fontSize` 96.sp 以上、`HH:mm`)。`idleApp == null` なら時計だけ。`idleApp.installed == false` のときはラベルの代わりに「アプリが見つかりません」
- `now != null`(再生中・一時停止中): ジャケット(`artwork` が null なら `R.drawable.artwork_placeholder`)、曲名、アーティスト(null なら出さない)、前へ・再生/一時停止・次へのボタン。再生中は一時停止ボタン(押すと `Pause`)、停止中は再生ボタン(押すと `Play`)。☆は `hasFavorite == true` のときだけ出す(押すと `Favorite`)。`durationMs != null` のときだけ、進行バー(Material3 `Slider`。操作を離したときに `onSeek(ms)`)と、経過 `m:ss`・残り `-m:ss` を出す。`durationMs == null` のときは進行バーも時間表示も出さない
- 進行バーとボタンは Accent 色。ボタンは最小 72dp 角、本文は 20sp 以上
- アイコンは Material Icons Extended: `SkipPrevious`、`PlayArrow`、`Pause`、`SkipNext`、`StarBorder`
- testTag(テストで使う。この名前で付ける): `clock`、`idle_app`、`artwork`、`title`、`artist`、`progress`、`btn_prev`、`btn_playpause`、`btn_next`、`btn_favorite`

`IconGrid.kt`:
```kotlin
@Composable fun IconGrid(apps: List<AppInfo>, columns: Int, onLaunch: (String) -> Unit, onAdd: (() -> Unit)?)
```
- 各マスはアイコン(`icon` が null なら `label` の先頭1文字を円形に)とラベル。タップで `onLaunch(pkg)`。testTag は `icon_<pkg>`
- `installed == false` のマスは、アイコンをグレーアウトして「アプリが見つかりません」を出し、タップしても `onLaunch` を呼ばない。testTag は `missing_<pkg>`
- `onAdd != null` のとき、末尾に「＋」のマス(testTag `icon_add`、タップで `onAdd()`)
- 各マスは最小 72dp 角、ラベルは 20sp 以上。1画面内に収まる固定レイアウト(スクロールなしの `Grid` 相当を `Column`/`Row` で。`LazyVerticalGrid` は使わない)

## 手順(TDD)

1. テストを書く。`@RunWith(RobolectricTestRunner::class)` と `@Config(sdk = [35])`、`@get:Rule val rule = createComposeRule()`、`rule.setContent { LauncherTheme { ... } }`。必須のテスト名:
   - PaletteTest: `palette_matchesDesign`(4色が上の値と一致。Robolectric 不要)
   - MediaCardTest: `mediaCard_idle_showsClockAndIdleApp`(`now=null`: `clock` と idleApp のラベルがある)、`mediaCard_playing_showsTitleArtistControls`(曲名・アーティスト・前/再生/次がある。`hasFavorite=false` で `btn_favorite` が存在しない。`hasFavorite=true` では存在する)、`mediaCard_unknownDuration_noProgressBar`(`durationMs=null` で `progress` が存在しない。`durationMs` があれば存在する)、`mediaCard_playPause_sendsActions`(再生中の `btn_playpause` クリックで `Pause`、停止中で `Play`)
   - IconGridTest: `slot_missingApp_showsPlaceholder`(`installed=false` で「アプリが見つかりません」が出て、クリックしても `onLaunch` が呼ばれない)、`grid_launchesInstalledApp`(`installed=true` のクリックで `onLaunch(pkg)`)、`grid_addCell_shownOnlyWhenOnAdd`
   - NowPlayingTest: `nowPlaying_positionAt_interpolates`
2. `/home/munde/tools/gradle-run.sh test` を実行し、新しいテストが失敗する(コンパイルエラーを含む)ことを確認する
3. 実装する
4. `/home/munde/tools/gradle-run.sh test assembleDebug` を実行し、すべて通るまで直す(最大3回。通らなければ、失敗したテスト名とエラーの先頭30行を報告して止まる)。Robolectric の初回はランタイムの取得で数分かかることがある

## 守ること

- テストの期待値を、実装に合わせて弱めない。仕様と食い違うと思ったら、直さずに報告して止まる
- 重いエフェクト(ぼかし、`Modifier.blur` 等)、走行状態による分岐、ライトテーマを入れない
- 最後に、`test assembleDebug` の結果(実行件数と失敗件数)と、作った・変更したファイル名を報告する。確認していないこと(実機での見た目)を「確認した」と書かない
