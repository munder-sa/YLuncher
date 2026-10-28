# Task 1e: 純正のアプリペアをアクセシビリティで押す実験コード(debug 専用の捨てコード)

純正 Launcher3 のアプリペア(比率つきの分割のショートカット)を、アクセシビリティサービスがホーム画面からラベルで探してクリックできるかを試す。あわせて、自作パネルを純正の分割の選択で選べるように、ランチャーのアイコンを付ける。**コードを書くところまで**。`adb` を実行しない。

## 変更するファイル(これだけ。許可外の書込みは拒否され、そこで停止する)

- `app/src/debug/AndroidManifest.xml`
- `app/src/debug/res/xml/spike_accessibility.xml`
- `app/src/debug/java/com/gt8d/launcher/spike/SpikeActions.kt`
- `app/src/debug/java/com/gt8d/launcher/spike/SpikeAccessibilityService.kt`

既存の step とログの書式は変えない。ログは `Log.i("GT8D-SPIKE", ...)`(失敗は `Log.w`)。

## 仕様

### Manifest
- `SpikePanelActivity` を `exported=true` にし、`MAIN` + `LAUNCHER` の intent-filter と `android:label="GT8D Panel"` を付ける(純正のアプリ一覧・分割の選択に出すため)。`resizeableActivity=true` は維持
- `SpikeActivity` の設定は変えない

### spike_accessibility.xml
- `android:canRetrieveWindowContent="true"` に変更。`android:accessibilityEventTypes="typeWindowStateChanged"`、`android:accessibilityFlags="flagReportViewIds|flagIncludeNotImportantViews"`、`android:notificationTimeout="100"` を付ける。**タッチ探索(`flagRequestTouchExplorationMode`)は付けない**

### SpikeAccessibilityService
- 既存の `instance` の仕組みはそのまま。`onAccessibilityEvent` は空のまま(何もしない)

### SpikeActions に足す step(どちらもサービスが無ければ `FAIL <step> service-not-enabled`)

- `home_dump`: `performGlobalAction(GLOBAL_ACTION_HOME)` を呼び、1200ms 後に `rootInActiveWindow` を取得。ツリーを深さ優先でたどり、`text` か `contentDescription` が空でないノードを、1 ノード 1 行で `node text=<text> desc=<contentDescription> cls=<className> clickable=<bool> bounds=<boundsInScreen>` とログ(最大 300 行)。`rootInActiveWindow` が null なら `FAIL home_dump root-null`。続けて `windows`(全ウィンドウ)についても `window type=<type> title=<title> layer=<layer>` を1行ずつログ
- `click_label`: extras `label`(文字列、必須)、`wait`(ms、既定 1200)。`performGlobalAction(GLOBAL_ACTION_HOME)` のあと `wait` ms 待ち、`rootInActiveWindow` をたどって、`text` または `contentDescription` に `label` を**含む**ノードを探す(大文字小文字を区別しない)。見つかった候補をすべて `candidate text=... desc=... clickable=... bounds=...` とログ。最初の候補について、そのノードか、`clickable` な最も近い祖先に `performAction(ACTION_CLICK)` を行い、結果を `click_label result=<true|false> on=<text/desc>` とログ。候補が無ければ `FAIL click_label not-found <label>`
- 待ちは `Handler(Looper.getMainLooper()).postDelayed` で行う(メインスレッドを止めない)

## 手順

1. 上のファイルを変更する
2. `/home/munde/tools/gradle-run.sh assembleDebug` を実行し、`BUILD SUCCESSFUL` になるまで直す(最大3回。通らなければ、エラーの先頭30行を報告して止まる)
3. `/home/munde/tools/gradle-run.sh test` を実行し、既存のテストが全部通ることを確認する

## 守ること

- 読み取った画面の内容は logcat に出すだけ。ファイルや外部に保存・送信しない
- 走行状態による分岐、`com.android.launcher3` の設定変更・無効化を入れない(画面の項目をクリックするだけ)
- 最後に、`assembleDebug` と `test` の結果と、変更したファイル名を報告する。実機で動くことは確認していないので「確認した」と書かない
