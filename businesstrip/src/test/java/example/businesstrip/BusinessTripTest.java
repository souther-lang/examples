package example.businesstrip;

import net.unit8.raoh.Err;
import net.unit8.raoh.Ok;
import net.unit8.raoh.Path;
import net.unit8.raoh.Result;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * businesstrip.sou を境界から動かす。導出 decoder が入れ子の直和（費目・費用負担区分・役職）と
 * スプレッドで合成したフィールドを読み、申請準備中 から 承認完了 までの状態遷移を behavior が運ぶ。
 */
class BusinessTripTest {

    private static final Map<String, Object> 一般社員 =
            Map.of("従業員ID", "e-002",
                    "役職", Map.of("type", "一般社員"),
                    "上長ID", "m-001");

    /**
     * 交通費の1明細。費用負担区分 は 自社負担 という直和をケースに持つ入れ子だが、導出 decoder は
     * 葉まで畳んで判別するので、境界の JSON は 立替 / 会社カード という一段のタグで済む。
     */
    private static Map<String, Object> 交通費明細(long 金額, String 負担) {
        return Map.of(
                "費目", Map.of("type", "交通費", "金額", 金額, "出発地", "東京", "到着地", "福岡"),
                "負担", Map.of("type", 負担));
    }

    private static Map<String, Object> 申請(Object... 明細) {
        return Map.of(
                "申請者", 一般社員,
                // 予定費用 は List<費用明細> を包む newtype なので、境界ではリストそのまま
                // （`{"明細": [...]}` ではない）。
                "予定費用", List.of(明細),
                "出張先", "福岡",
                "出張目的", "顧客訪問",
                // Object 版の decoder は日付を LocalDate で受ける（JSON から読むなら
                // jsonDecoder() が ISO8601 のテキストを解釈する）。
                "出張開始日", LocalDate.parse("2026-08-03"),
                "出張終了日", LocalDate.parse("2026-08-05"));
    }

    /** Ok / Err はパターンマッチで見分ける（ワイルドカードもキャストも要らない）。 */
    private static <T> T ok(Result<T> result) {
        return switch (result) {
            case Ok<T> v -> v.value();
            case Err<T> e -> throw new AssertionError("decode に失敗した: " + e.issues());
        };
    }

    @Test
    void 入れ子の直和とスプレッドしたフィールドをdecodeする() {
        申請準備中 準備中 = ok(申請準備中.decoder().decode(申請(交通費明細(28000L, "立替")), Path.ROOT));

        Map<String, Object> 復元 = 申請準備中.encoder().encode(準備中);

        // 出張申請共通項目 をスプレッドしたフィールドも、decoder から見ればただのフィールド。
        assertEquals("福岡", 復元.get("出張先"));
    }

    @Test
    void ネストしたinvariant違反はErrになる() {
        // インボイス登録番号は T + 13桁。直和を2段降りた先の newtype の invariant も decode で効く。
        Map<String, Object> 不正な宿泊費 = Map.of(
                "費目", Map.of("type", "宿泊費", "金額", 12000L, "インボイス登録番号", "X999"),
                "負担", Map.of("type", "先方負担"));

        Result<申請準備中> 結果 = 申請準備中.decoder().decode(申請(不正な宿泊費), Path.ROOT);

        assertInstanceOf(Err.class, 結果);
    }

    @Test
    void 一般社員の申請は事前承認待ちになる() {
        申請準備中 準備中 = ok(申請準備中.decoder().decode(申請(交通費明細(3000L, "立替")), Path.ROOT));

        出張申請を提出するResult 結果 =
                出張申請を提出する.of().apply(準備中, LocalDateTime.parse("2026-07-27T09:00:00"));

        // 金額は3000円で高額出張には当たらないが、申請者が一般社員なので事前承認が要る。
        assertInstanceOf(事前承認待ち.class, 結果);
    }

    @Test
    void 提出から承認完了まで運ぶと精算額は立替分だけになる() {
        従業員ID 上長 = ok(従業員ID.decoder().decode("m-001", Path.ROOT));
        LocalDateTime 提出日時 = LocalDateTime.parse("2026-07-27T09:00:00");

        申請準備中 準備中 = ok(申請準備中.decoder().decode(申請(交通費明細(28000L, "立替")), Path.ROOT));
        事前承認待ち 待ち =
                assertInstanceOf(事前承認待ち.class, 出張申請を提出する.of().apply(準備中, 提出日時));
        事前承認済み 承認済み = assertInstanceOf(事前承認済み.class,
                事前承認する.of().apply(待ち, 上長, 提出日時.plusHours(1)));

        // 実費用は立替31000円と会社カード12000円。払い戻すのは立替だけ。
        List<費用明細> 実費 = List.of(
                ok(費用明細.decoder().decode(交通費明細(31000L, "立替"), Path.ROOT)),
                ok(費用明細.decoder().decode(交通費明細(12000L, "会社カード"), Path.ROOT)));
        出張完了 完了 = assertInstanceOf(出張完了.class,
                出張を完了する.of().apply(承認済み, 実費, "訪問して受注",
                        LocalDateTime.parse("2026-08-05T18:00:00")));

        最終承認待ち 最終待ち = 最終承認を依頼する.of().apply(完了);
        承認完了 完了済み = assertInstanceOf(承認完了.class,
                最終承認する.of().apply(最終待ち, 上長, LocalDateTime.parse("2026-08-06T09:00:00")));

        assertEquals(31000L, 承認完了.encoder().encode(完了済み).get("精算額"));
    }

    @Test
    void 上長でない承認者は承認権限なしで返る() {
        従業員ID 他人 = ok(従業員ID.decoder().decode("m-999", Path.ROOT));
        申請準備中 準備中 = ok(申請準備中.decoder().decode(申請(交通費明細(3000L, "立替")), Path.ROOT));
        事前承認待ち 待ち = assertInstanceOf(事前承認待ち.class,
                出張申請を提出する.of().apply(準備中, LocalDateTime.parse("2026-07-27T09:00:00")));

        // 承認できないことは例外ではなくケースとして返る。
        assertInstanceOf(承認権限なし.class,
                事前承認する.of().apply(待ち, 他人, LocalDateTime.parse("2026-07-27T10:00:00")));
    }
}
