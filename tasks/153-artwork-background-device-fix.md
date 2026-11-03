最初に tasks/RULES.md を読み、従う。

# Task 153: 実機でジャケット色の背景が付かない不具合を直す

## 実機の症状(2.0.0-beta1、2026-11-04)
- ホームのミニプレイヤーのカードの地が、歌詞表示でもジャケット表示でも `Palette.Panel`(#2E2E38)のまま。ジャケット(青緑)は表示されている。単体テストは通っている。
- 端末の別のアプリの記録で、Apple Music の再生情報の変化(`onMediaInfoChange`)が**1秒ごと**に出ている。`MediaWatcher` は metadata の更新ごとに `NowPlaying` を作り直すので、`NowPlaying.artwork` の Bitmap が毎秒**別のインスタンス**になっている可能性が高い。
- 今の `ArtworkBackground.kt` は「画像が変わったら取り出しが終わるまで Panel 色、古い要求の結果は捨てる」ため、毎秒やり直し・取り消しになって色が付かないと推測している(推測なので、コードで確かめ、違えば報告する)。

## 変えるファイル(これ以外は変えない)
- `app/src/main/java/com/gt8d/launcher/ui/home/ArtworkBackground.kt`
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeMiniPlayer.kt`・`app/src/main/java/com/gt8d/launcher/ui/panel/MediaCard.kt`(呼び出しの引数を変える必要があるときだけ)
- `app/src/test/java/com/gt8d/launcher/ui/home/ArtworkBackgroundTest.kt`(追加と、鍵の変更に合わせた期待値の調整)

## 変更
1. 取り出しをやり直すかどうかの**鍵**を、Bitmap の同一性ではなく「曲の鍵 = `pkg`・`metaTitle`・`artist`・`album`(`NowPlaying` の値)+ Bitmap の幅・高さ」にする。鍵が同じなら、Bitmap のインスタンスが変わっても取り出しをやり直さず、保持している色をそのまま使う。
2. 鍵が変わったとき(別の曲): 取り出しの間は、前の曲の色を保つのではなく **Panel 色に戻す**のは今どおりでよい(古い色の取り違えを避ける)。取り出しが終わったら新しい色にする。古い鍵の結果は捨てる(今どおり)。
3. 失敗(有効な画素が無い・例外)は、同じ鍵の間は再試行しない(今どおり)。
4. 記録: `DiagLog.d("Artwork", "extract start key=<曲名>/<幅>x<高さ> config=<Bitmap.config>")`、`"extract done <#RRGGBB light>/<#RRGGBB dark> ms=<経過>"`、`"extract failed <理由>"`、鍵が同じでスキップしたときは出さない(毎秒出ないように)。
5. 背景の描き方(静止のグラデーション、MediaCard との共用)は変えない。

## テスト(ArtworkBackgroundTest に追加)
- `sameTrackNewBitmapInstanceDoesNotRestart`: 同じ曲の鍵で、内容の同じ別の Bitmap インスタンスを 1 秒ごとに 5 回渡しても、取り出しは 1 回だけで、色は付いたまま(Panel に戻らない)
- `differentTrackRestartsExtraction`: 曲の鍵が変わると取り出しをやり直し、結果が出るまで Panel 色
- `sizeChangeRestartsExtraction`: 同じ曲でも Bitmap の大きさが変われば取り出しをやり直す
- 既存のテストで「Bitmap が変わったらやり直す」を前提にした期待値は、鍵の仕様に合わせて直してよい(確かめる内容は減らさない、報告する)

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.ArtworkBackgroundTest' --tests 'com.gt8d.launcher.ui.home.HomeMiniPlayerTest' --tests 'com.gt8d.launcher.ui.panel.MediaCardTest'`

## 報告
- 推測が合っていたか(コードの根拠)、実行したコマンドと結果、直したテスト、決めたこと(RULES.md 1)。
