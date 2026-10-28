# Task 1d: 非公開 API(setLaunchWindowingMode)でフリーフォーム起動する実験コード(debug 専用の捨てコード)

公式 API では分割もフリーフォームも作れなかった(`docs/superpowers/spikes/home-spikes.md`)。非公開の `ActivityOptions.setLaunchWindowingMode(int)` をリフレクションで呼んで、フリーフォームで左右に並べられるかを試す。**コードを書くところまで**。`adb` を実行しない。

## 変更するファイル(これだけ。許可外の書込みは拒否され、そこで停止する)

- `app/src/debug/java/com/gt8d/launcher/spike/SpikeActions.kt`

既存の step と、ログの書式は変えない。ログは `Log.i("GT8D-SPIKE", ...)`(失敗は `Log.w`)。

## 足す step: `freeform_hidden`

extras は既存の `freeform` と同じ(`left`、`right`、`ratio`(既定 50)、`delay`(既定 300))に加えて、`mode`(整数、既定 5 = `WINDOWING_MODE_FREEFORM`)。

1. 最初に `Settings.Global.getString(contentResolver, "hidden_api_policy")` をログ(`hidden_api_policy=<値>`)
2. 左右の `Rect` は既存の `freeform` と同じ計算
3. 各起動で `ActivityOptions.makeBasic()` を作り、`setLaunchBounds(rect)` のあと、リフレクションで `ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(options, mode)` を呼ぶ。リフレクションの成否を `reflect setLaunchWindowingMode(<mode>) -> ok` または `-> FAIL <例外クラス>: <message>` とログ。失敗しても起動は続ける(`setLaunchBounds` だけの状態で起動)
4. 起動は既存の `freeform` と同じフラグ・同じ `START ... -> ok/FAIL` のログ形式。左 → `delay` ms 後に右
5. 起動から 1 秒後に `freeform_hidden bounds requested left=<Rect> right=<Rect> mode=<mode>` をログ

## 手順

1. 上のファイルを変更する
2. `/home/munde/tools/gradle-run.sh assembleDebug` を実行し、`BUILD SUCCESSFUL` になるまで直す(最大3回。通らなければ、エラーの先頭30行を報告して止まる)
3. `/home/munde/tools/gradle-run.sh test` を実行し、既存のテストが全部通ることを確認する

## 守ること

- リフレクションの失敗でクラッシュさせない(必ずログにして続ける)
- 走行状態による分岐、`com.android.launcher3` への操作を入れない
- 最後に、`assembleDebug` と `test` の結果と、変更したファイル名を報告する。実機で動くことは確認していないので「確認した」と書かない
