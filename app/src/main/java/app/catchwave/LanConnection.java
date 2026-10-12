// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
/** Fixed public diagnostic codes; never exception text or credentials. */
final class LanConnection {
    enum Stage { DISCONNECTED, SCANNED, STARTING, TCP, TLS, AUTH, AUTHENTICATED, FORMAT, FOCUS, OUTPUT, SILENCE, STREAMING, ERROR, BUFFERING }
    enum Error { NONE, ANDROID_START, CONNECT_TIMEOUT, CONNECT_REFUSED, NETWORK, TLS_HANDSHAKE, PIN_MISMATCH, CERT_TIME,
        AUTH_REJECTED_OR_CLOSED, PROTOCOL, HOST_TIMEOUT, HOST_CLOSED, AUDIO_FOCUS, AUDIO_FOCUS_LOST, PCM_OUTPUT, AUDIO_STALLED, INTERNAL }
    static String title(Stage stage) { return switch(stage) {
        case DISCONNECTED -> "Нет соединения"; case SCANNED -> "QR подтверждён — готово к подключению";
        case STARTING -> "Запуск сервиса Android…"; case TCP -> "Подключение к ПК…"; case TLS -> "Проверка защищённого соединения…";
        case AUTH -> "Подтверждение сопряжения…"; case AUTHENTICATED -> "Сопряжение принято ПК";
        case FORMAT -> "Формат звука получен"; case FOCUS -> "Получение аудиофокуса…"; case OUTPUT -> "Подготовка звука телефона…";
        case SILENCE -> "Соединение работает — принимается тишина"; case STREAMING -> "Звук с ПК воспроизводится"; case ERROR -> "Соединение остановлено — ошибка";
        case BUFFERING -> "Соединение сохранено — ожидается свежий звук";
    }; }
    static String remedy(Error error) { return switch(error) {
        case NONE -> "";
        case ANDROID_START -> "Android не разрешил запустить сервис. Откройте CatchWave и повторите подключение.";
        case CONNECT_TIMEOUT -> "ПК не ответил на TCP. Проверьте адрес, WiFi и доступность порта Host.";
        case CONNECT_REFUSED -> "Порт ПК не принимает соединение. Запустите Host; после его перезапуска нужен новый QR.";
        case NETWORK -> "Сетевое соединение прервалось. Проверьте связь с ПК.";
        case TLS_HANDSHAKE -> "TLS не установлен. Проверьте совпадение версии Host и приложения.";
        case PIN_MISMATCH -> "Сертификат Host изменился. Сканируйте новый QR из текущего окна Host.";
        case CERT_TIME -> "Сертификат вне срока действия. Проверьте дату телефона и ПК; перезапустите Host и сканируйте новый QR.";
        case AUTH_REJECTED_OR_CLOSED -> "Host закрыл соединение до подтверждения сопряжения. Нужен QR текущего Host; точная причина видна на ПК.";
        case PROTOCOL -> "Получены несовместимые данные. Используйте приложение и Host из одного комплекта.";
        case HOST_TIMEOUT -> "Host перестал передавать данные. Проверьте состояние в окне ПК.";
        case HOST_CLOSED -> "Host закрыл соединение. После его перезапуска сканируйте новый QR.";
        case AUDIO_FOCUS -> "Android не дал аудиофокус. Другой проигрыватель мог сохранить управление звуком.";
        case AUDIO_FOCUS_LOST -> "Android отдал аудиофокус другому приложению.";
        case PCM_OUTPUT -> "Не удалось запустить или продолжить вывод PCM на телефоне.";
        case AUDIO_STALLED -> "Звуковые кадры устарели. Соединение остановлено, чтобы не воспроизводить накопленный звук.";
        case INTERNAL -> "Неожиданная ошибка CatchWave. Сообщите код и этап, показанные здесь.";
    }; }
    static boolean effectiveControl(boolean live,boolean desired,boolean hostAllowed) { return live&&desired&&hostAllowed; }
    private LanConnection() {}
}
