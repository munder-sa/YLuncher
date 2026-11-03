最初に tasks/RULES.md を読み、従う。

# Task 143: 再生位置の 500ms の更新で画面全体を組み立て直さない、シークバーのユーザー補助の通知を1秒単位に

## 背景(実機の perfetto で確認、2026-11-03)
- ホーム(歌詞オフでも)の1フレームが中央値 30〜38ms。メインスレッドで、組み立て直し(Recomposer)平均 6.4ms・77回/12秒、ユーザー補助向けの `checkForSemanticsChanges` 平均 6.8ms・`sendAccessibilitySemanticsStructureChangeEvents` 平均 5.4ms。端末ではユーザー補助サービスが常に2つ有効。Apple Music(View)は同じ場面でメインスレッドの動きの処理 0.5ms、ユーザー補助の処理なし。
- codex の分析: `PanelScreen.kt` の 148〜161 行で 500ms ごとに `positionMs` を更新し、175 行で `PanelState` を作るときに読むため、`PanelScreen` が 500ms ごとに再実行される。そのたびに 164〜168 行のアプリ情報(`catalog.info` → `AppCatalog.label` が PackageManager を毎回呼ぶ)とリストを作り直す。シークバー(Material3 `Slider`)の値も 500ms ごとに変わり、ユーザー補助の進み具合の通知が出る。

## 変えるファイル(これ以外は変えない)
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelContent.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/SeekBar.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/MediaCard.kt`
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeMiniPlayer.kt`
- `app/src/main/java/com/gt8d/launcher/apps/AppCatalog.kt`
- テスト: 既存の `app/src/test/java/com/gt8d/launcher/ui/home/HomeMiniPlayerTest.kt`・`ui/layout/LayoutMatrixTest.kt`・`ui/panel/MediaCardTest.kt`・`ui/panel/PanelContentTest.kt`・`ui/panel/PanelScreenTest.kt`・`apps/AppCatalogTest.kt`(下の「テスト」の範囲で)、新規 `ui/panel/SeekBarTest.kt`

## 変更
1. **再生位置は使う所でだけ読む**:
   - `PanelState.positionMs: Long` を `val position: () -> Long` に替える(既定値 `{ 0L }`)。`PanelScreen` は 500ms ごとに更新する `mutableLongStateOf` を持ち続けるが、**その値を `PanelScreen`・`PanelContent`・`HomeWideContent` の composition では読まない**(ラムダ `{ positionState.longValue }` を渡すだけ)。
   - `HomeMiniPlayer`・`MediaCard` の `positionMs: Long` 引数を `position: () -> Long` に替え、`SeekBar` に渡す。`SeekBar` の `positionMs: Long` も `position: () -> Long` に替え、**SeekBar の中でだけ**読む。
   - 歌詞(`LyricsPane`・`rememberLyricIndex`)は今どおり `NowPlaying` と自前の時計を使う(位置のラムダは渡さない)。
2. **シークバーの自動の進みは1秒単位**: SeekBar は、ドラッグ中でないとき、`derivedStateOf { position() / 1000L * 1000L }` の値を `Slider` の値と時間の表示に使う(ユーザー補助への進み具合の変化と再コンポーズが1秒に1回になる)。ドラッグ中・シーク後の動き、押せる範囲、`onSeek` の値、時間の表示の形式は今どおり。
3. **アプリ情報を毎回作り直さない**:
   - `PanelScreen` の `idleApp`・`panelApps`・`dockApps` を `remember(…)` で保持し、もとになる値(`config.idleApp`・`config.panelApps`・`config.dockMode`・`config.dockApps`・`frequent`・`installedPkgs`・`settings?.packageName`・`environment.revision`)が変わったときだけ作り直す。
   - `AppCatalog.label(pkg)` に、アイコンと同じ形のキャッシュを足す(既存の `iconCache` の消し方・作り直しの決まりがあれば、それと同じ時に消す)。
4. `PanelScreen` の composition で、ほかに 500ms ごとに変わる値を読んでいないか確かめ、あれば同じ形で下ろす(見つけたものは報告)。

## テスト
- 既存のテストで、`PanelState(positionMs = …)`・`HomeMiniPlayer(…, positionMs, …)`・`MediaCard(positionMs = …)`・`SeekBar(…, positionMs, …)` の呼び出しを、**値の渡し方だけ**ラムダに替えてよい(期待値・確かめる内容は変えない)。替えたファイルを報告する。
- 追加(`SeekBar` のテストがあるファイル、無ければ `app/src/test/java/com/gt8d/launcher/ui/panel/SeekBarTest.kt` を作ってよい。作ったら報告):
  - `autoProgressUpdatesPerSecond`: 位置のラムダが 0→400→900→1100 と変わっても、Slider の値(semantics の `ProgressBarRangeInfo.current`)は 0、0、0、1000
  - `dragShowsExactPosition`: ドラッグ中は今どおりの値(既存のドラッグのテストがあればそれで足りる)
- 追加(`PanelScreen` のテストがあるファイル): `positionTickDoesNotRecomposePanel`: 再生中の NowPlaying を流し、`mainClock` を 2 秒進めても、`HomeWideContent` の中の、位置を読まない部品(例: 時計の見出し)の再コンポーズの回数が増えない(数える仕掛けの方法は決めたことに書く。テスト専用の口を足してよい)
- `AppCatalog` に `labelIsCached`: 同じ pkg で2回呼んでも PackageManager の問い合わせは1回(Robolectric の `ShadowPackageManager` か、数えられる形で。方法は決めたことに書く)

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.panel.*' --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.apps.*'`
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.layout.*'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、替えたテストのファイル、見つけた他の 500ms ごとの読み取り、決めたこと(RULES.md 1)。
