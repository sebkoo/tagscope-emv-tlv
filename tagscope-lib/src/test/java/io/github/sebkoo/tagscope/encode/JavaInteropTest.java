package io.github.sebkoo.tagscope.encode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.sebkoo.tagscope.decode.DecodeResult;
import io.github.sebkoo.tagscope.decode.DecodedValue;
import io.github.sebkoo.tagscope.decode.ValueDecoder;
import io.github.sebkoo.tagscope.tags.TagDictionary;
import io.github.sebkoo.tagscope.tags.TagInfo;
import io.github.sebkoo.tagscope.tags.TagLookup;
import io.github.sebkoo.tagscope.tlv.TlvNode;
import io.github.sebkoo.tagscope.tlv.TlvParser;
import io.github.sebkoo.tagscope.tlv.TlvResult;
import io.github.sebkoo.tagscope.tlv.TlvTag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The library is callable from Java, not only from Kotlin.
 *
 * The one Java source file in the repository, and it earns its place by exercising the whole path a
 * Java caller takes — parse, dictionary lookup, decode, build — rather than by touching one method.
 * Every type crossing the boundary is checked here in passing: the sealed results unwrap with
 * {@code assertInstanceOf}, the Kotlin {@code object}s are reached through {@code INSTANCE} (no
 * {@code @JvmStatic} anywhere in this library, deliberately, so this is what a Java consumer really
 * writes), {@code Map<TlvTag, byte[]>} is an ordinary Java map, and {@code DecodedValue.Dol} is an
 * ordinary nested type.
 *
 * The values are synthetic terminal parameters. A DOL never requests the PAN or Track 2.
 */
class JavaInteropTest {

    @Test
    void decodesAPdolAndBuildsItsCommandData() {
        // The PDOL of golden vector 2, as it appears inside the Visa FCI:
        // 9F38 0C, then the four (tag, length) entries (9F33,3)(9F1A,2)(9F35,1)(9F40,5).
        byte[] pdol = {
            (byte) 0x9F, 0x38, 0x0C,
            (byte) 0x9F, 0x33, 0x03,
            (byte) 0x9F, 0x1A, 0x02,
            (byte) 0x9F, 0x35, 0x01,
            (byte) 0x9F, 0x40, 0x05,
        };

        TlvResult.Success<?> parsed =
                assertInstanceOf(TlvResult.Success.class, TlvParser.INSTANCE.parse(pdol));
        TlvNode node = (TlvNode) ((List<?>) parsed.getValue()).get(0);

        TagInfo info =
                assertInstanceOf(TagLookup.Known.class, TagDictionary.INSTANCE.lookup(node.getTag()))
                        .getInfo();
        DecodeResult.Success decoded =
                assertInstanceOf(DecodeResult.Success.class, ValueDecoder.INSTANCE.decode(node, info));
        DecodedValue.Dol dol = assertInstanceOf(DecodedValue.Dol.class, decoded.getValue());

        Map<TlvTag, byte[]> terminalData = new LinkedHashMap<>();
        terminalData.put(new TlvTag(0x9F33L, 2), new byte[] {(byte) 0xE0, (byte) 0xF8, (byte) 0xC8});
        terminalData.put(new TlvTag(0x9F1AL, 2), new byte[] {0x08, 0x40});
        terminalData.put(new TlvTag(0x9F35L, 2), new byte[] {0x22});

        EncodeResult.Success built =
                assertInstanceOf(EncodeResult.Success.class, DolEncoder.INSTANCE.build(dol, terminalData));

        // 9F40 was never supplied, so its five octets fill with zeroes: EMV Book 3 v4.4, section 5.4.
        byte[] expected = {
            (byte) 0xE0, (byte) 0xF8, (byte) 0xC8, 0x08, 0x40, 0x22, 0x00, 0x00, 0x00, 0x00, 0x00,
        };
        assertArrayEquals(expected, built.bytes());
    }
}
