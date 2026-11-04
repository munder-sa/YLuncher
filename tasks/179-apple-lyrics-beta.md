最初に `tasks/RULES.md` を読み、従う。

# Task 179 Apple Music歌詞の実機確認用ベータ

ユーザー了承済みの計画のベータ準備。175〜178の実装・対象テスト・司令塔レビューが完了した。関連Androidテストは工程別に計148件(176a 6、176b 8、176c 32、177a 9、177b 31、178 62)成功。Task 175のPython追加20ケースと認証付き構造検証も成功。実歌詞のKotlin解析・実機表示は未確認。

実行環境が司令塔のGit更新・ADBを `codex-runで禁止（司令塔が行う）` として拒否するため、実装担当もGit/ADBを行わない。今回はAPK準備まで。制限を迂回しない。

## 変更してよいファイル

- `app/build.gradle.kts`
- `panelhost/build.gradle.kts`

両ファイルの `versionCode = 40` を `41`、`versionName = "2.2.1-beta1"` を `"2.3.0-beta1"` にする。それ以外は変更しない。新しい歌詞取得機能の試験版として次のminorを選んだ。正式公開の許可・安定版への昇格は含まない。

既存のreleaseビルド・R8・署名設定を使う。鍵/keystore設定を読んで出力しない。依存や署名設定を変更しない。司令塔は現存する版40の2APKにapksigner verifyを実行し、どちらも署名検証成功、証明書SHA-256 `44d8c612bd3948e265840f28adff0f820d5893d0ba24d888b041ca16544f7f6d` で一致と確認済み。

## 使ってよいコマンド

- 読み取りコマンド。
- `/home/munde/tools/gradle-run.sh :app:assembleRelease :panelhost:assembleRelease`

版だけの変更なので単体テストを繰り返さない。失敗したら原因を報告し、範囲外の修正をしない。成功後に両output-metadata.jsonの版とAPKの存在を確認する。

## 司令塔がビルド後に行うこと(実装担当は実行しない)

1. 版差分、両出力メタデータとAPK内のpackage/versionを照合する。
2. apksigner verifyで両APKの署名を検証し、上記の版40の証明書と比較。SHA-256とファイルサイズを記録する。
3. 実行環境側のADB制限が解消するまで実機導入は未実施として記録する。現時点で最後に確認された実機は2.2.1-beta1 / 40。
4. 導入できる状態になったら、本体→中継をinstall -rで更新し、版/署名・HOME・通知/Bluetooth・通知リスナー・ユーザー補助の前後を照合する。端末データを消さない。
5. 設定の「音楽 → Apple Music の歌詞」で利用者がWeb用トークンをマスク入力し「接続確認」。チャット・コマンド引数・ログへトークンを貼らない。
6. Lemonと別の曲でApple採用ログ(固定理由/行/単語数/所要時間)と表示を確認。ホーム/パネル、曲送り・停止/シーク、オフ・解除・再入力・プロセス再作成、IDなし・他アプリを確認する。本文・トークンを証跡やGitに保存しない。
7. Android実通信とKotlinパーサー採用は、実機で確かめるまで成功扱いにしない。取得時間/解析時間の分離、要求数、ヒープ実測も未計測ならそのまま記す。

## 報告

変更した版、ビルドコマンドと結果、APKのパス/メタデータ、実機未確認、決めたことを日本語で報告する。

## 司令塔の実機導入結果 (2026-11-04 14:35 JST以降)

- Codex再起動後にADB/Gitの旧禁止判定が解消。PATHから外れていた既存Windows ADBを `/mnt/c/Users/munde/AppData/Local/Android/Sdk/platform-tools/adb.exe` の絶対パスで使い、対象 `192.168.137.248:5555` のget-stateがdeviceを返した。
- 準備済みAPKのsha256sumはHANDOFF記載と一致。本体・中継の順に、上記ADBで `install -r 'F:\YLuncher\app\build\outputs\apk\release\app-release.apk'` と `install -r 'F:\YLuncher\panelhost\build\outputs\apk\release\panelhost-release.apk'` を実行し、ともにSuccess。
- dumpsys packageで本体/中継とも40 / 2.2.1-beta1→41 / 2.3.0-beta1、署名表示 `[2728742d]` 維持を確認。HOME、通知/Bluetooth権限、通知リスナー、carsyso/ランチャーのユーザー補助設定も前後一致。
- `shell am start -W -n com.gt8d.launcher/.ui.home.HomeActivity` 成功。アプリ一覧を長押しして設定→音楽→Apple Musicの歌詞へ移動。未接続・説明文・空のマスク入力・無効な接続確認/解除ボタンを確認。
- 利用者へ実機にトークンを入力して接続確認するよう依頼中。認証付き通信、実TTML採用・歌詞表示・操作場面はまだ未確認。起動後GT8Dのlogcat取得は空、採用ログの証拠にはしていない。
- 追記: 利用者の入力後「接続済み（地域: jp）」を回答と実機画面の両方で確認。入力欄の消去と解除ボタン有効も確認した。認証付きstorefront取得は成功、歌詞API/実TTML採用・表示は引き続き未確認。
- 再開時のビルド/単体テスト実行は0件。既存の成功結果は繰り返していない。
- 決めたこと: PATHを変更せず既存ADBの絶対パスを使う。未接続UIと認証後の確認を分け、実機導入だけで機能合格とはしない。
