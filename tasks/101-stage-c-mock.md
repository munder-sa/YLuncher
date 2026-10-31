# Task 101: v1.6 段階 C(見た目の整理)のモックを作る

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
v1.6 設計書 `docs/superpowers/specs/2026-10-31-v1.6-ux-design.md` の §5 段階 C(C1〜C6)を、ユーザーにモックで選んでもらう。画面は GT8D(1920×1080px・密度 238)。**今の実機の画面**は `build/mock/stage-c/ref/` の PNG(司令塔が撮った): `settings-top.png`(設定のトップ)、`settings-sets.png`(画面セットの一覧)、`settings-edit.png`(画面セットの編集)、`panel-sets.png`(左 YouTube・右パネル 6:4 で、パネルのセット表示)、`curtain.png`(切り替えの幕)。起動画面は `ui/home/BootScreen.kt` を読んで描く。
色・大きさは今のコードに合わせる: `ui/theme/Theme.kt` の `Palette`(Background0 #0B0B0F、Background1 #17171D、Panel #2E2E38、Accent #8FE63B(緑。やめる)、PanelAccent #FFFFFF、Muted #B0B0BB、Control #454550、Divider #555560、Danger #FF6B6B)、`Dims`(タッチ 72dp、本文 20sp)。dp → px は ×1.4875(密度 238)。
決まっていること(全部の案で守る): 強調色は白(緑をやめる)。タッチ 72dp 以上・本文 20sp 以上。Material 3 / iOS HIG に寄せる(破線のボタンは使わない、ボタンは filled / tonal / outlined / text のどれか)。ドックに文字を付けない。

## 作るファイル(これで全部)
- create `build/mock/stage-c/c3-selection.html`
- create `build/mock/stage-c/c6-set-list.html`
- create `build/mock/stage-c/c1-panel-sets.html`
- create `build/mock/stage-c/c2-curtain-boot.html`
- create `build/mock/stage-c/c6-edit-dialogs.html`

使ってよいコマンド: なし(読み取り系のみ。画像化は司令塔が Chrome で行う)。

## 共通の作り
- 各ページは1つの判断。`<body>` は幅 1960px・背景 #000、上に見出し(ページの題と1行の説明、白 28px)。その下に、1920×1080px の画面を `transform: scale(0.5)` で 960×540 にした枠を **2 列**で並べ、各枠の上にラベル(「現状」「案A: …」「案B: …」、白 24px、案の特徴を一言)。現状の枠は `ref/` の PNG を `<img>` で 960×540 に。案の枠は HTML/CSS(インラインの SVG 可。外部のフォント・画像・スクリプトは使わない。フォントは `"Noto Sans JP", "Roboto", sans-serif`)。
- 案の画面は、今の画面と同じ内容(同じセット名・アプリ名)で、実機の大きさ(px)で描く。アプリのアイコンは単純な図形(YouTube は赤い角丸に白い三角、Apple Music は赤〜ピンクの角丸に白い音符、マップは白い角丸に色の点、など)。`PanelIcon`(白い角丸の正方形に濃いグレーで画面とドック)は `ui/home/PanelIcon.kt` を読んで同じ形に。
- 角丸・余白は C4 の段に合わせる: 外側の面 24dp、内側の部品 12dp、画像 8dp、余白 8/16/24dp。

## 各ページ
1. `c3-selection.html`(C3: 選択の見せ方と主ボタン)— 画面セットの編集(`settings/SetsEdit.kt` を読む)。現状 + 案A + 案B。
   - 両案共通の直し: 「左」「右」の見出しが背景にほぼ溶けて見えない → `Palette.Muted` 20sp に。「左のアプリ: YouTube」の文字だけの所を、押せる行(アプリのアイコン 48dp・名前・右に「変更」と ›、72dp)に。下の「分割に対応していないアプリは…」も見える色に。比率(3:7〜7:3)は1行の**セグメント**(5つ並び)に。「左右を入れ替える」は tonal ボタン(Panel の地、白文字)。「保存」は下の主ボタン。ヘッダーは ← だけ(× は消す。C6)。
   - 案A: 選ばれたもの = **白い面に暗い文字**(Material の filled / selected segment)。保存 = 白い filled ボタンに暗い文字。
   - 案B: 選ばれたもの = **暗い面(Panel)+ 白い輪郭 2dp + ✓ のアイコン**、選ばれていないもの = 輪郭なしの Background1。保存 = 白い輪郭の outlined ボタン。
2. `c6-set-list.html`(C6: セット一覧の並べ替え・削除)— 画面セットの一覧(`settings/SetsScreen.kt`)。現状 + 案A + 案B。
   - 両案共通: 各行の頭に番号と左右のアプリのアイコン(48dp)、名前「YouTube | パネル」、下に比率(Muted)。ヘッダーは ← だけ。一番下の「セットを追加」は outlined ボタン(+ のアイコン、Muted ではなく白い文字でよい)。
   - 案A: 行の右に、上へ・下へ(tonal のアイコンボタン 72dp、端の行では薄く無効)と、ゴミ箱のアイコンボタン(Danger の色のアイコン、地は透明)。削除は確認のダイアログ(この案の枠の中に、ダイアログを開いた状態の小さな絵を添える必要はない)。
   - 案B: 行の右に「⋮」のボタン 72dp だけ。押すとメニュー「上へ」「下へ」「削除」(削除は Danger の文字)。案Bの枠は、2 行目のメニューを開いた状態で描く。
3. `c1-panel-sets.html`(C1: パネルのセット表示の専用カード)— 左 YouTube・右パネル(6:4、パネルの幅は 768px)の、パネルの「セット」表示。現状 + 案A。
   - 案A: パネルの中のカードを**横長**に: 高さ 104dp、間隔 8dp、角丸 24dp、地 Background1。左に左右のアプリのアイコン(各 48dp を 4dp 空けて並べ、比率の絵は無し)、右に「1  YouTube | パネル」(番号 Muted、名前 白 20sp、1行で省略)。今のセットは白い輪郭 3dp。時計・「アプリ | セット」のセグメント・ドックは今のまま。5 セットが縦に並び、最後に outlined の「セットを追加」(高さ 72dp、全幅)。左の YouTube の画面は暗い灰色の面で代用してよい。
4. `c2-curtain-boot.html`(C2+C3: 幕と起動画面)— 現状の幕(ref)+ 案Aの幕 + 起動画面の現状(BootScreen.kt から描く)+ 起動画面の案A。
   - 案A: パネルの側の絵を、緑の3本の縦線から `PanelIcon`(96dp。左パネルなら左右反転)に。進行線・今の位置の点を**白**(未来の点は白の 40%)。アプリの側は、頭文字の丸でなくアプリのアイコンの図形(今は頭文字になっている)。「開いています」「ホームへ戻る」(白い輪郭)は今のまま。起動画面も緑を白に、パネルの絵を PanelIcon に。
5. `c6-edit-dialogs.html`(C6: 保存せずに戻るときの確認ほか)— 2×2 の4枠: (a) 編集中に ← を押したときのダイアログ「変更を保存しますか?」+ ボタン「保存」(白い filled)・「破棄」(Danger の文字の text ボタン)・「続ける」(text ボタン)。(b) 片側をパネルにしたとき、反対側の「アプリ」が未選択の状態の説明「反対側のアプリを選んでください」(Muted、アプリの行が強調)。(c) 即時に保存される画面(例「操作」)の上部に「変更は自動で保存されます」(Muted 20sp)。(d) 設定のトップ(ref の `settings-top.png` を元に、ヘッダーを ← だけにし、緑の残りが無いことを示す)。ダイアログは Material 3 の basic dialog(角丸 28dp、地 Panel、題 24sp、本文 20sp Muted、ボタンは右寄せ)。

## この Task でしないこと
- アプリのコードの変更。画像化(司令塔が行う)。git のコミット等。

## 報告
作ったページと各案の意図、**決めたこと**(描き方の判断)。
