# Task 1c: SpikePanelActivity に、Activity 自身から起動するボタンを足す(debug 専用の捨てコード)

分割の中にいる Activity を起動元にしたときの `FLAG_ACTIVITY_LAUNCH_ADJACENT` の挙動を確かめる。**コードを書くところまで**。`adb` を実行しない。

## 変更するファイル(これだけ。許可外の書込みは拒否され、そこで停止する)

- `app/src/debug/java/com/gt8d/launcher/spike/SpikePanelActivity.kt`

既存の表示(黒背景に白で大きな「PANEL」)は残す。その下に、縦に並べたボタンを足す(画面が狭くても使えるよう、各ボタンは最小 72dp 角でスクロール可)。ボタンはすべて **`this`(この Activity)から `startActivity`** する(`applicationContext` を使わない)。各ボタンは `Log.i("GT8D-SPIKE", "PANEL-BTN <名前> flags=0x... -> ok")`、例外は `Log.w(... -> FAIL <クラス名>: <message>)` を必ず出す。

- 「Maps 隣へ(NEW_TASK+ADJACENT+MULTIPLE_TASK)」: `getLaunchIntentForPackage("com.google.android.apps.maps")` に `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_LAUNCH_ADJACENT or FLAG_ACTIVITY_MULTIPLE_TASK`
- 「Maps 隣へ(NEW_TASK+ADJACENT)」: 同、`MULTIPLE_TASK` なし
- 「Apple Music 隣へ(NEW_TASK+ADJACENT+MULTIPLE_TASK)」: `com.apple.android.music` で同様

`getLaunchIntentForPackage` が null なら `FAIL not-installed <pkg>` をログに出す(クラッシュさせない)。パッケージの可視性は、debug の Manifest の `<queries>` で足りている。

## 手順

1. 上のファイルを変更する
2. `/home/munde/tools/gradle-run.sh assembleDebug` を実行し、`BUILD SUCCESSFUL` になるまで直す(最大3回。通らなければ、エラーの先頭30行を報告して止まる)
3. `/home/munde/tools/gradle-run.sh test` を実行し、既存のテストが全部通ることを確認する

## 守ること

- 走行状態による分岐、`com.android.launcher3` への操作を入れない
- 最後に、`assembleDebug` と `test` の結果と、変更したファイル名を報告する。実機で動くことは確認していないので「確認した」と書かない
