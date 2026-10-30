# Task 049: 内蔵ブラウザの上のバーを「サイト名だけ」にし、文字を見やすくする

最初に `tasks/RULES.md` を読み、従うこと。仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` §4.7・§9(見た目のルール)に従う。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

## 背景(ユーザーの指摘、2026-10-31)
上のバーの題名が「Android (オペレーティングシステム) - Wikipedia」のようにページ名になっている。**サイト名だけ**(例「Wikipedia」)でよい。題名の文字色も見にくい。

## 作成・変更するファイル(これで全部)
- `M/link/BrowserPane.kt`
- 新規 `M/link/SiteLabel.kt`(純粋な Kotlin)
- 新規 `T/link/SiteLabelTest.kt`
- `T/link/BrowserPaneTest.kt`(テストを足すだけ。既存のテストは変えない)

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.link.*`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様
### SiteLabel.kt
```kotlin
object SiteLabel {
    // 表示する名前を決める。siteName(ページの og:site_name)が空でなければ、前後の空白を除いてそれ。
    // 無ければ url のホスト名から先頭の "www." を除いたもの(例 "ja.wikipedia.org")。ホスト名も取れなければ空文字。
    fun label(siteName: String?, url: String?): String
    // evaluateJavascript の戻り値(JSON の文字列。例 "\"Wikipedia\""、"null")を素の文字列に直す。null・"null"・空なら null
    fun parseJsString(raw: String?): String?
}
```
### BrowserPane.kt
- 題名に出すのは `SiteLabel.label(siteName, 表示中の URL)`。ページの題名(`onReceivedTitle`)は使わない。
- `siteName` は、ページの読み込みが終わったとき(`WebViewClient.onPageFinished`)に
  `view.evaluateJavascript("(function(){var m=document.querySelector('meta[property=\"og:site_name\"]');return m?m.content:null;})()")` で取り、
  `SiteLabel.parseJsString` で直して入れる。新しいページの読み込みが始まったら(`onPageStarted`)null に戻す。
- 題名の文字: 色は `Color.White`、太さ `FontWeight.SemiBold`、大きさ `Dims.BodyText`、1行・省略記号(今のまま)。
- ボタン(戻る/Chromeで開き直す/閉じる)の文字: 色は `Palette.Accent`、太さ `FontWeight.SemiBold`、大きさ `Dims.BodyText`。72dp の大きさは今のまま。
- バーの背景は今の `Palette.Panel` のまま。ほかは変えない。

## テスト(先に書き、修正前に失敗することを確かめる)
- `T/link/SiteLabelTest.kt`: `siteName_wins`(" Wikipedia " → "Wikipedia")、`noSiteName_usesHostWithoutWww`("https://www.example.com/a" → "example.com")、`subdomainKept`("https://ja.wikipedia.org/wiki/X" → "ja.wikipedia.org")、`badUrl_empty`、`parseJsString_cases`("\"Wikipedia\""→"Wikipedia"、"null"→null、"\"\""→null、null→null、エスケープを含む "\"A \\\"B\\\"\"" → "A \"B\"")。
- `T/link/BrowserPaneTest.kt` に `title_showsHostBeforeSiteName`(読み込み直後の題名がホスト名。`example.com` など)。JavaScript の結果は Robolectric で作れないので、そこは確かめなくてよい(報告に書く)。

## 手順(TDD)
1. テストを書く。2. 修正前に失敗することを確かめる。3. 実装する。4. 対象テストが全部通ったら `assembleDebug`。

## この Task でしないこと
- パネル・ホーム・設定の変更。全テストの実行、実機の操作、git のコミット等。

## 報告
実行したコマンドと結果(件数・失敗件数、修正前の失敗の内容、assembleDebug)、作成・変更したファイル、**決めたこと**。
