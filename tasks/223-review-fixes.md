最初に `tasks/RULES.md` を読み、従う。

# Task 223: 暫定期間レビューの指摘4件の修正と 2.3.2-beta4

司令塔(Claude)が暫定司令塔の期間(v2.2.0 以降)の変更をレビューし、ユーザーが修正を承認した4件を直す。どれも小さな回復漏れで、既存の挙動・既存テストの期待値は変えない。

## 直すこと

### 1. Apple 接続の再試行の待機を、端末の日時ではなく経過時間で測る
- 対象: `media/lyrics/AppleLyricsClient.kt`(429/503 の `retryAtMillis`)、`media/lyrics/AppleWebTokenProvider.kt`(`retryAtMillis`)、`media/lyrics/AppleMusicSession.kt`(`retryAtMillis`)。
- 今は `clock`(既定 `System::currentTimeMillis`)で期限を作り比較しているため、端末の日時が戻ると待機が延び、進むと早く抜ける。
- 各クラスのコンストラクタに、単調時計の引数を1つ追加する(既定 `android.os.SystemClock::elapsedRealtime`)。**再試行の期限の作成・比較・残り秒数の計算だけ**をこの単調時計に切り替える。
- JWT の期限判定(`isFresh` など)、保存する `verifiedAtEpochMillis`、Cookie の期限など、**日時として意味のある値は今までどおり `clock`**。
- `AppGraph.kt` の生成箇所は、既定値で動くなら変更しない。
- 既存テストが `clock` で再試行を操作している場合、テストの組み立て部分で同じ偽の時刻を新しい引数にも渡してよい(期待値・assert は変えない)。`AppleMusicRetentionTest.kt` の `Fixture` も同様に、組み立て部分だけ変えてよい。
- 新しいテスト(各クラスに最低1件): 再試行の待機中に `clock` だけを大きく戻しても(例: 1時間前)、単調時計で期限が過ぎれば再要求する。`clock` だけ進めても、単調時計で期限前なら待機のまま(残り秒数も単調時計で計算される)。

### 2. メディアの初回取得に失敗しても、後の通知で全情報を取り直す
- 対象: `media/MediaSessionCache.kt`。
- 今は `replaceControllers` でメタデータ/再生状態の読み取りが例外になると `entry.snapshot` が null のまま。後で再生状態の通知だけが来ると `emptySnapshot` に再生状態だけを載せるため、曲名・画像・曲長・歌詞識別が回復しない。`update` の例外時も同様。
- `update` で `entry.snapshot` が null のときは、まずコントローラからメタデータと再生状態を全部読み直したスナップショットを作り(読み直しが例外なら従来どおり `emptySnapshot`)、その上に今回の変更を適用する。
- snapshot がある通常時は、今までどおり I/O を増やさない(変わった項目だけ更新)。
- 新しいテスト(`MediaWatcherTest.kt` に追加): 初回のメタデータ取得が1回だけ例外 → 再生状態の通知 → 曲名・曲長などがコントローラの値で出る。通常時に再生状態の通知でメタデータを読み直さないことを確かめる既存テストは変えない(無ければ追加する)。

### 3. 最後のセット番号の読み取りの一時的な失敗を固定しない
- 対象: `config/LastSetIndexStore.kt`。
- 今は `io.read()` の失敗を `runCatching` で null にし、`loaded = true` にするため、後で読めるようになっても 0 のまま。
- ファイルが無い(null)・中身が数値でない(`NumberFormatException`)は、今までどおり 0 として読み込み済みにする。**それ以外の例外(I/O の失敗)は 0 を返すが読み込み済みにせず、次の取得で読み直す**。
- 書き込み(setter)は今までどおり。読み込み失敗の状態で書いた値は、そのまま保存・キャッシュされる。
- 新しいテスト(`LastSetIndexStoreTest.kt` に追加): 1回目の read が IOException → 0、2回目の取得で read が 3 を返す → 3。数値でない場合は再読しない(read の呼び出し回数で確認)。
- **既存テスト `failedRead_cachesDefaultButStillPersistsZero` の扱い(司令塔の判断・ユーザー承認済みの仕様変更)**: このテストは「IOException でも0を固定する」旧仕様を検証している。`NumberFormatException` の場合の検証(読み取り1回・0の書き込み)は**そのまま残す**。`IOException` の場合だけ、新仕様に合わせて「取得のたびに読み直す(reads が取得回数だけ増える)・0 を返す・0 を書けば persisted が 0 になり、以後は読み直さない」を厳密に検証する形に変える。テストを2つに分けてもよい(名前は報告する)。それ以外の既存テストの期待値は変えない。

### 4. 歌詞用トークン探しを入力長に対して線形にする
- 対象: `media/lyrics/AppleWebTokenProvider.kt` の `JWT` 正規表現と `findToken`。
- 今の `eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+` は、`.` の無い `eyJeyJeyJ…` のような長い入力で、開始位置ごとに末尾まで走査するため処理量が入力長の2乗になる。
- 正規表現をやめて手書きの走査にするなどして、**入力長に対して線形**にする。通常の入力で見つかる候補(順序・重複除去・20件の上限・16KiB を超える候補の除外)は今と同じにする。今の正規表現と同じ結果になる条件を報告に書く。
- 新しいテスト(`AppleWebTokenProviderTest.kt` に追加): `eyJ` を繰り返した 2MiB 程度で `.` を含まない文書を含めてもトークン取得が短時間で終わる(上限 5 秒。今の実装では終わらない規模にする)。通常の JWT を含む文書で、今と同じトークンが選ばれる既存テストは変えない。

### 5. 版を 2.3.2-beta4 / 55 にする
- `app/build.gradle.kts`・`panelhost/build.gradle.kts` の `versionCode = 54` → `55`、`versionName = "2.3.2-beta3"` → `"2.3.2-beta4"`。他は変えない。

## 作る・変えるファイル(これ以外は変えない)
- `app/src/main/java/com/gt8d/launcher/media/lyrics/AppleLyricsClient.kt`
- `app/src/main/java/com/gt8d/launcher/media/lyrics/AppleWebTokenProvider.kt`
- `app/src/main/java/com/gt8d/launcher/media/lyrics/AppleMusicSession.kt`
- `app/src/main/java/com/gt8d/launcher/media/MediaSessionCache.kt`
- `app/src/main/java/com/gt8d/launcher/config/LastSetIndexStore.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/AppleLyricsClientTest.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/AppleWebTokenProviderTest.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/AppleMusicSessionTest.kt`
- `app/src/test/java/com/gt8d/launcher/media/MediaWatcherTest.kt`
- `app/src/test/java/com/gt8d/launcher/config/LastSetIndexStoreTest.kt`
- `app/src/test/java/com/gt8d/launcher/media/lyrics/AppleMusicRetentionTest.kt`(時計の注入だけ)
- `app/build.gradle.kts`
- `panelhost/build.gradle.kts`

新しいファイルは作らない。上記の範囲で済まない場合は止めて報告する。

## 使ってよいコマンド
    /home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.*' --tests 'com.gt8d.launcher.media.MediaWatcherTest' --tests 'com.gt8d.launcher.config.*' --tests 'com.gt8d.launcher.sets.SetRunnerTest'

失敗を直したときの再実行はよい。全テスト・ビルドは流さない(司令塔が後でまとめて流す)。

## 報告
- 変更の要約(ファイルごと)、追加したテストの名前
- 実行したコマンドと結果(件数・失敗件数)
- 4 の手書き走査が今の正規表現と同じ結果になる条件
- 決めたこと
