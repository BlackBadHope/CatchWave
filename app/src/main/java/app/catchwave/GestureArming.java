// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

/** Button-only mode, with no pose prerequisite or timer. Sensor/permission faults still fail closed. */
final class GestureArming {
    private static final long TABLE_DWELL = 150;
    private static final double FLAT = Math.cos(Math.toRadians(15)),
            EXIT_FLAT = Math.cos(Math.toRadians(22));
    private boolean allowed, active, flatPosture;
    private long last = -1, flatSince = -1, revision;
    private double gravityZ;
    private String reason = "OFF";

    synchronized void permit(boolean value, long now) {
        if (value != allowed) {
            allowed = value;
            clear(value ? "LOCKED" : "OFF");
        }
        tick(now);
    }

    synchronized void reset(String why) {
        clear(why);
    }

    synchronized void displayChange() {
        if (active) {
            reason = "DISPLAY_NEUTRAL";
            revision++;
        }
    }

    private void clear(String why) {
        active = flatPosture = false;
        last = flatSince = -1;
        reason = allowed ? why : "OFF";
        revision++;
    }

    synchronized void tick(long now) {
        if (last >= 0 && (now < last || now - last > 150)) clear("SENSOR_GAP");
    }

    synchronized boolean active(long now) {
        tick(now);
        return allowed && active;
    }

    synchronized boolean commands(long now) {
        return active(now);
    }

    synchronized long revision() {
        return revision;
    }

    synchronized String reason() {
        return reason;
    }

    synchronized boolean activationReady() {
        return allowed && last >= 0;
    }

    synchronized boolean activate(long now) {
        tick(now);
        if (!activationReady() || active) return false;
        active = true;
        flatPosture = Math.abs(gravityZ) >= FLAT;
        flatSince = -1;
        reason = "ARMED";
        revision++;
        return true;
    }

    // Motion may refresh the neutral, but never turns this mode on or off.
    synchronized void sample(
            long now, double w, double x, double y, double z, double gyro, double accelNorm) {
        tick(now);
        if (!allowed) return;
        double norm = Math.sqrt(w * w + x * x + y * y + z * z);
        if (!Double.isFinite(norm)
                || norm < .5
                || norm > 1.5
                || !Double.isFinite(gyro)
                || !Double.isFinite(accelNorm)
                || now <= last) {
            clear("INVALID_SENSOR");
            return;
        }
        x /= norm;
        y /= norm;
        last = now;
        gravityZ = 1 - 2 * (x * x + y * y);
        if (!active) return;
        double flat = Math.abs(gravityZ);
        boolean quiet = gyro <= .18 && Math.abs(accelNorm - 9.81) <= 1.2;
        if (flatPosture && flat < EXIT_FLAT) {
            flatPosture = false;
            reason = "PICKUP_NEUTRAL";
            revision++;
        }
        if (!flatPosture && flat >= FLAT && quiet) {
            if (flatSince < 0) flatSince = now;
            if (now - flatSince >= TABLE_DWELL) {
                flatPosture = true;
                flatSince = -1;
                reason = "TABLE_NEUTRAL";
                revision++;
            }
        } else flatSince = -1;
    }

    synchronized String label(long now) {
        return !allowed
                ? "Управление выключено"
                : active(now)
                        ? "Управление активировано — без таймера"
                        : "Режим выключен — нажмите «Активировать управление»";
    }

    synchronized String explanation() {
        return switch (reason) {
            case "ARMED" -> "Режим включён кнопкой; положение телефона его не выключает";
            case "PICKUP_NEUTRAL", "TABLE_NEUTRAL", "DISPLAY_NEUTRAL" ->
                    "Активация сохранена: держите спокойно для новой нейтрали";
            case "MANUAL_NEUTRAL" ->
                    "Активация сброшена кнопкой; нажмите «Активировать управление»";
            case "MANUAL_OFF" -> "Режим выключен кнопкой; звук с ПК продолжается";
            case "SENSOR_GAP",
                    "SENSOR_STALE",
                    "GYRO_STALE",
                    "ACCEL_STALE",
                    "DEPENDENCY_STALE",
                    "SENSOR_ACCURACY",
                    "INVALID_SENSOR" ->
                    "Режим выключен: датчики недостоверны или измерения задержались ("
                            + reason
                            + ")";
            case "PERMISSION_CHANGE" -> "Разрешение изменилось: нужна новая активация кнопкой";
            default -> "Для управления нажмите «Активировать управление»";
        };
    }
}
