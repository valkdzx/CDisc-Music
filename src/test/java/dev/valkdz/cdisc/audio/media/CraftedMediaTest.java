package dev.valkdz.cdisc.audio.media;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class CraftedMediaTest {

    private static byte[] box(String type, byte[]... children) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] child : children) body.writeBytes(child);
        return ByteBuffer.allocate(8 + body.size()).putInt(8 + body.size())
                .put(type.getBytes(StandardCharsets.ISO_8859_1)).put(body.toByteArray()).array();
    }

    private static byte[] raw(int... words) {
        ByteBuffer out = ByteBuffer.allocate(words.length * 4);
        for (int word : words) out.putInt(word);
        return out.array();
    }

    private static byte[] ftyp() {
        return box("ftyp", "M4A ".getBytes(StandardCharsets.ISO_8859_1), raw(0));
    }

    private static void refuse(byte[]... boxes) {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        for (byte[] b : boxes) file.writeBytes(b);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(IOException.class,
                () -> Media.open(MediaInput.of(file.toByteArray()), null).close()));
    }

    @Test
    void zeroSizedTagBoxDoesNotLoopForever() {
        byte[] zeroSized = raw(0, 0x66726565);
        refuse(ftyp(), box("moov", box("udta", box("meta", raw(0), box("ilst", zeroSized, raw(0, 0))))));
    }

    @Test
    void zeroSizedTrackExtendsBoxDoesNotLoopForever() {
        refuse(ftyp(), box("moov", box("mvex", raw(0, 0x74726578), raw(0, 0, 0, 0, 0))));
    }

    @Test
    void tableLongerThanItsBoxAllocatesNothing() {
        byte[] stts = box("stts", raw(0, Integer.MAX_VALUE));
        refuse(ftyp(), box("moov", box("trak", box("mdia", box("minf", box("stbl", stts))))));
    }

    @Test
    void blockLargerThanTheStreamIsRefusedBeforeAllocating() {
        MediaReader in = new MediaReader(MediaInput.of(new byte[16]));
        assertThrows(IOException.class, () -> in.bytes(Integer.MAX_VALUE));
        assertThrows(IOException.class, () -> in.bytes(-1));
        assertThrows(IOException.class, () -> in.bytes(17));
    }
}
