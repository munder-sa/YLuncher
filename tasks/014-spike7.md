# Task 1g: Spike 画面に HOME の入口を足す(debug 専用の捨てコード)

自作アプリを既定のホームにしたままメーカーの分割入口が動くかを試すため、`SpikeActivity` を HOME として選べるようにする。**コードを書くところまで**。`adb` を実行しない。

## 変更するファイル(これだけ。許可外の書込みは拒否され、そこで停止する)

- `app/src/debug/AndroidManifest.xml`

## 仕様

- `SpikeActivity` に、既存の intent-filter(MAIN + LAUNCHER)とは**別の** intent-filter を1つ足す: `action MAIN`、`category HOME`、`category DEFAULT`
- `SpikeActivity` に `android:launchMode="singleTask"` を付ける(HOME として複数起動しないため)
- 他の Activity・サービスは変えない

## 手順

1. 上のファイルを変更する
2. `/home/munde/tools/gradle-run.sh assembleDebug` を実行し、`BUILD SUCCESSFUL` になるまで直す(最大3回。通らなければ、エラーの先頭30行を報告して止まる)
3. `/home/munde/tools/gradle-run.sh test` を実行し、既存のテストが全部通ることを確認する

## 守ること

- `com.android.launcher3` への操作・設定変更を入れない
- 最後に、`assembleDebug` と `test` の結果と、変更したファイル名を報告する。実機で動くことは確認していないので「確認した」と書かない
