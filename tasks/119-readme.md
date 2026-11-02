最初に tasks/RULES.md を読み、従う。

# Task 119: リポジトリの README.md を作る(紹介ページから作り直す)

## 目的
GitHub(非公開リポジトリ munder-sa/YLuncher)の最初に表示される README.md を、日本語で分かりやすく作る。内容は紹介ページ `docs/site/index.html` を元にし、Markdown に合わせて改める。

## 書くこと(順に)
1. タイトル `# YLuncher` と 1〜2 文の説明(GT8D 用のカスタムランチャー、Android 15)。版は v1.6.3。
2. スクリーンショット: `docs/site/img/home.png` を大きく 1 枚。その後に set-panel.png・panel-sets.png・app-list.png・settings.png を表(2 列)で並べ、それぞれに短い説明を付ける(説明は index.html の該当箇所から)。画像は相対パス。
3. 特長: index.html の FEATURES の 6 項目を、見出しなしの箇条書き(太字の名前 + 1 文)にする。
4. 使い方の流れ: index.html の 3 ステップ。
5. 導入: index.html の install 節を元に、release 版を本体 → 中継の順に `adb install -r`、通知へのアクセスの許可、既定のホーム。詳しくは `docs/install-v1.6.md` へのリンク。
6. よくある質問: index.html の FAQ を `<details>` で。
7. 開発者向け: 構成(モジュール :app = com.gt8d.launcher、:panelhost = com.gt8d.launcher.panel の中継)、使っている技術(Kotlin 2.1.0、AGP 8.7.3、Jetpack Compose、DataStore + kotlinx.serialization、Robolectric の JVM テスト、minSdk 35)、ビルドとテストのコマンド(`./gradlew test assembleDebug`、`./gradlew assembleRelease`、リリースの署名は git に入れない `keystore.properties` を使う)、実機の場面テスト `scripts/device-scenarios.sh`(デバッグ版が必要)、紹介ページ `docs/site/index.html`。
8. 終わりに一行: 自分用のプロジェクトであること。

## 守ること
- 事実は index.html・docs/install-v1.6.md・build.gradle.kts・settings.gradle.kts・.gitignore で確かめたことだけを書く。確かめられないことは書かない(推測で進めない)。
- 個人の端末の IP アドレス・メールアドレス・署名の鍵のパスワードは書かない。
- 絵文字は使わない。

## 作るファイル(これで全部)
- create README.md

## 使ってよいコマンド
読み取り系のみ。
