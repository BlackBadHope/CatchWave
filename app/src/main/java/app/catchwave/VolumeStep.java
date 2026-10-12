// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

/** Read limits on each step and never issue an adjustment at a fixed or reached boundary. */
final class VolumeStep {
    interface Device {
        int current();

        int min();

        int max();

        boolean fixed();

        void adjust(int direction);
    }

    static final class Result {
        final int before, after;
        final boolean adjusted;

        Result(int before, int after, boolean adjusted) {
            this.before = before;
            this.after = after;
            this.adjusted = adjusted;
        }
    }

    static Result apply(Device device, int direction) {
        int before = device.current();
        boolean valid = direction == 1 || direction == -1;
        boolean change =
                valid
                        && !device.fixed()
                        && (direction > 0 ? before < device.max() : before > device.min());
        if (change) device.adjust(direction);
        return new Result(before, device.current(), change);
    }
}
