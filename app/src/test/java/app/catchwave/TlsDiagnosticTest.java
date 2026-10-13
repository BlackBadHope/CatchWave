package app.catchwave;
import javax.net.ssl.SSLException;
import org.junit.Test;
import static org.junit.Assert.*;
public class TlsDiagnosticTest {
    @Test public void diagnosticUsesFixedCategoriesWithoutExceptionText(){
        assertEquals("TLS_NOT_A_RECORD",LanFailure.tlsReason(new SSLException("WRONG_VERSION_NUMBER private-content")));
        assertEquals("TLS_PEER_CLOSED",LanFailure.tlsReason(new SSLException("Connection reset by peer private-content")));
        assertEquals("TLS_FAILURE_UNCLASSIFIED",LanFailure.tlsReason(new SSLException("private-content")));
    }
}
