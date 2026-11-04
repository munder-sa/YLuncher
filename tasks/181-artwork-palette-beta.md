最初に `tasks/RULES.md` を読み、従う。

# Task 181 背景改善の実機確認用ベータ

Task 180は実装/司令塔レビュー/指定5クラス101件成功、失敗/エラー/スキップ0。色差改善を実機で確認するため、現在のApple歌詞試験版を2.3.0-beta2へ更新する。

## 変更してよいファイル

- app/build.gradle.kts
- panelhost/build.gradle.kts

両方のversionCodeを41→42、versionNameを2.3.0-beta1→2.3.0-beta2へ変更する。版以外は変更しない。依存、署名設定、キー、最適化設定は変更/表示しない。Git/ADBを実行しない。

## 使ってよいコマンド

- 読み取りコマンド。
- `/home/munde/tools/gradle-run.sh :app:assembleRelease :panelhost:assembleRelease`

単体テストを再実行しない。ビルド成功後は両output-metadata.jsonのpackage/versionとAPKの存在を読む。失敗なら範囲外の修正をせず報告する。

## 司令塔が行うこと

差分、署名、版、SHA-256を照合して実機へ本体/中継の順に更新。既定HOME・権限等を直前状態と比較する。更新前のユーザー補助はcarsysoだけになっていたため、過去の状態へ勝手に戻さない。異なるアルバムで背景色と歌詞/ジャケット切替を確認し、必要に応じてパネルの確認可能な範囲を記録する。主要文字コントラストの計算と実機の見た目は区別し、利用者にも見た目を確認してもらう。Apple歌詞の採用は別の未確認事項として扱う。公開・push・正式版昇格は行わない。

## 報告

変更版、ビルドコマンド/結果、両APKの版とパス、決めたこと、実機未確認を日本語で報告する。

## 実装・実機確認結果 (2026-11-04)

- `.codex-bak/20261104-152740/`、終了0、指定2ファイルの版だけ変更。上記assembleReleaseは1回でBUILD SUCCESSFUL、47秒。単体テストの再実行なし。
- 司令塔はmetadataとSDK35 aapt2 dump badgingで両方42 / 2.3.0-beta2を確認。apksigner verify --print-certsは両方終了0、証明書SHA-256は旧版と同じ `44d8c612bd3948e265840f28adff0f820d5893d0ba24d888b041ca16544f7f6d`。Javaのnative-access/perfdata警告はあったが検証は成功。

| APK | bytes | SHA-256 |
|---|---:|---|
| app/build/outputs/apk/release/app-release.apk | 2922039 | 0bd47f1c01e670267257bf2dabad8f38433d88e751f6634b0be8fbd11c6d8492 |
| panelhost/build/outputs/apk/release/panelhost-release.apk | 2021093 | ebce9b2368f3bd601685ac07359d7beebe607331f98554a3f230fb137d38f593 |

- Windows ADBの既存絶対パスで対象192.168.137.248:5555へ本体→中継の `install -r`、両方Success。更新後のdumpsys packageで42 / beta2と署名表示2728742dを確認。HOME=YLuncher、通知/Bluetooth許可、全通知リスナー5件、ユーザー補助carsysoのみという直前状態も維持。ユーザー補助を変更していない。
- `am start -W`、通常の入力操作、`dumpsys media_session`、Artworkに限定したlogcat、screencapで3つの異なるアルバムを照合した。

| アルバム/曲 | 開始/中央/終了 | 抽出ログの時間 |
|---|---|---:|
| WAVES / Ride on Wave | #0A3F5D / #08324A / #062638 | 初回173ms |
| Lemon - Single / Lemon | #1D413F / #2E3967 / #5B2957 | 10ms |
| 怪獣 - Single / 怪獣 | #263E54 / #3B3A3C / #503624 | 3ms |

- WAVESは鮮やかな青、Lemonは緑〜青〜紫、怪獣は青〜茶色への違いを視認。Lemonで歌詞ON/OFFを切り替えて背景維持・その切替による再抽出なしを確認。主要文字を視認でき、利用者から「かなりいいですね」と評価を得た。
- セット1の通常操作でYouTubeとパネルの分割を起動し、パネルの怪獣にも同配色を確認。パネル単体の全画面起動は既存動作でホームへ戻ったため、その状態をパネル合格とは扱っていない。パネルの表示設定自体は変更していない。
- 実画面の空白背景6地点ずつ(ホームLemon/怪獣、分割パネル怪獣)をPNGから読み取り、3停止点の参照値とRGB各成分最大1階調差で一致。採取色に対する白/Muted/白0.62の最小計算比はそれぞれ5.2058/5.2054/5.1411。実画面全画素の文字コントラストを測ったという意味ではない。
- 性能は厳密な前後比較なし。ログ時間はディスパッチや復帰待ちも含む。初回173msに加え、分割中のYouTubeメタデータ切替では3〜64ms、怪獣への戻りで28msも記録した。3〜10msだけを代表値にして性能合格とはしない。常時アニメーションなし/再抽出抑止は対象テストで確認済み。
- 終了前にHomeActivityへの起動は成功したが、後の画面取得はApple Music本体だった。最後のmedia_sessionではApple Music怪獣が一時停止、YouTubeはSTOPPED。歌詞表示は比較のためOFFへ切り替えた状態。再生データ・SIM・ネットワーク設定を消去/変更していない。
- 決めたこと: ユーザーが了承したこの配色をbeta2として採用する。公開/push/正式版昇格は行わない。実機で画像なしの場面と厳密な性能比較は今回未実施(画像なしは単体検証済み)。Apple歌詞の実機採用は引き続き未完了。
