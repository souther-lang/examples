# 出張申請モデルを作るチュートリアル

`businesstrip` の出張申請モデルを、運用中のExcel台帳を写したところから作り直します。手を動かすのは読者です。各節でモデルを書き換え、`souther examples` を回し、出てきた行を読んで次に何をするかを決めます。

このチュートリアルには業務担当者として総務の田中さんが登場します。モデルが変わるきっかけの多くは、レポートではなく田中さんの一言です。レポートは、田中さんに何を聞けばいいかを教えます。

書き上がるのは `businesstrip/src/main/souther/businesstrip.sou` の本線部分です。却下と差し戻しは扱わないので、最後に本家との差分を宿題として出します。

## 必要なもの

`souther` の実行ファイルだけです。Maven のモジュールは作りません。

```sh
git clone https://github.com/souther-lang/souther.git
cd souther
mvn -pl souther-cli -am -DskipTests install
```

`souther-cli/target/souther` ができるので、PATH の通った場所に置くかフルパスで呼びます。

作業用のディレクトリを1つ作り、その中に `businesstrip.sou` を1ファイルだけ置きます。以降、コマンドはすべてそのディレクトリで実行します。

```sh
mkdir -p ~/tmp/businesstrip-tutorial
cd ~/tmp/businesstrip-tutorial
```

## 1. 台帳を写す

いま運用されている出張申請は、総務が管理するExcelの台帳です。列はこうなっています。

```text
申請者ID / 出張先 / 予定費用 / 実費用 / ステータス / 提出日 / 事前承認日 / 最終承認日 / 精算額
```

画面には「提出」「事前承認」「最終承認」「精算」の4つのボタンがあります。列を `data` に、ボタンを `behavior` に写します。日時と実費用と精算額は台帳で空欄になっている行があるので `Option` にします。

`businesstrip.sou` を作って、次を書いてください。

```
module example.businesstrip

data 従業員ID = String
data 金額 = Int

data ステータス = 申請準備中 | 提出済み | 事前承認待ち | 事前承認済み | 出張完了 | 承認完了

data 出張申請 =
    { 申請者ID: 従業員ID
    , 出張先: String
    , 予定費用: 金額
    , 実費用: Option<金額>
    , ステータス: ステータス
    , 提出日時: Option<DateTime>
    , 事前承認日時: Option<DateTime>
    , 最終承認日時: Option<DateTime>
    , 精算額: Option<金額>
    }

behavior 出張申請を提出する : (申請: 出張申請, 提出日時: DateTime) -> 出張申請
    constructs 出張申請

behavior 事前承認する : (申請: 出張申請, 事前承認日時: DateTime) -> 出張申請
    constructs 出張申請

behavior 最終承認する : (申請: 出張申請, 最終承認日時: DateTime) -> 出張申請
    constructs 出張申請

behavior 精算額を計算する : (申請: 出張申請) -> 金額
    constructs 金額
```

回します。

```sh
souther examples businesstrip.sou
```

4つの behavior が同じことを言います。1つだけ引用します。

```text
  出張申請を提出する       injected      rows 0    pending 0
    signature   not applicable (this behavior's output is not a sum)
    partition   axes 6   single-axis 0/0   (6 not measured: no row names this behavior)
    boundary    not measured (no line was derived at any position)
      · not derivable: 申請.申請者ID
      · not derivable: 申請.出張先
      · not derivable: 申請.予定費用
      · not derivable: 提出日時
    branch      not applicable (this behavior has no body)

4 behaviors: 0 implemented, 4 injected; 0 rows waiting for a `let`.
adequacy: undetermined
```

レポートは behavior ごとに4つの軸で測ります。

- `signature` は、出力の直和のどのケースを example が名指ししたか
- `partition` は、入力にある直和のどのケースを通ったか
- `boundary` は、invariant や guard が作る線のどちら側を踏んだか
- `branch` は、本体の分岐をいくつ通ったか

いまはどれも測れていません。`signature not applicable (this behavior's output is not a sum)` は、出力が `出張申請` という1つの型なので、どのケースに落ちたかという問いが立たないという意味です。提出しても事前承認しても最終承認しても、この behavior のシグネチャは同じことしか言いません。台帳は「どのボタンをいつ押していいか」を知らないので、写した結果もそれを知りません。

`partition axes 6` の6は、`ステータス` と5つの `Option` です。`Option` は「値がある」「値がない」の直和なので、台帳の空欄1つにつき軸が1つ増えています。

`branch not applicable (this behavior has no body)` は `let` がないからで、`injected` も同じことを言っています。ここは後で埋めます。

まず `signature` の行を消します。これは田中さんに聞くまでもありません。

## 2. 聞いた話を行にする

田中さんに最初の質問をします。

> 出張申請を提出したら、そのあとどうなりますか。

田中「10万円を超えると部長の事前承認が要ります。それ以外はそのまま経理へ回ります」

聞いたことを example の行にします。

```
example 出張申請を提出する
    | "10万円を超えなければそのまま経理へ回る" :
        (福岡出張の申請, DateTime("2026-07-27T09:00:00"))
            -> 提出済み
```

右辺に `提出済み` と書きたいのに、いまのモデルにその名前はありません。あるのは `出張申請` だけで、`提出済み` は `ステータス` の中の値です。行が書けないので、状態を型に分けます。

