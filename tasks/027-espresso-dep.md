# Task 027: テストのコンパイルに espresso-core を足す(Task 026 の続き)

**最初に `tasks/RULES.md` を読み、従うこと。**

## 背景

Task 026 で作った `app/src/test/java/com/gt8d/launcher/TestApp.kt` が `androidx.test.espresso.IdlingPolicies` を使うが、espresso-core は compose の ui-test-junit4 経由で実行時にしか入っておらず、コンパイルで `Unresolved reference 'espresso'` になる。テストの依存に明示的に足す。

## 変更するファイル(これで全部)

- `gradle/libs.versions.toml`
  - `[versions]` に `espresso = "3.5.0"` を足す(この版は端末のキャッシュにある。別の版にしない)
  - `[libraries]` に `espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espresso" }` を足す
- `app/build.gradle.kts`
  - `testImplementation(libs.robolectric)` の次の行に `testImplementation(libs.espresso.core)` を足す

ほかの行は変えない。`TestApp.kt`・`robolectric.properties` も変えない。

## 手順

1. 上の2ファイルを変える
2. `/home/munde/tools/gradle-run.sh test` を**1回だけ**実行する(バックグラウンドに回さず、終わるまで待つ)。失敗したら、失敗したテスト名(またはコンパイルエラー)の先頭30行を報告して止まる。ほかのファイルは直さない

## 報告

`test` の結果(実行件数と失敗件数)、変更したファイル名、**決めたこと**。
