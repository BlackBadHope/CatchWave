// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

/** Button-only mode, with no pose prerequisite or timer. Sensor/permission faults still fail closed. */
final class GestureArming {
    private boolean allowed, active;
    private long last = -1, revision;
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
        active = false;
        last = -1;
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
        reason = "ARMED";
        revision++;
        return true;
    }

    // Crossing a flat-pose threshold can be the intended 30-degree volume gesture.
    // Keep the mode revision stable; the engine handles genuine off-axis posture changes.
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
        last = now;
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
            case "DISPLAY_NEUTRAL" ->
                    "Активация сохранена; направление наклонов соответствует экрану";
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