```
data 申請準備中 =
    { 申請者ID: 従業員ID
    , 出張先: String
    , 予定費用: 金額
    }

data 提出済み =
    { 申請者ID: 従業員ID
    , 出張先: String
    , 予定費用: 金額
    , 提出日時: DateTime
    }

data 事前承認待ち =
    { 申請者ID: 従業員ID
    , 出張先: String
    , 予定費用: 金額
    , 提出日時: DateTime
    }

data 出張申請 = 申請準備中 | 提出済み | 事前承認待ち
```

`申請準備中` に `提出日時` はありません。提出前の申請に提出日時は存在しないからです。台帳で空欄だったところが、状態を分けたことで「その状態には無い」に変わります。`Option` は消えます。

`出張申請を提出する` は `申請準備中` を受け取り、2つのうちどちらかを返します。

```
behavior 出張申請を提出する : (申請: 申請準備中, 提出日時: DateTime) -> 提出済み | 事前承認待ち
    constructs 提出済み, 事前承認待ち
```

残り3つの behavior は 7 節と 8 節で戻ってきます。いまはファイルから外してください。1つの behavior を片付けてから次に行きます。

フィクスチャと行を書きます。`let` はまだ書きません。

```
let 福岡出張の申請 = 申請準備中
    { 申請者ID = 従業員ID("e-001")
    , 出張先 = "福岡"
    , 予定費用 = 金額(28000)
    }

example 出張申請を提出する
    | "10万円を超えなければそのまま経理へ回る" :
        (福岡出張の申請, DateTime("2026-07-27T09:00:00"))
            -> 提出済み
    | "10万円を超えると事前承認待ちになる" :
        (申請準備中 { ...福岡出張の申請, 予定費用 = 金額(120000) },
         DateTime("2026-07-27T09:00:00"))
            -> 事前承認待ち
```

回します。

```text
  出張申請を提出する       injected      rows 2    pending 2
    signature   out specified 2/2  observed 0/2  verified 0/2
    partition   not measured (no partition axis was derived at any position)
    boundary    not measured (no line was derived at any position)
      · not derivable: 申請.申請者ID
      · not derivable: 申請.出張先
      · not derivable: 申請.予定費用
      · not derivable: 提出日時
    branch      not applicable (this behavior has no body)

1 behavior: 0 implemented, 1 injected; 2 rows waiting for a `let`.
adequacy: undetermined
```

`specified 2/2` は、出力の2つのケースを行が両方名指ししたということです。`observed 0/2` は、まだ何も動かしていないので、その行が実際にそう答えるかは分かっていないということです。`2 rows waiting for a let` も同じことを別の言い方で書いています。

この時点で `100000` はモデルのどこにもありません。あるのは行の中の `120000` だけです。

## 3. `let` を書く

田中さんの言葉どおりに書きます。

```
let 出張申請を提出する (申請, 提出日時) =
    if 申請.予定費用.value > 100000
        then 事前承認待ち { ...申請, 提出日時 = 提出日時 }
        else 提出済み { ...申請, 提出日時 = 提出日時 }
```

回します。

```text
  出張申請を提出する       implemented   rows 2    pending 0
    signature   out specified 2/2  observed 2/2  verified 2/2
    partition   axes 1   single-axis 2/2
    boundary    0/2
      · no row is at 出張申請を提出する/申請.予定費用 = 100000 (guard@32:5)
      · no row is at 出張申請を提出する/申請.予定費用 = 100001 (guard@32:5)
      · not derivable: 申請.申請者ID
      · not derivable: 申請.出張先
      · not derivable: 提出日時
    branch      2/2

1 behavior: 1 implemented, 0 injected; 0 rows waiting for a `let`.
adequacy: not satisfied
```

`observed 2/2 verified 2/2` になりました。2つの行が実際にそう答えます。

`guard@32:5` の行番号は自分のファイルの行番号です。ここから先、レポートが位置を指すところは手元の数字と違います。

`boundary` に2行出ています。`> 100000` と書いたので、その両側にあたる `100000` と `100001` を要求しています。ここが行番号で名指しされているのは、10万円という数字がいまモデルの中で名前を持たず、`let` の本体のリテラルとしてしか存在しないからです。

そして、この2行は自分では埋められません。ちょうど10万円がどちらに転ぶのかを、読者は知らないからです。

## 4. 境界を聞きに行く

> これ、ちょうど10万円だったらどっちですか。

田中「規程には『10万円以上』と書いてありますね。ちょうど10万は要ります」

> その10万円ってどこから来た数字ですか。

田中「旅費規程です」

`> 100000` は間違いでした。直します。

```
    if 申請.予定費用.value >= 100000
```

境界の行を作らせます。

```sh
souther examples businesstrip.sou --generate --boundaries
```

レポートの後ろに、コメントアウトされた行が付いてきます。

```text
// generated by `souther examples --generate`: 2 rows to fill what nothing covers.
// Replace each `<?>` with what the system actually answers, then uncomment.
// example 出張申請を提出する
//     | "申請.予定費用 = 100000"
//         : (
//             申請準備中 { 申請者ID = 従業員ID("x"), 出張先 = "x", 予定費用 = 金額(100000) },
//             DateTime("2000-01-01T00:00:00")
//         )
//         -> <?>
//     | "申請.予定費用 = 99999"
//         : (
//             申請準備中 { 申請者ID = 従業員ID("x"), 出張先 = "x", 予定費用 = 金額(99999) },
//             DateTime("2000-01-01T00:00:00")
//         )
//         -> <?>
```

