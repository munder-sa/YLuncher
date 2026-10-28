# Task 026: 単体テストの環境を安定させる(アプリの起動処理を外す・待ち時間を10秒に)

**最初に `tasks/RULES.md` を読み、従うこと。**

## 背景

Robolectric のテストのたびに、本物の `LauncherApp.onCreate`(クラッシュ検知の記録、例外ハンドラーの継ぎ足し、10秒後の予約、`AppGraph.init`)が動き、テストの間に状態が残る。全テストを続けて流すと、Compose の画面テストがときどき `AppNotIdleException`(Compose did not get idle ... in 60 SECONDS)で1件60秒待って失敗する。単体テストでは素のアプリを使い、待ち時間の上限を10秒にする。

## 作成するファイル(これで全部。既存ファイルは変えない)

- `app/src/test/resources/robolectric.properties`
- `app/src/test/java/com/gt8d/launcher/TestApp.kt`

## 仕様

### robolectric.properties
```
application=com.gt8d.launcher.TestApp
```
(この1行だけ。`sdk` は各テストの `@Config(sdk = [35])` のままにする)

### TestApp.kt(パッケージ `com.gt8d.launcher`、テスト用ソース)
```kotlin
class TestApp : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        androidx.test.espresso.IdlingPolicies.setIdlingResourceTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
    }
}
```
- import は通常の形で書いてよい。ほかの処理は入れない

## 手順

1. 2つのファイルを作る
2. `/home/munde/tools/gradle-run.sh test` を**1回だけ**実行する(バックグラウンドに回さず、終わるまで待つ)。失敗したら、失敗したテスト名とエラーの先頭30行を報告して止まる。テストやほかのファイルは直さない

## 報告

`test` の結果(実行件数と失敗件数)、作成したファイル名、**決めたこと**。
