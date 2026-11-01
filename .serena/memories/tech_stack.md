# Tech stack
- Kotlin 2.1.0, AGP 8.7.3, Gradle wrapper, JDK 17(jvmTarget 17)。依存の版は gradle/libs.versions.toml(version catalog)。
- UI: Jetpack Compose(BOM 2025.01.00, material3, material-icons-extended), activity-compose。XML レイアウトは使わない。
- 永続化: androidx DataStore + kotlinx-serialization-json(設定は JSON 文字列として保存)。
- テスト: JUnit4 + Robolectric 4.14.1 + compose ui-test-junit4 + espresso-core + kotlinx-coroutines-test。すべて JVM の unit test(app/src/test)。androidTest は無い。
  - `forkEvery = 1`, `maxParallelForks = 4`。~/.m2 に android-all-instrumented があれば Robolectric をオフラインで使う(codex のサンドボックス対策)。
- 開発環境は WSL2。SDK/JDK は ~/tools(`~/tools/env.sh` を source)。端末への adb は Windows の `adb.exe` 経由。
- リリース署名は rootProject の keystore.properties(無ければ debug 鍵)。
