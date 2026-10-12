// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import android.os.Handler;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

final class LanFocus {
    static CompletableFuture<Boolean> request(
            Handler main, BooleanSupplier allowed, BooleanSupplier fresh, BooleanSupplier acquire) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (!main.post(
                () -> {
                    try {
                        result.complete(
                                allowed.getAsBoolean()
                                        && fresh.getAsBoolean()
                                        && acquire.getAsBoolean());
                    } catch (RuntimeException failure) {
                        result.complete(false);
                    }
                })) result.complete(false);
        return result;
    }

    private LanFocus() {}
}
