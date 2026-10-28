# Task 1: 家での実験用コード(SpikeActivity。debug ビルド専用の捨てコード)

計画 `docs/superpowers/plans/2026-10-28-gt8d-launcher.md` の Task 1、仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §10.1 に従う。**この指示書の対象はコードを書くところまで**。端末への導入・実行・結果の記録は別の担当が行うので、ここでは行わない(`adb` を実行しない)。

## 作るファイル(これで全部。許可外の書込みは拒否され、そこで停止する)

すべて debug ソースセット(`app/src/debug/`)に置く。release ビルドに含めない。

- `app/src/debug/AndroidManifest.xml`
- `app/src/debug/java/com/gt8d/launcher/spike/SpikeActions.kt`
- `app/src/debug/java/com/gt8d/launcher/spike/SpikeOverlay.kt`
- `app/src/debug/java/com/gt8d/launcher/spike/SpikeReceiver.kt`
- `app/src/debug/java/com/gt8d/launcher/spike/SpikeActivity.kt`
- `app/src/debug/java/com/gt8d/launcher/spike/SpikePanelActivity.kt`

既存のファイルは変更しない。パッケージは `com.gt8d.launcher.spike`。ログはすべて `android.util.Log.i("GT8D-SPIKE", ...)`(失敗は `Log.w`)。

## 仕様

### AndroidManifest.xml(debug)

- `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />`
- `<queries>`: `MAIN` + `LAUNCHER` の intent(他アプリの起動 Intent を取るため)
- `<application>` の中に:
  - `SpikeActivity`: `exported=true`、`MAIN` + `LAUNCHER`、`resizeableActivity=true`
  - `SpikePanelActivity`: `exported=false`、`resizeableActivity=true`
  - `SpikeReceiver`: `exported=true`、アクション `com.gt8d.launcher.spike.RUN`

### SpikeActions.kt

`object SpikeActions { fun run(ctx: Context, step: String, extras: Bundle?) }` と、各実験の関数。`ctx` は `applicationContext` を使う。すべての `startActivity` は try/catch し、`START <対象> flags=0x... -> ok` または `-> FAIL <例外クラスと message>` を必ずログに出す(結果が分かるように)。

`step` の種類:

- `split`: extras `left`(パッケージ名)、`right`(パッケージ名、または文字列 `PANEL`)、`delay`(ms、既定 300)、`home_first`(boolean、既定 false)
  1. `home_first` が true なら HOME の Intent を先に起動し、さらに 500ms 待つ
  2. 左: `packageManager.getLaunchIntentForPackage(left)` に `FLAG_ACTIVITY_NEW_TASK` で起動
  3. `delay` ms 後(`Handler(Looper.getMainLooper()).postDelayed`)に右を起動。右がパッケージ名なら `getLaunchIntentForPackage(right)` に `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_LAUNCH_ADJACENT or FLAG_ACTIVITY_MULTIPLE_TASK`。右が `PANEL` なら `SpikePanelActivity` を同じ 3 フラグで起動
  4. 起動対象が見つからない(`getLaunchIntentForPackage` が null)ときは `FAIL not-installed <pkg>` をログに出す
- `home`: `Intent.ACTION_MAIN` + `Intent.CATEGORY_HOME` + `FLAG_ACTIVITY_NEW_TASK` を起動(結果をログ)
- `media_dump`: 実験④。`MediaSessionManager.getActiveSessions(ComponentName(ctx, com.gt8d.launcher.media.MediaListenerService::class.java))` の各セッションについて、1 セッション 1 行以上のログに、パッケージ名、`playbackState.state`、`playbackState.actions`(16進)、`customActions` の各 `action` と `name`、メタデータの title・artist・duration・アルバムアート有無、`playbackState.position` / `lastPositionUpdateTime` / `playbackSpeed` を出す。セッションが 0 件なら `media_dump: sessions=0`。通知アクセス未許可の `SecurityException` は `FAIL` としてログ
- `overlay_on` / `overlay_off`: `SpikeOverlay` を呼ぶ
- 未知の step は `Log.w` に出すだけ(例外を出さない)

