# Task 028: 単体テストをクラスごとに別の JVM で実行する

**最初に `tasks/RULES.md` を読み、従うこと。**

## 背景

全テストを1つの JVM で続けて流すと、Compose の画面テスト12件が毎回 `AppNotIdleException` で失敗する。同じテストを単独や少数で流すと必ず通る。テストのクラスごとに新しい JVM で実行して、テストの間に状態が残らないようにする。

## 変更するファイル(これで全部)

- `app/build.gradle.kts` の `testOptions` ブロックを、次のとおりにする(1行足すだけ。ほかの行は変えない)

```kotlin
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.forkEvery = 1 }
    }
```

## 手順

1. 上の1行を足す
2. **Gradle は実行しない**(検証はこちらで行う)。コマンドは使わない

## 報告

変更したファイル名と変更した行、**決めたこと**。