`<?>` を埋めてコメントを外します。名前と値は自分のフィクスチャに寄せて構いません。

```
    | "ちょうど10万円なら事前承認待ちになる" :
        (申請準備中 { ...福岡出張の申請, 予定費用 = 金額(100000) },
         DateTime("2026-07-27T09:00:00"))
            -> 事前承認待ち
    | "9万9999円ならそのまま経理へ回る" :
        (申請準備中 { ...福岡出張の申請, 予定費用 = 金額(99999) },
         DateTime("2026-07-27T09:00:00"))
            -> 提出済み
```

```text
  出張申請を提出する       implemented   rows 4    pending 0
    signature   out specified 2/2  observed 2/2  verified 2/2
    partition   axes 1   single-axis 2/2
    boundary    2/2
      · not derivable: 申請.申請者ID
      · not derivable: 申請.出張先
      · not derivable: 提出日時
    branch      2/2

1 behavior: 1 implemented, 0 injected; 0 rows waiting for a `let`.
adequacy: satisfied
```

`adequacy: satisfied` は、いまモデルが言っていることについて、行が足りているという意味です。モデルが正しいという意味ではありません。

## 5. 理由に名前を付ける

田中さんが席を立ちながら足します。

田中「あ、一般社員は金額に関わらず上長の承認が要ります」

素朴に条件を足します。役職の列は台帳になかったので、`役職` を申請に持たせます。

```
data 役職 = 管理職 | 一般社員

let 一般社員か (役職: 役職): Bool =
    match 役職 with
        | 管理職   -> false
        | 一般社員 -> true

let 出張申請を提出する (申請, 提出日時) =
    if 申請.予定費用.value >= 100000 || 一般社員か(申請.役職)
        then 事前承認待ち { ...申請, 提出日時 = 提出日時 }
        else 提出済み { ...申請, 提出日時 = 提出日時 }
```

3つの `data` に `役職: 役職` を足し、`福岡出張の申請` に `役職 = 管理職` を書き足すことになります。ここまでに書いた4行は全部、申請者が管理職だから通っていました。行にはそれが書いてありませんでした。

回します。

```text
  出張申請を提出する       implemented   rows 4    pending 0
    signature   out specified 2/2  observed 2/2  verified 2/2
    partition   axes 1   single-axis 1/2
      · no row is in `一般社員`
    boundary    not measured (no line was derived at any position)
      · not derivable: 申請.申請者ID
      · not derivable: 申請.出張先
      · not derivable: 申請.予定費用
      · not derivable: 提出日時
    branch      3/4
      · no row goes through `case 一般社員` (37:5)
```

`no row is in 一般社員` は足りない行を指しています。それは足せば済みます。

問題は `boundary` です。4節で `2/2` まで埋めたのが `not measured` に戻りました。条件が `||` でつながったので、10万円の線が導出できなくなっています。書いた2行はファイルに残っていますが、レポートはもう数えていません。理由を `if` に継ぎ足していくと、規程の閾値がレポートから見えなくなります。

理由を並べる形に変えます。田中さんが挙げた2つを、それぞれ名前のある `data` にします。

```
data 高額出張 = { 基準金額: 金額 }
data 権限不足 = { 役職: 役職 }
data 事前承認理由 = 高額出張 | 権限不足
data 事前承認理由リスト = List<事前承認理由>
```

`高額出張` が `基準金額` を持ちます。旅費規程の値の置き場所がここにできました。

候補を並べ、該当するものだけ残します。

```
let 事前承認理由の候補 (役職: 役職): List<事前承認理由> =
    [ 高額出張 { 基準金額 = 金額(100000) }
    , 権限不足 { 役職 = 役職 }
    ]

let 高額か (予定費用: 金額, 基準金額: 金額): Bool =
    予定費用.value >= 基準金額.value

let 該当するか (予定費用: 金額, 役職: 役職, 理由: 事前承認理由): Bool =
    match 理由 with
        | 高額出張 { 基準金額 } -> 高額か(予定費用, 基準金額)
        | 権限不足              -> 一般社員か(役職)

let 事前承認の理由 (予定費用: 金額, 役職: 役職): List<事前承認理由> =
    List.filter(理由 -> 該当するか(予定費用, 役職, 理由), 事前承認理由の候補(役職))

behavior 事前承認要否を判定する : (予定費用: 金額, 役職: 役職) -> 事前承認理由リスト
    constructs 事前承認理由リスト, 高額出張, 権限不足, 金額

let 事前承認要否を判定する (予定費用, 役職) =
    事前承認理由リスト(事前承認の理由(予定費用, 役職))

let 出張申請を提出する (申請, 提出日時) = {
    let 理由 = 事前承認の理由(申請.予定費用, 申請.役職)
    if List.isEmpty(理由)
        then 提出済み { ...申請, 提出日時 = 提出日時 }
        else 事前承認待ち { ...申請, 提出日時 = 提出日時
                        , 事前承認理由リスト = 事前承認理由リスト(理由) }
}
```

