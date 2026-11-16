# 新型RAV4(Arene)× GT8D メーター地図暗転:調査メモのファクトチェックと統合判定

作成: 2026-11-16 / 対象: 外部調査メモ2本(`rav4-arene-sources/`)を、一次ソースと既存の spike(`meter-map-nav-bridge.md`)の静的解析・DHU 実測に照らして確かめた結果。

- 元メモ: `rav4-arene-sources/2026-11-16-memo-original.md`
- 一次ソース検証(ユーザー調査): `rav4-arene-sources/2026-11-16-primary-source-verification.md`

## 結論(先に)

1. **carprobe / a0call が送る値の根拠は確認できた。** `NavigationFocusRequest`(制御チャネル msg 13、Phone→HU)の `NAV_FOCUS_NATIVE = 1` / `NAV_FOCUS_PROJECTED = 2` が、open-android-auto の `.proto` と一致する。a0call が呼ぶ `sendNavFocusNotifyCmd(int)`(引数 0 で NATIVE、0 以外で PROJECTED。spike の解析)とも合う。ただし `.proto` の確度表記は `silver [apk_static, cross_version]` で、**APK の静的解析由来・実機での実証ではない**。
2. **Phone から NATIVE を送るのは仕様上の「フォーカスの解放(release)」**。元メモ 2-2 の「NATIVE は本来ヘッドユニット側の値」という書き方は不正確。ただし GT8D はフォーカスを一度も要求していない(DHU 記録で `MESSAGE_NAV_FOCUS_REQUEST` 0 件)ので、**NATIVE 単発は「持っていないものを解放」になり無反応の可能性がある**。`a0call toggle`(PROJECTED → NATIVE)はこれを埋める手順で、**実装済み**。
3. **CarPlay 経路の暗転の仕組みは、ほぼ説明がつく(未実測)。** 本物の iPhone では未案内時でもメーターに Apple マップが出る(Reddit の 2026 年型 RAV4 オーナー証言)。GT8D の CarPlay ブリッジは車へ `altScreen` と `maps:/car/instrumentcluster` を申告している(spike の静的解析)。車はメーター枠を「対応 iPhone」に渡すが、GT8D にはメーター用の地図を描く主体が無い → 黒、という筋が最も自然。
4. **Android Auto 経路は未確定のまま。** AA ではメーター地図が出ること自体は複数のオーナーが肯定しているが、**未案内時から出るか**は誰も書いていない。GT8D の AA デーモンはクラスター用映像シンクに一切応じない(静的解析)ので、車がクラスター用シンクを申告するかどうかが分岐点。→ 実車の `Save Boot Log & Services` が決め手(手順は既存のまま)。
5. **「P3 Pro は R15 以前の世代だから暗転しない」仮説は根拠が崩れたので外す。** 出典の記述は「起きにくい」+「暗転は機種を問わず共通」で、「暗転しない」ではない。また GT8D のブリッジでは `viewAreas`(全画面化)と `altScreen`(メーター)が**別々の申告項目**なので、「R15 で同時に入った機能だから両方そろう」とは限らない。

## 引用元の判定

| 引用 | 実在 | 内容 | 注記 |
|---|---|---|---|
| open-android-auto `NavigationFocusRequestMessage.proto` / `...ResponseMessage.proto` | ✅ | 一致 | msg 13(Phone→HU、request/release)/ msg 14(HU→Phone)。NATIVE=1、PROJECTED=2。確度 silver(静的解析) |
| open-android-auto `docs/channels/nav.md` | ✅ | 一致 | 「Channel 11 = NAVIGATION_STATUS」。フォーカスの交渉はここではなく制御チャネル |
| open-android-auto `DisplayTypeEnum.proto` / `display-routing.md` | ✅ | 一致 | `DISPLAY_TYPE_CLUSTER = 1`。映像サービス(AVChannel)の `display_type`。CLUSTER は最大 1 つ |
| `OMKE/wireless-carplay-virtual-cockpit` | ✅ | 一致 | `ALT_SCREEN_TYPE 111`(0x6f)。Suding 製ドングル `sdDongle` の標準ファームは `/info` からメイン以外の画面を落とし、type 110 しか中継しない。**Audi MHI2Q 向け・ドングル(中継)の話** |
| `yuedizhibo/MHI2Q-CarPlay-AltScreen` | ✅ | 一致 | Main=110、AltScreen=111。**Audi MHI2Q(車両側)の改造** |
| Apple WWDC16 Session 723 | ✅ | 一致 | changeModes / modesChanged、appStates(TurnByTurn・PhoneCall・Speech)、TurnByTurn は後勝ち。確認は第三者の書き起こし(nonstrict) |
| Apple WWDC19 Session 252 | ✅ | 一致 | iOS 13 / Communication Plug-in R15 で、メーター用の複数 H.264 と動的リサイズ・view area |
| Android for Cars ナビ品質 `NF-9` | ✅ | 一致 | クラスタには地図タイルのみ(ルートは任意) |
| AOSP Instrument Cluster API | ✅ | 一致 | `CarAppFocusManager`(`APP_FOCUS_TYPE_NAVIGATION`)。**AAOS の車内 API で、AA の投影プロトコルとは別物** |
| `CPSupportsInstrumentClusterNavigationScene` | ページは有る | 未確認 | 本文が JS 描画で取れず |
| note「復活手順」(n32a4…) | ✅ | 手順は一致 | **機種は HE PRO ULTRA で GT8D ではない**。純正ナビで案内を開始→終了で復帰、純正画面に戻すだけでは戻らない |
| note「GT8D」(n3b41…) | ✅ | — | GT8D の紹介記事。暗転の話は無い |
| note「4機種比較」(n99144…) | ✅ | 一部誇張 | P3 Pro の純正サイドバーが消せない点は一致。暗転は「起きにくい」「機種を問わず共通」 |
| Reddit r/rav4club 1sb1872 | ✅ | 一部のみ | CarPlay(Apple マップ)は未案内時もメーターに地図(トップダウン)。AA は「動く」の証言のみで、未案内時は不明 |