### SpikeOverlay.kt

実験③。`object SpikeOverlay { fun show(ctx: Context); fun hide(ctx: Context) }`
- `ctx.getSystemService(WindowManager::class.java)` で、`TYPE_APPLICATION_OVERLAY` の窓を 2 枚追加する: 左端と右端、幅 8px、高さ `MATCH_PARENT`、`FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL`、`PixelFormat.TRANSLUCENT`、`gravity` は左端 `Gravity.START or Gravity.TOP`・右端 `Gravity.END or Gravity.TOP`
- 各窓の View は、背景に `0x4D8FE63B`(薄いライム色)。`OnTouchListener` で、`ACTION_DOWN` / `ACTION_MOVE` / `ACTION_UP` / `ACTION_CANCEL` を `touch side=<L|R> action=<名前> x=<rawX> y=<rawY> t=<eventTime> pointers=<pointerCount>` としてログに出す。`true` を返す
- 二重に追加しない(すでに表示中なら何もしない)。`hide` で 2 枚とも `removeView`。`SYSTEM_ALERT_WINDOW` が未許可で例外になったら `FAIL overlay <message>` をログ
- 窓は `applicationContext` の `WindowManager` に追加し、Activity が閉じても残るようにする

### SpikeReceiver.kt

`BroadcastReceiver`。受けたら `SpikeActions.run(context.applicationContext, intent.getStringExtra("step") ?: "", intent.extras)` を呼ぶ。`adb shell am broadcast -n com.gt8d.launcher/.spike.SpikeReceiver -a com.gt8d.launcher.spike.RUN --es step split --es left com.google.android.youtube --es right com.apple.android.music --ei delay 300` で動くこと。boolean は `--ez home_first true`。

### SpikeActivity.kt / SpikePanelActivity.kt

- `SpikeActivity`(`ComponentActivity`、Compose、`LauncherTheme`): ボタンを縦に並べるだけの画面。縦に収まらなければスクロール。ボタンはすべて最小 72dp。各ボタンは `SpikeActions.run` を呼ぶ:
  - 「① YouTube｜Apple Music」(`split`、left=`com.google.android.youtube`、right=`com.apple.android.music`)
  - 「② YouTube｜Spike パネル」(right=`PANEL`)
  - 「③ 帯を表示」/「帯を消す」(`overlay_on` / `overlay_off`)
  - 「④ メディア一覧」(`media_dump`)
  - 「⑤ YouTube｜Googleマップ」(right=`com.google.android.apps.maps`)
  - 「⑤ YouTube｜Spike パネル」(②と同じ)
  - 「⑤ HOME へ」(`home`)
  - 「通知アクセス設定を開く」(`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`)、「オーバーレイ許可設定を開く」(`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`、パッケージ URI つき)
- `SpikePanelActivity`: 画面中央に大きく「PANEL」とだけ表示する(黒背景、白文字、`fontSize` 96.sp)。他は何もしない

## 手順

1. 上記のファイルを作る
2. `/home/munde/tools/gradle-run.sh assembleDebug` を実行し、`BUILD SUCCESSFUL` になるまで直す(最大3回。通らなければ、エラーの先頭30行を報告して止まる)
3. `/home/munde/tools/gradle-run.sh test` を実行し、既存のテストが全部通ることを確認する

## 守ること

- 捨てコードでも、`ctx` はすべて `applicationContext`、`startActivity` の失敗は必ずログ、という点は守る(実験の結果がこのログに依存する)
- 走行状態による分岐、ライトテーマ、`com.android.launcher3` への操作を入れない
- 最後に、`assembleDebug` と `test` の結果、作ったファイル名を報告する。実機で動くことは確認していないので「確認した」と書かない
