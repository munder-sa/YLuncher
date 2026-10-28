# Task 0b: gradle.properties を足してビルドを通す

前回(`tasks/001-project-base.md`)のビルドは、`gradle.properties` が無く `android.useAndroidX` が未設定のため `:app:checkDebugAarMetadata` で失敗した。続きをやる。

## 作る(すでに在れば内容が下と合っているか確認し、違えば直す)

`gradle.properties` をルートに作る。内容:

```
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

## 手順

1. 上のファイルを作る
2. `/home/munde/tools/gradle-run.sh test assembleDebug` を実行する
3. 失敗したら、原因を読んで、**書込みを許可された既存ファイルだけ**を直し、再実行する(最大2回)。許可されているのは `settings.gradle.kts`、`build.gradle.kts`、`gradle/libs.versions.toml`、`app/build.gradle.kts`、`app/src/main/AndroidManifest.xml`、`app/src/main/res/` 配下の `ic_launcher_*.xml` と `artwork_placeholder.xml` のみ。それ以外が必要なら、直さずに止まって理由を報告する
4. 2回で通らなければ、エラーの該当部分(先頭30行)を報告して止まる

## 守ること

- 依存のバージョンを勝手に下げない・上げない。バージョン起因なら、どれをどう変えたいかを報告して止まる
- 最後に、`BUILD SUCCESSFUL` か `BUILD FAILED` かと、変更したファイル名を1行ずつ報告する。確認していないことを「確認した」と書かない