`事前承認待ち` に `事前承認理由リスト: 事前承認理由リスト` を足します。なぜ事前承認待ちなのかを、状態そのものが持つようになります。

一般社員の行を1本足して回します。

```text
  事前承認要否を判定する   implemented   rows 0    pending 0
    signature   not measured (no row names this behavior)
    partition   axes 1   single-axis 0/0   (1 not measured: no row names this behavior)
    boundary    not measured (no line was derived at any position)
      · not derivable: 予定費用
    branch      not measured (no row names this behavior)
  出張申請を提出する       implemented   rows 5    pending 0
    signature   out specified 2/2  observed 2/2  verified 2/2
    partition   axes 1   single-axis 2/2
    boundary    not measured (no line was derived at any position)
      · not derivable: 申請.申請者ID
      · not derivable: 申請.出張先
      · not derivable: 申請.予定費用
      · not derivable: 提出日時
    branch      8/8

2 behaviors: 2 implemented, 0 injected; 0 rows waiting for a `let`.
adequacy: undetermined
```

`branch 8/8`、`partition 2/2` です。理由を1つ足すと、どんなときに当たるのかを書くまでコンパイルが通りません。

```text
-- NON-EXHAUSTIVE MATCH  E1201 -----------------businesstrip.sou:53:5

53|     match 理由 with
        ^^^^^

This `match` on data `事前承認理由` does not cover every case.
Hint: Add a case for: 先方費用負担
```

`boundary` は `not measured` のままです。閾値が `基準金額` という値になったので、guard の線としては導出されなくなりました。10万円の両側を押さえるのは、これ以降は自分の仕事です。4節で書いた2行はそのまま残しておいてください。

代わりに `not derivable` が並んでいます。次はこれを聞きに行きます。

## 6. 何が有効な値かを聞く

`not derivable: 申請.出張先` は、`出張先` が裸の `String` で、どんな値が有効なのかをモデルが何も言っていないという意味です。値を作れないので、レポートはそこを測れません。

> 出張先には何を入れますか。空でもいいですか。

田中「都市名です。空はだめです」

> 金額にマイナスが入ることはありますか。

田中「ありません」

> 予定費用0円の申請は出せますか。

田中「明細が無いと出せません」

前の2つを invariant にします。

```
data 従業員ID = String
    invariant String.length(value) >= 1
data 金額 = Int
    invariant value >= 0
data 出張先 = String
    invariant String.length(value) >= 1
```

`出張先: String` を `出張先: 出張先` に、`出張先 = "福岡"` を `出張先 = 出張先("福岡")` に直します。3つめの答えは 7 節で扱います。

`事前承認要否を判定する` にも行が要ります。

```
example 事前承認要否を判定する
    | "管理職の10万円未満なら理由は挙がらない" :
        (金額(28000), 管理職)
            -> 事前承認理由リスト([])
    | "ちょうど10万円なら高額出張が挙がる" :
        (金額(100000), 管理職)
            -> 事前承認理由リスト([ 高額出張 { 基準金額 = 金額(100000) } ])
    | "一般社員なら権限不足が挙がる" :
        (金額(28000), 一般社員)
            -> 事前承認理由リスト([ 権限不足 { 役職 = 一般社員 } ])
```

回します。

```text
  事前承認要否を判定する   implemented   rows 3    pending 0
                in #2 specified 2/2
    partition   axes 1   single-axis 2/2
    boundary    0/1
      · no row is at 事前承認要否を判定する/予定費用 = 0 (invariant 金額 (min))
    branch      6/6
  出張申請を提出する       implemented   rows 5    pending 0
    signature   out specified 2/2  observed 2/2  verified 2/2
    partition   axes 1   single-axis 2/2
    boundary    0/3
      · no row is at 出張申請を提出する/String.length(申請.申請者ID) = 1 (invariant 従業員ID (min))
      · no row is at 出張申請を提出する/String.length(申請.出張先) = 1 (invariant 出張先 (min))
      · no row is at 出張申請を提出する/申請.予定費用 = 0 (invariant 金額 (min))
      · not derivable: 提出日時
    branch      8/8
```

`boundary` が数え始めました。今度は行番号ではなく、どの invariant が作った線かを名指しします。`--generate --boundaries` で行を作らせて埋めます。

`予定費用 = 0` の行は、田中さんが「出せません」と言ったものです。いまのモデルはそれを通してしまうので、通ってしまうことをそのまま行に書きます。

```
    | "予定費用0円の申請がいまは通ってしまう" :
        (申請準備中 { ...福岡出張の申請, 予定費用 = 金額(0) },
         DateTime("2026-07-27T09:00:00"))
            -> 提出済み
```

`従業員ID` と `出張先` の1文字の行も足すと、`adequacy: satisfied` に戻ります。

## 7. 精算のルールを聞く

> 精算額は実費用の合計ですか。

田中「立替分だけです。会社カードで払った分は経費で落ちているので」

> 仮払いは。

田中「あれも会社のお金なので精算しません」

> 先方が持ってくれた分は。

田中「それは会社を通らないので、そもそも精算の話になりません」

4つ出てきたので、そのまま並べます。あわせて、6節で保留にした「明細が無いと出せません」に答えるために、費用を明細のリストにします。

