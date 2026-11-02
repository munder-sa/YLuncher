最初に tasks/RULES.md を読み、従う。

# Task 123: 一瞬で閉じるアプリ(Car = com.ds.backcar)を、パネルの相手として記録しない

## 背景(実機で確認済み、2026-11-02)
- 端末の Car アプリ(com.ds.backcar、メーカーのシステムアプリ OneStepBackCar)は、押すと carsyso に「CarPlay から車の元の画面に戻る」を頼み、約 0.35 秒で自分から閉じる。
- 分割中のパネルから押すと、パネルの反対側に一瞬出て閉じ、元のアプリ(例 YouTube)に戻る。分割は保たれる(実機で確認)。
- しかし `SetRunner.panelLaunched("com.ds.backcar")` が `lastOpenedPair` を (com.ds.backcar, パネル) に書き換える。`lastOpenedPair` はリンクの行き先の判定(LinkEntryActivity → LinkRouter.detectFront の AppPanel(app=…))に使われるため、その後にリンクを開くと「Car | ブラウザ」の分割を作ろうとして Car を起動しかねない(=意図せず車の元の画面に戻る)。また `saver?.partnerChanged` で、その後の左右入れ替えの保存が飛ばされる。
- GPT Live(local.aibox.gptlive)などの自作アプリ・分割の行き先(112 の SplitRedirect)を持つアプリの動きは変えないこと。

## 変更
### app/src/main/java/com/gt8d/launcher/apps/OneShotApps.kt(新規)
```kotlin
package com.gt8d.launcher.apps

// 押すと用事を済ませて一瞬で閉じるアプリ。パネルの相手(分割の反対側のアプリ)として記録しない。
object OneShotApps {
    const val BACK_CAR = "com.ds.backcar" // 端末の Car(CarPlay から車の元の画面に戻る)
    private val packages = setOf(BACK_CAR)
    fun contains(pkg: String?): Boolean = pkg != null && pkg in packages
}
```

### app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt
- `panelLaunched(pkg)` の先頭で、`OneShotApps.contains(pkg)` なら `DiagLog.d("SetRunner", "panelLaunched skipped oneShot pkg=$pkg")` を出して return(lastOpenedPair も saver も変えない)。ほかは変えない。

### 起動のしかたは変えない
- パネルからの起動(隣に開く)、ホームからの起動、ドック、アプリ一覧での表示は今のまま(Car を隣に開いても分割が保たれることは実機で確認済み)。

### テスト(app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt に追加)
- `panelLaunched_oneShotApp_keepsPairAndSaverPartner`: パネルを含むセットが ready になった後、`panelLaunched("com.ds.backcar")` で `lastOpenedPair` が変わらない。その後にパネルの左右の入れ替えが起きても保存される(既存の入れ替え保存のテストの組み立てにならう。保存の確認が既存の仕組みで難しければ、lastOpenedPair の確認だけにして、報告に書く)。
- `panelLaunched_normalApp_stillUpdatesPair`: 既存のテストで同じことを確かめていれば追加不要(その名前を報告に書く)。
- `app/src/test/java/com/gt8d/launcher/apps/OneShotAppsTest.kt`(新規): com.ds.backcar は true、local.aibox.gptlive・com.openai.chatgpt・null は false。

## 作るファイル・変えるファイル(これで全部)
- create app/src/main/java/com/gt8d/launcher/apps/OneShotApps.kt
- create app/src/test/java/com/gt8d/launcher/apps/OneShotAppsTest.kt
- write app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt
- write app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt

## 使ってよいコマンド
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.sets.*' --tests 'com.gt8d.launcher.apps.*'`
