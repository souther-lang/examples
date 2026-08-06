package example.shippingfee;

import net.unit8.raoh.Err;
import net.unit8.raoh.Ok;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The same model, driven from the boundary rather than from an {@code example} row.
 *
 * <p>The rows in {@code shippingfee.examples.sou} are what this demo is about, and they are checked
 * by the compiler at build time. What is left for a JUnit test is the part the rows do not reach:
 * that an order arriving as a neutral map decodes into the model's own types, format rule and all,
 * and that what comes back encodes to something a caller can read.
 */
class ShippingFeeTest {

    private static 注文 order(String number, long total, String region, String membership) {
        Map<String, Object> raw = Map.of("番号", number, "合計", total,
                "地域", region, "会員", membership);
        return switch (注文.decoder().decode(raw)) {
            case Ok<注文> ok -> ok.value();
            case Err<注文> err -> throw new AssertionError("decode failed: " + err.issues());
        };
    }

    @Test
    void anOrderThatCrossesTheBoundaryIsCharged() {
        送料あり fee = assertInstanceOf(送料あり.class,
                送料を求める.of().apply(order("1000-000001", 4999L, "離島", "一般")));

        assertEquals(1500L, 送料あり.encoder().encode(fee).get("金額"));
    }

    @Test
    void anOrderNumberBreakingItsFormatDoesNotDecode() {
        Map<String, Object> raw = Map.of("番号", "1-2", "合計", 4999L,
                "地域", "本州", "会員", "一般");

        assertInstanceOf(Err.class, 注文.decoder().decode(raw),
                "the format rule is enforced where the value is built, not where it is used");
    }
}