```
data 出発地 = String
    invariant String.length(value) >= 1
data 到着地 = String
    invariant String.length(value) >= 1
data 使用人数 = Int
    invariant value >= 1
data 費目名 = String
    invariant String.length(value) >= 1

data 交通費 = { 金額: 金額, 出発地: 出発地, 到着地: 到着地 }
data 宿泊費 = { 金額: 金額 }
data 交際費 = { 金額: 金額, 使用人数: 使用人数 }
data その他費用 = { 金額: 金額, 費目名: 費目名 }
data 費目 = 交通費 | 宿泊費 | 交際費 | その他費用

data 費用負担区分 = 立替 | 仮払い | 会社カード | 先方負担

data 費用明細 = { 費目: 費目, 負担: 費用負担区分 }

data 予定費用 = List<費用明細>
    invariant List.length(value) >= 1
data 実費用 = List<費用明細>
    invariant List.length(value) >= 1
data 精算額 = Int
    invariant value >= 0
```

費目ごとに持つ項目が違うので直和にします。明細のリストそのものに `予定費用` と `実費用` という名前を付けると、「空リストではない」をそのリストについての invariant として書けます。

合計と精算を書きます。

```
let 費目の金額 (費目: 費目): Int =
    match 費目 with
        | 交通費 { 金額 }     -> 金額.value
        | 宿泊費 { 金額 }     -> 金額.value
        | 交際費 { 金額 }     -> 金額.value
        | その他費用 { 金額 } -> 金額.value

let 明細の合計 (明細: List<費用明細>): Int =
    List.sum(List.map(明細 -> 費目の金額(明細.費目), 明細))

let 立替か (負担: 費用負担区分): Bool =
    match 負担 with
        | 立替       -> true
        | 仮払い     -> false
        | 会社カード -> false
        | 先方負担   -> false

behavior 精算額を計算する : (実費用: 実費用) -> 精算額
    constructs 精算額

let 精算額を計算する (実費用(明細)) =
    精算額(明細の合計(List.filter(明細 -> 立替か(明細.負担), 明細)))
```

`予定費用: 金額` を `予定費用: 予定費用` に直すと、`高額か` は合計を取ってから比べる形になります。

```
let 高額か (予定費用(明細), 基準金額: 金額): Bool =
    明細の合計(明細) >= 基準金額.value
```

ここまでに書いた行の `予定費用 = 金額(120000)` はすべて書き直しになります。金額を渡すだけの補助を1つ置くと短く済みます。

```
let 立替の交通費 (額: Int): 費用明細 = 費用明細
    { 費目 = 交通費 { 金額 = 金額(額), 出発地 = 出発地("東京"), 到着地 = 到着地("福岡") }
    , 負担 = 立替 }
```

`予定費用 = 予定費用([ 立替の交通費(120000) ])` のように書き換えます。

行が実際と食い違うと、コンパイラは何が返ったかを見せて止まります。

```text
-- EXAMPLE  E1905 -----------------------------businesstrip.sou:216:16

216|             -> 事前承認待ち
                    ^^^^^^^^^^^^

This example does not hold.

It is:
    提出済み { 申請者ID = "e-001", 役職 = "管理職", 出張先 = "福岡", 予定費用 = [ ... ], 提出日時 = "2026-07-27T09:00" }
But it needs to be:
    事前承認待ち
Hint: 費目が混ざっていても合計で判定する
```

`It is:` の行は実際には明細を展開した長い1行になります。ここでは `予定費用` の中身を省いています。

明細のフィクスチャを4つ置いて、精算の行を書きます。

```
let 福岡までの交通費 = 費用明細
    { 費目 = 交通費 { 金額 = 金額(28000), 出発地 = 出発地("東京"), 到着地 = 到着地("福岡") }
    , 負担 = 立替 }

let 会社カードの宿泊費 = 費用明細
    { 費目 = 宿泊費 { 金額 = 金額(12000) }
    , 負担 = 会社カード }

let 仮払いの交際費 = 費用明細
    { 費目 = 交際費 { 金額 = 金額(20000), 使用人数 = 使用人数(4) }
    , 負担 = 仮払い }

let 先方負担のその他費用 = 費用明細
    { 費目 = その他費用 { 金額 = 金額(5000), 費目名 = 費目名("会場費") }
    , 負担 = 先方負担 }

example 精算額を計算する
    | "払い戻すのは立替だけ" :
        (実費用([ 福岡までの交通費, 会社カードの宿泊費 ]))
            -> 精算額(28000)
```

```text
  精算額を計算する         implemented   rows 1    pending 0
    signature   not applicable (this behavior's output is not a sum)
    partition   not measured (no partition axis was derived at any position)
    boundary    0/1
      · no row is at 精算額を計算する/List.length(実費用) = 1 (invariant 実費用 (min))
    branch      5/10
      · no row goes through `case 仮払い` (80:5)
      · no row goes through `case 先方負担` (80:5)
      · no row goes through `case 宿泊費` (70:5)
      · no row goes through `case 交際費` (70:5)
      · no row goes through `case その他費用` (70:5)
```

`case 宿泊費` を通っていないのは、`会社カードの宿泊費` が `立替か` で先に落ちるので、その金額が読まれないからです。立替の宿泊費が混ざった行を書くと通ります。

