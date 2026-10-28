# Task 0: プロジェクト土台

計画: `docs/superpowers/plans/2026-10-28-gt8d-launcher.md` の Task 0。迷ったら計画と、`design/assets/README.md` を読むこと。

## 前提(すでに在るもの。変更しない)

- `gradlew` / `gradlew.bat` / `gradle/wrapper/*`(Gradle 8.11.1)。作り直さない
- `design/assets/` の元データ(読むだけ)
- JDK 17 と Android SDK は用意済み。Gradle は必ず `/home/munde/tools/gradle-run.sh <引数>` で実行する(環境変数を整えて `./gradlew` を呼ぶ)。`./gradlew` を直接呼ばない

## 作るもの(これ以外のファイルは作らない・変更しない。許可外の書込みは拒否され、そこで停止する)

- `settings.gradle.kts`、`build.gradle.kts`、`gradle/libs.versions.toml`、`app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`(Activity はまだ無い。`<application>` のみ)
- `app/src/main/java/com/gt8d/launcher/LauncherApp.kt`(`class LauncherApp : Application()`、中身は空)
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`(adaptive-icon。background・foreground・monochrome を指定)
- `app/src/main/res/drawable/ic_launcher_background.xml`、`ic_launcher_foreground.xml`、`ic_launcher_monochrome.xml`、`artwork_placeholder.xml`
- `app/src/main/res/drawable-nodpi/wallpaper.png`(`design/assets/wallpaper.png` のコピー)
- `app/src/test/java/com/gt8d/launcher/SmokeTest.kt`

以上で全部。`strings.xml`、`themes.xml`、`proguard-rules.pro` などは作らない(Manifest の `android:label` は文字列リテラル `GT8D Launcher`、テーマ指定は不要)。`wallpaper.png` は下の `cp` で置く(Write では作れない)。

## 制約

- minSdk 35 / targetSdk 35 / compileSdk 35、JDK 17、applicationId・パッケージ `com.gt8d.launcher`
- 依存はバージョンカタログ(`gradle/libs.versions.toml`)で管理する。目安: Kotlin 2.1 以上、AGP 8.7 以上、Compose BOM 2025.01 以上、DataStore 1.1、kotlinx.serialization 1.7、Robolectric 4.14。Kotlin 2.x なので Compose は `org.jetbrains.kotlin.plugin.compose` プラグインを使う
- 依存に入れるもの: compose BOM、material3、material-icons-extended、activity-compose、datastore(preferences ではなく core でよい)、kotlinx-serialization-json、junit4、robolectric、compose ui-test-junit4 と ui-test-manifest(testImplementation)
- `testOptions { unitTests.isIncludeAndroidResources = true }`
- 署名: ルートの `keystore.properties`(`storeFile` はリポジトリ外の絶対パス)があれば release に適用し、無ければ debug 署名。`keystore.properties` 自体は作らない
- ダーク固定。ライトテーマは作らない
- `com.android.launcher3` に触れない

## 素材の変換(手で書く)

SVG → VectorDrawable(`android:viewportWidth/Height` は 108、`artwork_placeholder` は 1024 のまま、`android:width/height` は dp 指定)。

- `icon_background.svg` → 縦のグラデーション(`#17171D` → `#0B0B0F`)。VectorDrawable では `aapt:attr name="android:fillColor"` に `<gradient>` を入れる
- `icon_foreground.svg` → 2つの角丸パネルと2つのグリフ。SVG の `transform` は `<group android:translateX/Y android:scaleX/Y>` に置き換える
- `icon_monochrome.svg` → **単一パス・`android:fillType="evenOdd"`**。`mask` や `clip` は使わない(グリフの抜けが消える)。`pathData` は SVG の `d` をそのまま使う
- `artwork_placeholder.svg` → 背景(グラデーション)、角丸タイル、音符

## 手順

1. 上記のファイルを作る
2. `mkdir -p {ROOT}/app/src/main/res/drawable-nodpi` のあと、`cp {ROOT}/design/assets/wallpaper.png {ROOT}/app/src/main/res/drawable-nodpi/wallpaper.png` を実行する
3. `SmokeTest.smoke_runs()` を `assertEquals(2, 1 + 1)` で作る
4. `/home/munde/tools/gradle-run.sh test assembleDebug` を実行し、`BUILD SUCCESSFUL` になるまで直す(2回まで。それでも通らなければ、原因を報告して止まる)

## 完了条件

- `BUILD SUCCESSFUL`
- 上の「作るもの」以外のファイルが増えていない(`app/build`、`.gradle`、`.kotlin` を除く)
- 最後に、作ったファイルの一覧と、`test assembleDebug` の結果を1行ずつ報告する。確認していないことを「確認した」と書かない
