最初に `tasks/RULES.md` を読み、従う。

# Task 234: 分割中のパネルの上に出る帯を消す(パネルを画面いっぱいに描く)

## 背景(司令塔が実機 GT8D で確認済み)
- 分割中のパネル(`PanelActivity`)の最上部に、高さ 36px(24dp)・色 `#303030` の帯が出る。`#303030` は標準テーマ(Theme.Material)のウィンドウの背景色。
- `dumpsys activity top` で、分割中のパネルは `android:id/content` が `0,36-1152,1080`(上が 36px 押し下げ)、`android:id/statusBarBackground` が `0,0-1152,36`。ホーム(`HomeActivity`)は全画面で `content` が `0,0-1920,1080` になっていて帯は出ない。
- システムのステータスバーは高さ 36px で透明(アイコンは画面右上だけ)。YLuncher のコードに上の余白を入れる処理は無い。分割中だけ、Android が中身をステータスバーの下へ押し下げている。
- ユーザーと合意: **パネルに画面いっぱいに描かせて帯を消す**。システムのステータスバーそのものは隠さない。ホームは変えない。

## 変えること(`PanelActivity.kt` だけ)
- `onCreate` の `super.onCreate(savedInstanceState)` の直前で、androidx.activity の `enableEdgeToEdge` を呼ぶ。
  ```kotlin
  enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
  )
  ```
  (`import androidx.activity.SystemBarStyle`・`import androidx.activity.enableEdgeToEdge`。依存の追加は不要。activity-compose 1.9.3 に含まれる。)
- それ以外の処理・レイアウト・余白は変えない。

## テスト(`PanelActivityTest.kt` に 1 件追加。既存のテストは変えない)
- `edgeToEdge_contentNotPushedBelowStatusBar`: `Robolectric.buildActivity(PanelActivity::class.java).create().start().resume().visible()` の後、decor に上 36px のステータスバーの insets(`WindowInsets.Builder().setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 36, 0, 0))` など)を `dispatchApplyWindowInsets` で渡し、measure・layout してから、`findViewById<View>(android.R.id.content)` の上端(`top`)が 0 であること。
- **同じ手順で、`enableEdgeToEdge` を入れる前のコードでは上端が 36 になる(テストが落ちる)ことを先に確かめる**。Robolectric で insets を渡しても入れる前から 0 になる(差が出ない)場合は、テストの書き方を推測で変えずに止めて、3 点セットで報告する。

## 作る・変えるファイル(これ以外は変えない)
- `app/src/main/java/com/gt8d/launcher/ui/panel/PanelActivity.kt`
- `app/src/test/java/com/gt8d/launcher/ui/panel/PanelActivityTest.kt`

## 使ってよいコマンド
    /home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.panel.PanelActivityTest'
    /home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.panel.*'

## 報告
- 実行したコマンドと結果(件数・失敗数)。「入れる前は落ちる」を確かめた結果。変更の要約、「決めたこと」。
