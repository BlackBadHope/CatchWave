// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

/** Published by the sole network reader while Android prepares focus/output. */
final class LanFreshStream {
    static final int RECOVERY_MS = 5000;
    private volatile long arrival = -1;

    void received(long at) {
        arrival = at;
    }

    boolean fresh(long now) {
        long at = arrival;
        return at >= 0 && now >= at && now - at < 750;
    }

    boolean timedOut(long now) {
        long at = arrival;
        return at < 0 || now < at || now - at >= RECOVERY_MS;
    }
}