## 元メモの訂正点

- **1-1 の出典**: 復活手順の note は HE PRO ULTRA の記事。GT8D で同じ手順が効くかは未確認。
- **1-2 の「PROJECTED → NATIVE の 2 段送信」**: 既に `a0call toggle [ms]` として実装済み。「NATIVE 単発は no-op の可能性」は nav.md に書かれた事実ではなく推論(上の結論 2 の理由で妥当)。
- **1-2 のログ採取「Channel 11 が広告されているか」**: チャネル番号は ServiceDiscoveryResponse で動的に振られる(display-routing.md 内でも番号が 1 つずれた例がある)。**番号ではなくサービス種別**(`navigation_status_service`、`DISPLAY_TYPE_CLUSTER` の `media_sink_service`)で見る。carprobe の要約は既にサービス種別で判定している。
- **2-2 の「NATIVE は本来 HU 側の値」**: 不正確。Phone→HU の msg 13 で NATIVE を送るのは仕様上の解放。問題は「Arene が解放を受けて純正地図に戻すか」と「持っていないフォーカスの解放を無視するか」の 2 点。
- **2-2 の `CarAppFocusManager`**: AAOS の API。Arene の内部実装がこれに相当するかは不明(Arene は AAOS とは限らない)。
- **3-1 の Suding ドングルの事例**: 事実だが、あれは「iPhone と車の間で中継するドングル」の話。GT8D は「自分が iPhone を名乗る」側で、**しかも GT8D のブリッジは `altScreen` を申告している**(OMKE のドングルが落としていたものを、GT8D はむしろ足している)。向きが逆なので、そのまま当てはめない。
- **3-2 の P3 Pro / R15 仮説**: 外す(結論 5)。P3 Pro が暗転しにくい理由の候補は、むしろ「`altScreen` を申告していない」こと(未確認。P3 Pro を解析する手段は無い)。

## 実車テストへの反映

手順そのもの(HANDOFF の「実車テスト手順」、spike の「実車テストの手順」)は変えない。追加で次を見る。

1. **接続直後・案内なしの状態でメーター地図が黒いか**をまず記録する(AA は未案内時の挙動が未確定なため、ここが最初の分岐)。
2. 黒いときの順序は既存手順どおり `native` → `toggle`。GT8D はフォーカスを持っていないので **`native` 単発の無反応は想定内で、`toggle` まで必ず試す**。`native` を先にするのは、それだけで戻れば「解放だけで足りる」と分かり、自動送信が最も簡単になるため。
3. 保存したログで、`navigation_status_service` と `DISPLAY_TYPE_CLUSTER` の映像サービスの有無に加え、**ナビ状態チャネルの `InstrumentClusterStart`(0x8001、HU→Phone)** が来ているかを見る(車がメーター表示を始めた合図。nav.md による)。
4. CarPlay モードで持ち込む場合は、暗転の主因は `altScreen` の申告と見てよい(上の結論 3)。この場合 a0call は効かない(別デーモン・別プロトコル)。

## 判断表(更新)

| 実車の結果 | 解釈 | 次の一手 |
|---|---|---|
| 有線 AA・案内なしで黒くならない | AA では車がメーター枠を投影側に渡さない(または渡しても純正地図を出し続ける) | AA 運用を軸にする。CarPlay が要る場合だけ CarPlay 側を調べる |
| 有線 AA で黒くなり、`toggle`(または `native`)で戻る | 車はフォーカスの遷移でメーターの描画元を純正に戻す | 投影確立後の自動送信を検討(A1/A2 はユーザー承認が要る)。無線 AA でも確かめる |
| 有線 AA で黒くなり、送っても変わらない | 車はクラスター用の映像サービスの成立に紐づけて固定している可能性 | ログで `DISPLAY_TYPE_CLUSTER` の申告を確認。申告ありなら GT8D のデーモンがそれに応じない(静的解析)ことが原因候補。純正ナビでの案内開始→終了(note の手順)が GT8D でも効くかも確かめる |
| CarPlay モードで黒くなる | `altScreen` の申告で車がメーター枠を渡し、GT8D が描かない | ブリッジの `altScreen` 申告を外す方法を調べる(`LG Electronics` のときだけ外す分岐があるので、条件分岐の場所は分かっている。書き換えになるのでユーザー承認が要る) |

## 確度のまとめ

- 確定: NAV フォーカスの値と向き、`DISPLAY_TYPE_CLUSTER`、CarPlay の type 110/111、CarPlay(本物の iPhone)での未案内時のメーター地図表示、P3 Pro のサイドバー制約、note の復活手順(HE PRO ULTRA)。
- 未確認: Arene が NATIVE の解放でメーターを純正に戻すか、AA の未案内時の挙動、GT8D の CarPlay ブリッジが実際に type 111 を開くか、P3 Pro の申告内容。いずれも実車(納車 2026-11-22 以降)か、CarPlay モードでのログ採取で決まる。