`仮払い` と `先方負担` の行を書きながら、4つの区分が同じ `false` を返していることに気づきます。理由は同じではありません。仮払いと会社カードは会社のお金を使っているから、先方負担はそもそも会社を通っていないからです。前者を1つにまとめます。

```
data 自社負担 = 立替 | 仮払い | 会社カード
data 費用負担区分 = 自社負担 | 先方負担

let 立替か (負担: 費用負担区分): Bool =
    match 負担 with
        | 自社負担 as 自社 ->
            match 自社 with
                | 立替       -> true
                | 仮払い     -> false
                | 会社カード -> false
        | 先方負担 -> false
```

`match` が二段になり、「精算するのは自社負担のうちの立替だけ」というルールがそのまま構造に出ます。行の書き方は変わりません。`立替` も `先方負担` も `費用負担区分` のケースであることに変わりはないからです。

行を足して回します。

```text
  精算額を計算する         implemented   rows 4    pending 0
    signature   not applicable (this behavior's output is not a sum)
    partition   not measured (no partition axis was derived at any position)
    boundary    1/1
    branch      11/11
```

## 8. 失敗を出力に入れる

> 事前承認するのは誰ですか。

田中「申請者の上長です」

> 上長でない人が承認してしまうことはありますか。

田中「他人の申請は画面に出ないので、まず無いです」

> APIは叩けますよね。

田中「……そうですね。それは弾いてください」

> 出張から戻ったら何を出しますか。

田中「実費用の明細と報告書です。報告書が空のまま出てくることがあるので、それも弾いてください」

上長IDが要るので、申請者を1つの `data` にまとめます。状態も増えるので、共通項目を1つにします。

```
data 従業員 =
    { 従業員ID: 従業員ID
    , 役職: 役職
    , 上長ID: 従業員ID
    }

data 出張申請共通項目 =
    { 申請者: 従業員
    , 出張先: 出張先
    , 予定費用: 予定費用
    }

data 申請準備中 = { ...出張申請共通項目 }

data 提出済み =
    { ...出張申請共通項目
    , 提出日時: DateTime
    }
```

出張から戻った後の状態を足します。事前承認を経たかどうかは残るので、直和で持ちます。

```
data 出張報告 = String
    invariant String.length(value) >= 1

data 事前承認あり =
    { 事前承認日時: DateTime
    , 事前承認者ID: 従業員ID
    }

data 事前承認履歴 = 事前承認なし | 事前承認あり

data 事前承認済み =
    { ...出張申請共通項目
    , 提出日時: DateTime
    , 事前承認: 事前承認あり
    }

data 出張完了共通項目 =
    { ...出張申請共通項目
    , 提出日時: DateTime
    , 実費用: 実費用
    , 完了日時: DateTime
    , 出張報告: 出張報告
    , 事前承認: 事前承認履歴
    }

data 出張完了 = { ...出張完了共通項目 }
data 最終承認待ち = { ...出張完了共通項目 }

data 承認完了 =
    { ...出張完了共通項目
    , 最終承認日時: DateTime
    , 最終承認者ID: 従業員ID
    , 精算額: 精算額
    }

data 出張中 = 提出済み | 事前承認済み
```

`出張中` は、出張を完了できる状態を2つ束ねたものです。事前承認を経た申請も経なかった申請も完了できます。同じ `data` が `出張申請` と `出張中` の両方のケースになります。

弾くと言われたものを出力のケースにします。例外ではなく、シグネチャに書きます。

```
behavior 事前承認する : (申請: 事前承認待ち, 承認者ID: 従業員ID, 事前承認日時: DateTime)
    -> 事前承認済み | 承認権限なし
    constructs 事前承認済み, 事前承認あり, 承認権限なし

let 事前承認する (申請, 承認者ID, 事前承認日時) = {
    guard 承認者ID == 申請.申請者.上長ID else 承認権限なし
    事前承認済み { ...申請
        , 事前承認 = 事前承認あり { 事前承認日時 = 事前承認日時, 事前承認者ID = 承認者ID } }
}

behavior 出張を完了する : (申請: 出張中, 明細: List<費用明細>, 報告: String, 完了日時: DateTime)
    -> 出張完了 | 不正な実費用 | 出張報告なし
    constructs 出張完了, 実費用, 出張報告, 事前承認なし, 不正な実費用, 出張報告なし

let 出張を完了する (申請, 明細, 報告, 完了日時) = {
    guard 実費用(明細) as 実費 else 不正な実費用
    guard 明細の合計(明細) > 0 else 不正な実費用
    guard 出張報告(報告) as 報告文 else 出張報告なし

    match 申請 with
        | 提出済み as 提出 ->
            出張完了 { ...提出
                , 実費用 = 実費
                , 完了日時 = 完了日時
                , 出張報告 = 報告文
                , 事前承認 = 事前承認なし }
        | 事前承認済み as 承認済 ->
            出張完了 { ...承認済
                , 実費用 = 実費
                , 完了日時 = 完了日時
                , 出張報告 = 報告文
                , 事前承認 = 承認済.事前承認 }
}
```

`承認権限なし` と `不正な実費用` と `出張報告なし` に `data` の行はありません。直和のケース欄と behavior の出力に名前が出てくれば、それが宣言になります。

