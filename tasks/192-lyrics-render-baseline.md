# Task 192 歌詞描画の比較条件固定（司令塔）

2026-11-05、利用者が計画の実行を承認。設計は `docs/superpowers/specs/2026-11-05-lyrics-render-cost-design.md`。このTaskは司令塔の実機計測記録で、実装担当へADB権限を与えない。

## 今回固定するもの

- 作業ブランチ `perf-lyrics-render`、開始HEAD299aa5d。基準の機能実装はv2.3.0 / f0e5c9d。
- 比較用の対照Aと候補Bをともに **2.3.1-beta1 / 48**、同署名・同じrelease最適化で作成する。Androidのrelease版ダウングレードに依存せず交互に上書き比較するための実験用版であり、各APKを候補名/ソースコミット/SHAで識別する。最終採用版は別のbeta番号・コードへ進める。
- Task196の版/ビルドのみを先行し、**機能変更前のAを保存してからTask193へ進む**。対照Aはコードを後から戻して再構築しない。APKはGit管理外build/perf-lyricsに保存する。
- 通常display 0の1920×1080/238dpi/30Hzを主要A/B条件とする。現在の仮想画面から戻す場合はscrcpyの設定と単一Homeの状態を揃える。仮想画面を併用する比較は別集計とし、60fpsの実証に使わない。
- 曲ID/状態/position/updated/speedと端末uptimeを前後で記録する。PLAYING以外、選択曲の変更、前面画面の変更、操作混入の試行を不採用にする。歌詞本文/トークンを採取しない。
- 単語区間はLemonの同じ30秒、前奏/間奏は表示する点の時間帯を画面確認して別に固定。点灯しない区間から候補Aの効果を推定しない。基準/候補は30秒を3組、交互測定。候補の対象場面が30秒未満なら該当区間の短い比較を別枠で3組とし、単語30秒と合算しない。
- 各APKのインストール後は同じコンパイル条件、再生/画面の暖機条件を使用する。時計/温度を記録。batteryのtemperature=0は有効な温度としない。thermalserviceの状態が取得できない場合は未測定と明記。

## 境界

端末のネットワーク/SIM/IPv6や画面のsize/density/Hzを変更しない。計測と準備に必要な再生操作、Activity/scrcpy操作、承認済みのbeta上書きだけを行う。テストやアプリ実装はTask193以降のcodex-runで行う。

## 記録

各試行のJSON、gfxinfo、meminfo、必要なトレースを一時領域へ保存し、採用判断に使う統計は文書へ残す。生の画像/実歌詞はGitへ保存しない。前回の0.45%のようにPLAYINGが維持されない測定は再利用しない。

## 確定した実行手順 (2026-11-06)

- 基準ソース4ea66f4、候補Aのソースe5f78cb。全比較APKはrelease/R8あり、48/beta1、同じ署名。本体を毎回install -rし、`cmd package compile -m speed -f com.gt8d.launcher`。compileの実状態speedを確認した。
- 通常画面display0、1920x1080/238dpi/30Hz、前面Homeは1 Activity。scrcpy 5.0.1の通常mirrorを使用し、引数は `-s 192.168.137.200:40275 --max-fps=60 -b 16M --audio-source=playback --print-fps`。転送用display7の60Hzはアプリの60fpsを意味しない。
- Apple Musicで同じLemon - Singleを開き、Lemon行を選択→HOME→40秒暖機→pause→Sliderを左端までdragしてposition0確認→play→約41〜71秒の30秒窓。続いて同じアルバムのPaper Flower→HOME→12秒暖機→同じ0シーク→play→約2〜5秒の3秒窓。前奏6秒窓のpilotは歌詞開始が混ざったため除外した。
- 曲の選択IDはLemon1537460612 / Paper Flower1537460614、album1537460610。[Appleの公開カタログ](https://itunes.apple.com/lookup?id=1537460610&entity=song&country=jp)でID/曲名/長さを照合。dumpsysのPlaybackStateはID値を出さないため、各観測点の同定は曲名・artist・album・状態・position/updated/speedと選択した画面による。数値IDを毎回セッションから採取したとは扱わない。
- `/tmp/yluncher-compare-run.py` が上記操作、`/tmp/yluncher-perf-v2.py` が/media_sessionとuptime、/proc差分、gfxinfo、meminfoを採取。30秒窓は5秒ごとに再生状態を確認。最初と最後の前面Activity一致と位置の単調増加を必須とする。窓の実時間は端末uptime差で計算し、ホストsleep30秒と同一とはみなさない。
- 生データ `/tmp/yluncher-render-comparison/`、APKと対象テストXML `build/perf-lyrics/`。基準/候補を交互に3組。同一パッケージのART speed条件であり、通常のspeed-profile状態の値とは直接比較しない。
- Thermal Statusは前後0。HALが実温度を出さずbattery温度0も無効なので、温度は未測定。短い前奏窓のthread ticksはprocess ticksと採取時点が僅かにずれるため内訳の和を厳密な値にしない。gfxの個別framestatsは末尾120件リングなので、間隔統計はその末尾サンプルと明記する。
