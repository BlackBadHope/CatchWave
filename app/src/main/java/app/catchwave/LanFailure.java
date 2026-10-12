// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import java.io.*;
import java.net.*;
import java.security.cert.*;
import javax.net.ssl.*;

final class LanFailure {
    static final class Coded extends IOException {
        final LanConnection.Error code;

        Coded(LanConnection.Error code) {
            super(code.name());
            this.code = code;
        }
    }

    static final class PinMismatch extends CertificateException {}

    static LanConnection.Error classify(Throwable failure, LanConnection.Stage stage) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof Coded coded) return coded.code;
            if (cause instanceof PinMismatch) return LanConnection.Error.PIN_MISMATCH;
            if (cause instanceof CertificateExpiredException
                    || cause instanceof CertificateNotYetValidException)
                return LanConnection.Error.CERT_TIME;
        }
        if (failure instanceof ConnectException) return LanConnection.Error.CONNECT_REFUSED;
        if (failure instanceof SocketTimeoutException)
            return stage == LanConnection.Stage.TCP
                    ? LanConnection.Error.CONNECT_TIMEOUT
                    : LanConnection.Error.HOST_TIMEOUT;
        if (failure instanceof SSLException) return LanConnection.Error.TLS_HANDSHAKE;
        if (failure instanceof EOFException || failure instanceof SocketException) {
            if (stage == LanConnection.Stage.AUTH)
                return LanConnection.Error.AUTH_REJECTED_OR_CLOSED;
            return stage == LanConnection.Stage.TCP
                    ? LanConnection.Error.NETWORK
                    : LanConnection.Error.HOST_CLOSED;
        }
        if (failure instanceof IOException) return LanConnection.Error.PROTOCOL;
        return LanConnection.Error.INTERNAL;
    }

    private LanFailure() {}
}
