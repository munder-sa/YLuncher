# Task 031: 単体テストを並列で実行する

**最初に `tasks/RULES.md` を読み、従うこと。**

## 背景

単体テストはクラスごとに別の JVM で実行している(`forkEvery = 1`)が、1つずつ順番なので全体で4〜8分かかる。端末は16コアあるので、4つの JVM で手分けして実行する。

## 変更するファイル(これで全部)

- `app/build.gradle.kts` の `testOptions` ブロックを、次のとおりにする(`maxParallelForks` の1行を足すだけ。ほかの行は変えない)

```kotlin
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.forkEvery = 1
            it.maxParallelForks = 4
        }
    }
```

## 手順

1. 上のとおりに変える
2. **Gradle は実行しない**(検証はこちらで行う)。コマンドは使わない

## 報告

変更したファイル名と変更した行、**決めたこと**。
