// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

/** Android's device-to-world quaternion, relative to a quiet neutral pose. */
final class RelativeOrientation {
    private double w, x, y, z, nw, nx, ny, nz, ax, ay, az, pw, px, py, pz;
    private boolean neutral, axisReady, pulsePose;
    private long last = -1;
    private int display;

    void reset() {
        neutral = false;
        axisReady = false;
        pulsePose = false;
        last = -1;
    }

    void display(int rotation) {
        display = rotation;
        if (neutral) rebase();
        else reset();
    }

    void clearNeutral() {
        neutral = false;
        axisReady = false;
    }

    boolean sample(long t, double a, double b, double c, double d, boolean calibrating) {
        double norm = Math.sqrt(a * a + b * b + c * c + d * d);
        if (!Double.isFinite(norm)
                || norm < .5
                || norm > 1.5
                || t <= last
                || (last >= 0 && t - last > 250)) {
            reset();
            return false;
        }
        w = a / norm;
        x = b / norm;
        y = c / norm;
        z = d / norm;
        last = t;
        if (calibrating) {
            rebase();
        }
        return true;
    }

    void rebase() {
        nw = w;
        nx = x;
        ny = y;
        nz = z;
        neutral = true;
        updateAxis();
    }

    private void updateAxis() {
        // Gravity in the neutral device frame: conjugate(q) * world-Z * q.
        double gx = 2 * (nx * nz - nw * ny),
                gy = 2 * (ny * nz + nw * nx),
                gz = 1 - 2 * (nx * nx + ny * ny);
        double rightX = display == 0 ? 1 : display == 2 ? -1 : 0,
                rightY = display == 1 ? 1 : display == 3 ? -1 : 0;
        // Fix during calibration: upright gets screen-Z, flat gets screen-Y.
        ax = -gz * rightY;
        ay = gz * rightX;
        az = gx * rightY - gy * rightX;
        double length = Math.sqrt(ax * ax + ay * ay + az * az);
        axisReady = length >= .3;
        if (axisReady) {
            ax /= length;
            ay /= length;
            az /= length;
        }
    }

    boolean flat() {
        return Math.abs(1 - 2 * (x * x + y * y)) >= Math.cos(Math.toRadians(15));
    }

    boolean fresh(long now) {
        return neutral && last >= 0 && now >= last && now - last <= 150;
    }

    boolean hasNeutral() {
        return neutral;
    }

    boolean tiltAvailable() {
        return neutral && axisReady;
    }

    long age(long now) {
        return last < 0 || now < last ? -1 : now - last;
    }

    void beginPulse() {
        pw = w;
        px = x;
        py = y;
        pz = z;
        pulsePose = last >= 0;
    }

    double pulseRotation() {
        if (!pulsePose) return Double.NaN;
        // Quaternion distance is sign invariant. A short gyro spike alone is not a turn.
        double dot = Math.abs(pw * w + px * x + py * y + pz * z);
        return 2 * Math.acos(Math.min(1, dot));
    }

    boolean postureChanged() {
        if (!tiltAvailable()) return false;
        double rw = nw * w + nx * x + ny * y + nz * z;
        double rx = nw * x - nx * w - ny * z + nz * y;
        double ry = nw * y + nx * z - ny * w - nz * x;
        double rz = nw * z - nx * y + ny * x - nz * w;
        double twist = rx * ax + ry * ay + rz * az;
        // A held roll is pure twist. Putting the phone down is an off-axis swing;
        // the old roll axis must not turn that new posture into a volume command.
        double swing = 2 * Math.acos(Math.min(1, Math.hypot(rw, twist)));
        return swing > Math.toRadians(35);
    }

    double tilt() {
        if (!tiltAvailable()) return Double.NaN;
        // conjugate(neutral) * current: every quaternion component participates.
        double rw = nw * w + nx * x + ny * y + nz * z;
        double rx = nw * x - nx * w - ny * z + nz * y;
        double ry = nw * y + nx * z - ny * w - nz * x;
        double rz = nw * z - nx * y + ny * x - nz * w;
        if (rw < 0) {
            rw = -rw;
            rx = -rx;
            ry = -ry;
            rz = -rz;
        }
        double axis = rx * ax + ry * ay + rz * az;
        // Swing/twist projection becomes ambiguous near a 180-degree off-axis swing.
        if (Math.hypot(rw, axis) < .25) return Double.NaN;
        return 2 * Math.atan2(axis, rw);
    }
}
