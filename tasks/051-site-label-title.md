# Task 051: サイト名が無いページでも「Wikipedia」のような名前を出す

最初に `tasks/RULES.md` を読み、従うこと。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

## 背景(司令塔が実機で確かめた事実)
Task 049 で、上のバーの題名を `og:site_name`(無ければドメイン名)にした。Wikipedia(ja.wikipedia.org のモバイル版)は `og:site_name` を持たないため、題名が「ja.wikipedia.org」になる。
ユーザーの要望は「サイト名だけ」(例「Wikipedia」)。ページの題名は「Android (オペレーティングシステム) - Wikipedia」。

## 変更するファイル(これで全部)
- `M/link/SiteLabel.kt`、`M/link/BrowserPane.kt`、`T/link/SiteLabelTest.kt`(テストを足すだけ。既存のテストは変えない)

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.link.*`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様
1. 名前の候補を、上から順に使う: ① `og:site_name` ② `<meta name="application-name">` ③ ページの題名(`document.title`)の**最後の区切りの後ろ**(区切りは " - "、" | "、" – "、" — "、" ｜ "。区切りが無ければ使わない。後ろが 1〜30 文字のときだけ使う) ④ ドメイン名(今のまま)。
2. `SiteLabel` に `fun fromTitle(title: String?): String?`(③の規則)を足し、`label(siteName, url)` を `label(siteName: String?, appName: String?, title: String?, url: String?)` にする(各候補は前後の空白を除き、空なら次へ)。古い形の呼び出しは残さなくてよい(呼び出し元は BrowserPane だけ)。
3. `BrowserPane` は、`onPageFinished` の JavaScript で ① と ② を1回で取る(例: `JSON.stringify([og, app])` を返し、`kotlinx.serialization.json` で読む)。③ は `view.title`(`WebView.getTitle()`)を使う。新しいページの読み込みが始まったら、すべて null に戻す。

## テスト(先に書く)
`SiteLabelTest` に: `fromTitle_lastSegment`("Android (OS) - Wikipedia"→"Wikipedia"、"記事 | 日本経済新聞"→"日本経済新聞")、`fromTitle_noSeparator_null`、`fromTitle_tooLong_null`(31文字)、`label_priority`(①>②>③>④ の順)。

## 手順(TDD)
1. テストを書く。2. 修正前に失敗することを確かめる。3. 実装する。4. 通ったら `assembleDebug`。

## この Task でしないこと
- 色・配置・ボタンの変更。全テストの実行、実機の操作、git のコミット等。

## 報告
実行したコマンドと結果(件数・失敗件数、修正前の失敗の内容、assembleDebug)、変更したファイル、**決めたこと**。
