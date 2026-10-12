package app.catchwave;

import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import org.junit.Test;
import static org.junit.Assert.*;

public class QrRoundTripTest {
    @Test
    public void decodeActualWindowsQrWithAndroidDecoder() throws Exception {
        try (var input = getClass().getResourceAsStream("/pairing-fixture.png")) {
            assertNotNull("Windows self-test fixture is required", input);
            // Java SE ImageIO is available on the host test JVM, outside Android's compile
            // bootclasspath.
            Object image =
                    Class.forName("javax.imageio.ImageIO")
                            .getMethod("read", java.io.InputStream.class)
                            .invoke(null, input);
            int w = (Integer) image.getClass().getMethod("getWidth").invoke(image),
                    h = (Integer) image.getClass().getMethod("getHeight").invoke(image);
            int[] pixels =
                    (int[])
                            image.getClass()
                                    .getMethod(
                                            "getRGB",
                                            int.class,
                                            int.class,
                                            int.class,
                                            int.class,
                                            int[].class,
                                            int.class,
                                            int.class)
                                    .invoke(image, 0, 0, w, h, null, 0, w);
            Result result =
                    new MultiFormatReader()
                            .decode(
                                    new BinaryBitmap(
                                            new HybridBinarizer(
                                                    new RGBLuminanceSource(w, h, pixels))));
            PairingCode p = PairingCode.parse(result.getText());
            byte[] token = new byte[32], pin = new byte[32];
            for (int i = 0; i < 32; i++) {
                token[i] = (byte) i;
                pin[i] = (byte) (255 - i);
            }
            assertEquals("192.0.2.1", p.host);
            assertArrayEquals(token, p.token);
            assertArrayEquals(pin, p.pin);
            assertEquals(BarcodeFormat.QR_CODE, result.getBarcodeFormat());
        }
    }
}
