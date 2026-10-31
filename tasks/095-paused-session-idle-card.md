# Task 095: 一時停止中の曲を出し、セッションが無いときは小さな待ち受けのカードにする(v1.6 B5)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
v1.6 設計書 `docs/superpowers/specs/2026-10-31-v1.6-ux-design.md` の §4 B5 と §2、計画 `docs/superpowers/plans/2026-10-31-v1.6-ux.md` の Task 095 と Review Focus 3 を読むこと(この指示書が優先)。
- 今の `pickSession`(`media/NowPlaying.kt`)は、再生中が無く `lastActivePkg`(このプロセスで最後に再生していたアプリ)も無いと null を返す。起動し直した直後は、一時停止中のセッションがあってもミニプレイヤーが空になる。
- セッションが無いときは、ホームのミニプレイヤー(`ui/home/HomeMiniPlayer.kt`)は真ん中に 160dp の大きなアイコンと「タップで開く」、パネル(`ui/panel/MediaCard.kt` の `IdleMediaContent`)は 48dp のアイコンと名前の行。何もない理由と次の操作が分かりにくい。

## 変更するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/media/NowPlaying.kt`
- `app/src/main/java/com/gt8d/launcher/media/MediaWatcher.kt`(`paused` を埋めるだけ)
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeMiniPlayer.kt`
- `app/src/main/java/com/gt8d/launcher/ui/panel/MediaCard.kt`(`IdleMediaContent` だけ)
- `app/src/test/java/com/gt8d/launcher/media/NowPlayingTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/HomeMiniPlayerTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/MediaCardTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/layout/LayoutMatrixTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.media.*' --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.ui.panel.*' --tests 'com.gt8d.launcher.ui.layout.*'`

## 仕様
1. `SessionSnapshot` に `val paused: Boolean = false` を足す。`MediaWatcher.buildSnapshot` で `state?.state == PlaybackState.STATE_PAUSED`。
2. `pickSession(sessions, lastActivePkg)` の規則(上から順に):
   1. 再生中があれば、今と同じ(`lastActivePkg` のものを優先、無ければ最初)。
   2. `lastActivePkg` のセッションがあれば、今と同じくそれ(状態を問わない。曲の切り替えの間の BUFFERING などで消えないように)。
   3. それも無ければ、**一時停止中(`paused`)で曲名が空でない**(`title` が null・空白でない)セッションのうち、`positionUpdatedAt` が一番新しいもの(同じなら一覧で先のもの)。
   4. どれも無ければ null。停止(STOPPED)・NONE・曲名の無いセッションは、3 では選ばない。
   - 計画の Review Focus 3 の「停止・曲名の無いものを出さない」は 3 の規則のこと。2(このプロセスで最後に再生していたアプリ)は、今どおり状態を問わない(意図した例外)。
   - `MediaWatcher` で 3 により選ばれたときは、`lastActivePkg` を変えない(今どおり再生中のときだけ更新)。
3. ホームのミニプレイヤー(`HomeMiniPlayer`)の、`now == null` で `idleApp != null` のとき(今の `player_idle_app` を置き換える):
   - **上寄せ**(今の真ん中寄せ・160dp をやめる)。1 行目: アプリのアイコン 72dp(今の `AppImage`)+ 16dp + 縦に「<アプリ名>」(`Dims.BodyText`、白、1 行)と「再生情報はありません」(`Dims.BodyText`、`Palette.Muted`、1 行)。
   - その下に 24dp 空けて「<アプリ名>を開く」のボタン(高さ・最小幅 `Dims.MinTouch` 以上、白い輪郭 2dp・白い文字・角丸 36dp、横は中身の幅、左寄せ、`testTag("player_idle_open")`)。押すと `onLaunch(idleApp.pkg)`。名前が長いときは「<アプリ名>を開く」を1行で省略(…)。
   - アプリが入っていない(`installed == false`)ときは、2 行目を「アプリが見つかりません」にし、ボタンは出さない。
   - 外側の箱は `testTag("player_idle_app")` のまま(場所を確かめる既存のテストのため)。箱全体のクリックはやめる(押せるのはボタンだけ)。
4. パネルの `MediaCard` の `IdleMediaContent`(`idleApp != null` のとき):
   - 1 行の横並び: アイコン 72dp + 12dp + 縦に「<アプリ名>」と「再生情報はありません」(`Palette.Muted`)(幅を取る列、1 行ずつ省略)+ 8dp + 「開く」のボタン(高さ・最小幅 `Dims.MinTouch`、白い輪郭・白い文字、`contentDescription = "<アプリ名>を開く"`、`testTag("idle_open")`)。押すと `onLaunch(idleApp.pkg)`。
   - 今の `showName`(内側の 8dp の余白を引いた `maxWidth >= 300.dp`)が false のときは、真ん中の列を出さず、アイコンとボタンだけ(境は今のまま)。
   - アプリが入っていないときは、2 行目を「アプリが見つかりません」にし、ボタンは出さない。狭いとき(`showName` が false)でも、入っていなければ列を出して「アプリが見つかりません」を見せる(今の `showName || !installed` と同じ)。
   - 設計書の「<アプリ名>を開く」は、パネルでは名前が横に出ているので、見える文字を「開く」にし、読み上げ(`contentDescription`)を「<アプリ名>を開く」にする(意図した違い)。
   - 今の `testTag("idle_app")` は行の外側に残す。行全体のクリックはやめる。
5. `idleApp == null` のときは、今と同じ(何も出さない)。再生情報があるとき(`now != null`)の見た目は変えない。

## テスト(足す。既存は弱めない。「タップで開く」・行全体のクリックを確かめていた既存のテストは、ボタンを押す形に置き換えて報告する)
- `NowPlayingTest`(`pickSession`):
  - 再生中が無く `lastActivePkg` も無い → 一時停止中で曲名のあるもの。2 つあれば `positionUpdatedAt` が新しい方。同じなら先の方。
  - 一時停止中でも曲名が null・空白 → 選ばない(null)。停止(`playing=false, paused=false`)だけ → null。
  - `lastActivePkg` のセッションが停止中・BUFFERING 相当(`playing=false, paused=false`)でも、それを選ぶ(今どおり)。再生中があれば再生中を選ぶ(今どおり)。
  - `lastActivePkg` はあるが、そのセッションがもう無い → 一時停止中の候補へ進む。
  - 新しい停止中と古い一時停止中 → 古い一時停止中を選ぶ。曲名が空文字 `""` の一時停止中は選ばない。
- `HomeMiniPlayerTest`: 待ち受けで「再生情報はありません」と `player_idle_open`(高さ 72dp 以上)が出て、押すと `onLaunch` が1回。アイコンが箱の上寄せ(箱の上端から 40dp の余白の位置)。入っていないと「アプリが見つかりません」でボタンが無い。
- `MediaCardTest`: 待ち受けで「再生情報はありません」と `idle_open`(72dp 以上)、押すと `onLaunch`。境の前後(内側の幅 299dp・300dp)で名前の列の有無が切り替わり、ボタンは残る。入っていないとボタンが無く、狭くても「アプリが見つかりません」が出る。入っていても外側(`idle_app`)にクリックの動作が無い。
- `HomeMiniPlayerTest` にも: 入っていても外側(`player_idle_app`)にクリックの動作が無い。長い名前(例 40 文字)・文字の倍率 1.3 で、名前と「<アプリ名>を開く」が1行で省略され、箱の内側に収まる。
- `LayoutMatrixTest`: 既存の組み合わせ(画面の大きさ・左右・狭い/広い)で、待ち受けのカードの部品(`player_idle_open`・`idle_open`)が画面の中に収まり、他の部品と重ならない。今の広いホームのスクロールしない所の例外の一覧(`listOf("player_idle_app", "permission_banner")`)は、押せる部品の `player_idle_open` に置き換える(またはそれを足す)。カードの中のアイコン・文字・ボタン同士も重ならないこと。

## この Task でしないこと
- 再生中の見た目・流れる曲名・シーク(段階 C)。git のコミット等。

## 報告
変更点、テストの結果(件数)、置き換えた既存テスト、**決めたこと**。