明細と報告は裸で受けて、その場で構築を試みます。空リストや空文字列は業務上ありうることなので、`guard` の `else` でケースを返します。何が空かを決めるのは `実費用` と `出張報告` の invariant なので、`let` の中には条件が書かれません。6節で田中さんが言った「明細が無いと出せません」は、`明細の合計(明細) > 0` の guard と `実費用` の invariant の2つで答えています。

残りをつなぎます。

```
behavior 最終承認を依頼する : (完了: 出張完了) -> 最終承認待ち
    constructs 最終承認待ち

let 最終承認を依頼する (完了) = 最終承認待ち { ...完了 }

let 立替の合計 (実費用(明細)): Int =
    明細の合計(List.filter(明細 -> 立替か(明細.負担), 明細))

behavior 最終承認する : (申請: 最終承認待ち, 承認者ID: 従業員ID, 最終承認日時: DateTime)
    -> 承認完了 | 承認権限なし
    constructs 承認完了, 精算額, 承認権限なし

let 最終承認する (申請, 承認者ID, 最終承認日時) = {
    guard 承認者ID == 申請.申請者.上長ID else 承認権限なし
    承認完了 { ...申請
        , 最終承認日時 = 最終承認日時
        , 最終承認者ID = 承認者ID
        , 精算額 = 精算額(立替の合計(申請.実費用)) }
}
```

`精算額を計算する` も `立替の合計` を呼ぶ形に直します。

`福岡出張の申請` は申請者を持つ形に変わるので、従業員のフィクスチャを2つ置きます。状態が増えたぶん、そこから重ねていきます。

```
let 管理職の申請者 = 従業員
    { 従業員ID = 従業員ID("e-001"), 役職 = 管理職, 上長ID = 従業員ID("m-001") }

let 一般社員の申請者 = 従業員
    { 従業員ID = 従業員ID("e-002"), 役職 = 一般社員, 上長ID = 従業員ID("m-001") }

let 福岡出張の申請 = 申請準備中
    { 申請者 = 管理職の申請者
    , 出張先 = 出張先("福岡")
    , 予定費用 = 予定費用([ 福岡までの交通費 ])
    }

let 提出された福岡出張 = 提出済み
    { ...福岡出張の申請, 提出日時 = DateTime("2026-07-27T09:00:00") }

let 事前承認を待つ横浜出張 = 事前承認待ち
    { 申請者 = 一般社員の申請者
    , 出張先 = 出張先("横浜")
    , 予定費用 = 予定費用([ 立替の交通費(3000) ])
    , 提出日時 = DateTime("2026-07-27T09:00:00")
    , 事前承認理由リスト = 事前承認理由リスト([ 権限不足 { 役職 = 一般社員 } ])
    }

let 事前承認済みの横浜出張 = 事前承認済み
    { 申請者 = 一般社員の申請者
    , 出張先 = 出張先("横浜")
    , 予定費用 = 予定費用([ 立替の交通費(3000) ])
    , 提出日時 = DateTime("2026-07-27T09:00:00")
    , 事前承認 = 事前承認あり { 事前承認日時 = DateTime("2026-07-27T10:00:00")
                            , 事前承認者ID = 従業員ID("m-001") }
    }

let 最終承認を待つ福岡出張 = 最終承認待ち
    { ...提出された福岡出張
    , 実費用 = 実費用([ 立替の交通費(31000), 会社カードの宿泊費 ])
    , 完了日時 = DateTime("2026-08-05T18:00:00")
    , 出張報告 = 出張報告("訪問して受注")
    , 事前承認 = 事前承認なし
    }
```

これまでに書いた行のうち、`役職 = 一般社員` で作っていたものは `申請者 = 一般社員の申請者` に、`申請者ID = 従業員ID("e")` で作っていたものは `申請者 = 従業員 { ...管理職の申請者, 従業員ID = 従業員ID("e") }` に直します。

失敗のケースにも行が要ります。

```
example 事前承認する
    | "上長なら事前承認できる" :
        (事前承認を待つ横浜出張, 従業員ID("m-001"), DateTime("2026-07-27T10:00:00"))
            -> 事前承認済み
    | "上長でなければ承認権限なし" :
        (事前承認を待つ横浜出張, 従業員ID("m-999"), DateTime("2026-07-27T10:00:00"))
            -> 承認権限なし

example 出張を完了する
    | "実費用が空なら不正な実費用" :
        (提出された福岡出張, [], "訪問して受注", DateTime("2026-08-05T18:00:00"))
            -> 不正な実費用
    | "合計が0円なら不正な実費用" :
        (提出された福岡出張, [ 立替の交通費(0) ], "訪問して受注",
         DateTime("2026-08-05T18:00:00"))
            -> 不正な実費用
    | "出張報告が空なら出張報告なし" :
        (提出された福岡出張, [ 立替の交通費(31000) ], "", DateTime("2026-08-05T18:00:00"))
            -> 出張報告なし
    | "事前承認を経ていなければ事前承認なしで完了する" :
        (提出された福岡出張, [ 立替の交通費(31000) ], "訪問して受注",
         DateTime("2026-08-05T18:00:00"))
            -> 出張完了
    | "事前承認を経ていればそれを持ち越して完了する" :
        (事前承認済みの横浜出張, [ 立替の交通費(3000) ], "打合せ完了",
         DateTime("2026-08-05T18:00:00"))
            -> 出張完了
```

