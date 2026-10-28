# Task 1f: メーカーの分割入口(com.sd.factoryconfig/.SplitActivity)を自作アプリから呼ぶ実験コード(debug 専用の捨てコード)

シェル(adb)からは、`com.sd.factoryconfig/.SplitActivity` に extras を付けて起動すると比率つきの本物の分割が開くことを確認した(`docs/superpowers/spikes/vendor-split-entry.md`)。第三者アプリ(このアプリ)から呼んでも動くかを試すためのコードを足す。**コードを書くところまで**。`adb` を実行しない。

## 変更するファイル(これだけ。許可外の書込みは拒否され、そこで停止する)

- `app/src/debug/AndroidManifest.xml`
- `app/src/debug/java/com/gt8d/launcher/spike/SpikeActions.kt`

既存の step とログの書式は変えない。ログは `Log.i("GT8D-SPIKE", ...)`(失敗は `Log.w`)。

## 仕様

### Manifest
- 既存の `<queries>` に `<package android:name="com.sd.factoryconfig" />` を足す(パッケージの可視性のため)

### SpikeActions に足す step: `vendor_split`

extras: `left`(パッケージ名、必須)、`right`(パッケージ名、必須)、`percent`(文字列、既定 `"1:1"`。`1:1`/`2:3`/`3:2`/`3:7`/`7:3` のみ受け付け、それ以外は `FAIL vendor_split bad-percent <値>` を出して何もしない)

1. `Intent().setComponent(ComponentName("com.sd.factoryconfig", "com.sd.factoryconfig.SplitActivity"))` を作る
2. `packageManager.resolveActivity(intent, 0)` の結果を `vendor_split resolve=<null か ActivityInfo の name と exported>` とログ。null なら `FAIL vendor_split entry-missing` を出して終わる
3. extras: `putExtra("split_shortcur_package", "$left|$right")`(綴りは原文のまま `shortcur`)、`putExtra("split_percent", percent)`。フラグ `FLAG_ACTIVITY_NEW_TASK`
4. 既存の `startSafe` と同じ形式で `START vendor_split ... -> ok/FAIL` をログ(例外を握りつぶさない)

## 手順

1. 上のファイルを変更する
2. `/home/munde/tools/gradle-run.sh assembleDebug` を実行し、`BUILD SUCCESSFUL` になるまで直す(最大3回。通らなければ、エラーの先頭30行を報告して止まる)
3. `/home/munde/tools/gradle-run.sh test` を実行し、既存のテストが全部通ることを確認する

## 守ること

- `com.sd.factoryconfig` の他の画面(工場設定)を開かない。`SplitActivity` だけを呼ぶ
- 走行状態による分岐、`com.android.launcher3` への操作を入れない
- 最後に、`assembleDebug` と `test` の結果と、変更したファイル名を報告する。実機で動くことは確認していないので「確認した」と書かない