回します。

```text
  事前承認する             implemented   rows 2    pending 0
    signature   out specified 2/2  observed 2/2  verified 2/2
    partition   axes 1   single-axis 1/2
      · no row is in `管理職`
    boundary    1/5
      · no row is at 事前承認する/String.length(申請.申請者.従業員ID) = 1 (invariant 従業員ID (min))
      · no row is at 事前承認する/String.length(申請.申請者.上長ID) = 1 (invariant 従業員ID (min))
      · no row is at 事前承認する/String.length(申請.出張先) = 1 (invariant 出張先 (min))
      · no row is at 事前承認する/String.length(承認者ID) = 1 (invariant 従業員ID (min))
      · not derivable: 申請.提出日時
      · not derivable: 申請.事前承認理由リスト
      · not derivable: 事前承認日時
    branch      2/2
  出張を完了する           implemented   rows 5    pending 0
    signature   out specified 3/3  observed 3/3  verified 3/3
                in #1 specified 2/2
    partition   axes 1   single-axis 2/2
    boundary    not measured (no line was derived at any position)
      · not derivable: 明細
      · not derivable: 報告
      · not derivable: 完了日時
    branch      9/12
      · no row goes through `case 宿泊費` (116:5)
      · no row goes through `case 交際費` (116:5)
      · no row goes through `case その他費用` (116:5)
```

`out specified 3/3` は、失敗の2ケースも出力として数えられているということです。失敗をシグネチャの外に出していたら、この数字は出ません。

`in #1 specified 2/2` は入力側です。`出張中` の2つのケースを、行が両方通っています。

## 9. レポートを作業リストとして使う

behavior が7つになると、レポート全体は長くなります。1つずつ見ます。

```sh
souther examples businesstrip.sou --behavior 最終承認する
```

```text
  最終承認する             implemented   rows 2    pending 0
    signature   out specified 2/2  observed 2/2  verified 2/2
    partition   axes 2   single-axis 2/4   pairs 1 reached / 1 known reachable, 3 untried
      · no row is in `一般社員`
      · no row is in `事前承認あり`
    boundary    1/7
```

軸が2つあります。`役職` と `事前承認履歴` です。`no row is in 事前承認あり` は、事前承認を経た申請が最終承認まで進む経路を、行が1本も通っていないということです。行を1本足します。

```
let 事前承認を経た最終承認待ち = 最終承認待ち
    { ...最終承認を待つ福岡出張
    , 申請者 = 一般社員の申請者
    , 事前承認 = 事前承認あり { 事前承認日時 = DateTime("2026-07-27T10:00:00")
                            , 事前承認者ID = 従業員ID("m-001") }
    }

example 最終承認する
    | "事前承認を経た一般社員の申請も上長が最終承認する" :
        (事前承認を経た最終承認待ち, 従業員ID("m-001"), DateTime("2026-08-06T09:00:00"))
            -> 承認完了
```

```text
    partition   axes 2   single-axis 4/4   pairs 2 reached / 2 known reachable, 2 untried
```

`pairs` は軸の組み合わせです。`2 untried` は、まだ試していない組み合わせが2つあるという意味で、そのうち到達可能なものはこの時点では分かりません。

`--generate` は、値を書ける行だけを出します。書けないものは理由を添えて断ります。

```text
// generated by `souther examples --generate`: 0 rows to fill what nothing covers.
// Replace each `<?>` with what the system actually answers, then uncomment.
// no row for `String.length(申請.申請者.上長ID) = 1` in `出張申請を提出する`: every value tried was refused at construction, which does not make the combination impossible
```

この場合は自分のフィクスチャから書きます。

CI に入れるときは `--strict` を使います。レポートが挙げた不足が1つでも残っていれば、終了コードが 0 以外になります。

```sh
souther examples businesstrip.sou --strict
```

```text
adequacy: not satisfied
24 gap(s) named above: the rows do not cover the model.
```

ここまで書いたモデルで 24 件残ります。ほとんどは invariant の下限を踏む行で、フィクスチャから1本ずつ書けば埋まります。

開発中のモデルが `not satisfied` なのは普通のことです。`--strict` を入れるのは、どこまで埋まっていれば通すかを決めたあとにしてください。

## 宿題

ここまでで、提出から最終承認までの本線ができました。`businesstrip/src/main/souther/businesstrip.sou` にはこれに加えて次が入っています。書いたファイルと見比べてください。

- 却下（`事前承認を却下する` と `最終承認を却下する`）。却下理由が空という業務上ありうることを、`却下理由` の invariant と `却下理由なし` のケースでどう扱っているか
- 差し戻し（`差し戻す`）。`却下済み = 事前承認却下済み | 最終承認却下済み` を受けて `申請準備中` に戻すとき、却下理由を持ち越さないことを型がどう強制しているか
- 先方費用負担が事前承認理由の3つめとして入っていること。理由を1つ足すと `該当するか` の `match` がどう反応するか
- 宿泊費の `インボイス登録番号`。`String.matches` を使った invariant
- 出張の開始日と終了日、およびその前後関係を `出張申請共通項目` の invariant に書いていること
- `businesstrip.examples.sou`。`examples for` で対象モジュールを名指しすると、行だけを別ファイルに置けます
