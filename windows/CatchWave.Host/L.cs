// SPDX-License-Identifier: GPL-3.0-or-later
namespace CatchWave.Host;

/** UI language. English is the default; Russian is an explicit choice saved per Windows user. */
internal static class L
{
    private static readonly string SettingsPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CatchWave", "language.txt");
    internal static bool Russian { get; set; } = Load();

    private static bool Load()
    {
        try { return File.Exists(SettingsPath) && File.ReadAllText(SettingsPath).Trim() == "ru"; }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { return false; }
    }

    /** Saves the choice for the next Host start; the current window keeps its language. */
    internal static bool Save(bool russian)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(SettingsPath)!);
            File.WriteAllText(SettingsPath, russian ? "ru" : "en");
            return true;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { return false; }
    }

    internal static string T(string english) => Russian && Ru.TryGetValue(english, out var value) ? value : english;

    internal static IReadOnlyDictionary<string, string> RussianTable => Ru;

    private static readonly Dictionary<string, string> Ru = new()
    {
        ["Audio output "] = "Аудиовыход ",
        [" · Windows default"] = " · Windows по умолчанию",
        ["unavailable"] = "недоступен",
        ["not connected"] = "не подключён",
        ["device missing"] = "устройство отсутствует",
        ["disabled in Windows"] = "выключен в Windows",
        ["available"] = "доступен",
        ["output missing"] = "выход отсутствует",
        ["Send Windows audio to this same output."] = "Направьте звук Windows на этот же выход.",
        ["Disconnect the phone to change the source."] = "Для смены источника отключите телефон.",
        ["The source can be changed without reconnecting the phone."] = "Источник можно менять без переподключения телефона.",
        [" Choose another output."] = " Выберите другой выход.",
        ["Source unavailable"] = "Источник недоступен",
        ["Windows could not prepare audio for this output. Choose another one."] = "Windows не смогла подготовить звук этого выхода. Выберите другой.",
        ["Choice not saved; it applies until the Host is closed."] = "Выбор не сохранён; действует до закрытия Host.",
        ["Windows default"] = "Windows по умолчанию",
        ["Local network"] = "Локальная сеть",
        ["USB tethering"] = "USB-модем",
        ["Headphones · sample audio output"] = "Наушники · пример аудиовыхода",
        ["Connect USB tethering or a local network"] = "Подключите USB-модем или локальную сеть",
        ["Address unavailable. Check USB tethering or the local network."] = "Адрес недоступен. Проверьте USB-модем или локальную сеть.",
        ["Change connection?"] = "Сменить подключение?",
        ["Audio will stop. After changing the connection, scan the QR again."] = "Звук остановится. После смены подключения отсканируйте QR снова.",
        ["Could not read connections. Try again."] = "Не удалось прочитать подключения. Повторите попытку.",
        ["No Windows audio outputs"] = "Нет аудиовыходов Windows",
        ["Could not read audio outputs. Choose again."] = "Не удалось прочитать аудиовыходы. Повторите выбор.",
        ["Connecting the previously configured USB port. Android permission keeps priority."] = "Подключаем ранее настроенный USB-порт. Разрешение Android сохраняет приоритет.",
        ["Allow CatchWave USB access on the phone."] = "Разрешите USB-доступ CatchWave на телефоне.",
        ["Connect"] = "Подключить",
        ["Choose the CatchWave phone"] = "Выберите телефон CatchWave",
        ["Connect the phone with a data cable."] = "Подключите телефон кабелем с передачей данных.",
        ["A regular cable needs a signed USB component on the PC. Installation may briefly restart USB devices and may require a PC restart. Android debugging and USB tethering are not needed. Install the component?"] = "Для обычного кабеля нужен подписанный USB-компонент на ПК. Установка может кратко перезапустить USB-устройства и потребовать перезапуска ПК. Отладка Android и USB-модем не нужны. Установить компонент?",
        ["The phone is already connecting or connected via USB."] = "Телефон уже подключается или подключён по USB.",
        ["USB connection ended. Check the cable and the CatchWave permission on the phone."] = "USB-соединение завершено. Проверьте кабель и разрешение CatchWave на телефоне.",
        ["Windows could not open the phone over USB. Reconnect the cable; Wi-Fi remains available."] = "Windows не смогла открыть телефон по USB. Переподключите кабель; Wi-Fi остаётся доступен.",
        ["The Windows USB component rejected the request. Connect via Wi-Fi; the error code is in diagnostics."] = "USB-компонент Windows отклонил запрос. Подключитесь по Wi-Fi; код ошибки есть в диагностике.",
        ["The phone does not support USB accessory mode."] = "Телефон не поддерживает режим USB-аксессуара.",
        ["The USB component is busy. Try connecting again."] = "USB-компонент занят. Повторите подключение.",
        ["Windows does not allow access to the USB component."] = "Windows не разрешает доступ к USB-компоненту.",
        ["Restart the PC to finish installing the USB component. Wi-Fi is available."] = "Перезапустите ПК для завершения установки USB-компонента. Wi-Fi доступен.",
        ["A cable connection needs the CatchWave USB component."] = "Для подключения по кабелю нужен USB-компонент CatchWave.",
        ["USB setup cancelled. Wi-Fi remains available."] = "Настройка USB отменена. Wi-Fi остаётся доступен.",
        ["USB setup complete. Reconnect the cable and try connecting again."] = "Настройка USB завершена. Переподключите кабель и повторите подключение.",
        ["Could not set up the USB connection in Windows. Details are in diagnostics."] = "Не удалось настроить USB-подключение в Windows. Подробности — в диагностике.",
        ["This USB connection needs manual setup. Press «Connect via USB»."] = "Для этого USB-подключения нужна ручная настройка. Нажмите «Подключить по USB».",
        ["No connection? Check the shared Wi-Fi network, the firewall for private networks and the router's client isolation."] = "Нет подключения? Проверьте общую Wi-Fi сеть, брандмауэр для частной сети и изоляцию клиентов роутера.",
        ["Allow control on the PC"] = "Разрешить управление на ПК",
        ["PC permission on · turn off"] = "Разрешение ПК включено · выключить",
        ["Allow control for this phone"] = "Разрешить управление этому телефону",
        ["New phone key. Compare the fingerprint in the phone diagnostics and allow control once."] = "Новый ключ телефона. Сверьте отпечаток в диагностике телефона и разрешите управление один раз.",
        ["Gestures are ready on the phone. Tilting changes the phone volume."] = "Жесты готовы на телефоне. Наклон меняет громкость телефона.",
        ["The phone blocked commands: activate control with the button and hold still for 1.5 seconds"] = "Телефон заблокировал команды: активируйте управление кнопкой и держите спокойно 1,5 секунды",
        ["PC permission is on; enable control on the phone"] = "Разрешение ПК включено; включите управление на телефоне",
        ["PC permission is on. Connect the phone."] = "Разрешение ПК включено. Подключите телефон.",
        ["PC permission is off"] = "Разрешение ПК выключено",
        ["Commands blocked by a launch flag"] = "Команды заблокированы флагом запуска",
        ["Activate on the phone"] = "Активируйте на телефоне",
        ["No PC permission"] = "Нет разрешения ПК",
        ["Allow this phone"] = "Разрешите этот телефон",
        ["Waiting for the phone"] = "Ожидаем телефон",
        ["Gestures ready"] = "Жесты готовы",
        ["The phone is receiving audio"] = "Телефон принимает звук",
        ["The phone is receiving silence"] = "Телефон принимает тишину",
        ["Waiting for phone output"] = "Ожидаем вывод телефона",
        ["Phone output not confirmed"] = "Вывод телефона не подтверждён",
        ["Check the status on the phone and open diagnostics."] = "Проверьте состояние на телефоне и откройте диагностику.",
        ["Check that the Host and Android versions are compatible."] = "Проверьте совместимость версий Host и Android.",
        ["You can connect the phone again."] = "Можно подключить телефон снова.",
        ["The Windows audio output is unavailable. Open diagnostics."] = "Аудиовыход Windows недоступен. Откройте диагностику.",
        ["Could not establish a secure connection."] = "Не удалось установить защищённое соединение.",
        ["Scan the QR of the current Host."] = "Сканируйте QR текущего Host.",
        ["Connection error"] = "Ошибка соединения",
        ["Connected · "] = "Подключено · ",
        ["Connection lost"] = "Связь прервалась",
        ["Phone disconnected"] = "Телефон отключён",
        ["Sending audio"] = "Звук отправляется",
        ["Silence from PC"] = "Тишина с ПК",
        ["Starting audio…"] = "Запуск звука…",
        ["Checking pairing…"] = "Проверка сопряжения…",
        ["Connecting…"] = "Подключение…",
        ["Connect your phone"] = "Подключите телефон",
        ["Audio source: "] = "Источник звука: ",
        ["Audio: "] = "Звук: ",
        ["Disabling was not saved. After a restart, check the PC permission."] = "Выключение не сохранено. После перезапуска проверьте разрешение ПК.",
        ["Enabling was not saved; it applies only to this session."] = "Включение не сохранено; оно действует только в этом сеансе.",
        ["The system did not allow writing diagnostics"] = "Система запретила запись диагностики",
        ["Could not save diagnostics"] = "Не удалось сохранить диагностику",
        ["CatchWave — last 256 events"] = "CatchWave — последние 256 событий",
        ["Diagnostics"] = "Диагностика",
        ["Close diagnostics"] = "Закрыть диагностику",
        ["Connect phone"] = "Подключить телефон",
        ["Hide QR"] = "Скрыть QR",
        ["Control from the phone"] = "Управление с телефона",
        ["Allow the phone to control this PC's player? Control must also be allowed and activated on the phone."] = "Разрешить телефону управлять проигрывателем этого ПК? На телефоне управление также нужно разрешить и активировать.",
        ["The phone changed or the permission was not saved. Check again."] = "Телефон сменился или разрешение не сохранено. Повторите проверку.",
        ["Allow phone"] = "Разрешить телефон",
        ["\n\nAllow this phone to send commands to the PC? The QR by itself allows audio only."] = "\n\nРазрешить этому телефону команды на ПК? QR сам по себе разрешает только звук.",
        ["Compare the control key fingerprint in CatchWave diagnostics on the phone:\n\n"] = "Сверьте отпечаток ключа управления в диагностике CatchWave на телефоне:\n\n",
        ["Disconnect and close Host"] = "Отключить и закрыть Host",
        ["Audio: Windows default"] = "Звук: Windows по умолчанию",
        ["Test source: silence"] = "Тестовый источник: тишина",
        ["Phone audio"] = "Звук телефона",
        ["Previous"] = "Предыдущий",
        ["Next"] = "Следующий",
        ["Pause / play"] = "Пауза / старт",
        ["Taps on the phone"] = "Постукивания по телефону",
        ["Export diagnostics"] = "Экспорт диагностики",
        ["Open log"] = "Открыть журнал",
        ["The QR grants access to audio. Do not send photos of it.\nAfter a Host restart a new QR is needed."] = "QR открывает доступ к звуку. Не отправляйте фото.\nПосле перезапуска Host нужен новый QR.",
        ["Scan the QR in CatchWave"] = "Сканируйте QR в CatchWave",
        ["Connection via USB tethering or local network"] = "Подключение через USB-модем или локальную сеть",
        ["Connection to the phone"] = "Подключение к телефону",
        ["Choose the source before connecting the phone."] = "Выбор источника до подключения телефона.",
        ["Windows audio source"] = "Источник звука Windows",
        ["Audio source"] = "Источник звука",
        ["Connect via USB"] = "Подключить по USB",
        ["Revoke PC permission"] = "Отозвать разрешение ПК",
        ["Could not open the Host address. Connect USB tethering or a local network and launch the shortcut again."] = "Не удалось открыть адрес Host. Подключите USB-модем или локальную сеть и запустите ярлык снова.",
        ["CatchWave Host is already running at this address. Close its current window before starting the new version."] = "CatchWave Host уже работает на этом адресе. Перед запуском новой версии закройте его текущее окно.",
        ["Address: {0}\nCertificate SHA-256:\n{1}"] = "Адрес: {0}\nСертификат SHA-256:\n{1}",
        ["Session: {0} s\nFrames sent: {1}\nSkipped intervals: {2}\nCode: {3} · stage: {4}\nLast reason: {5}\nLog errors: {6}"] = "Сеанс: {0} с\nОтправлено кадров: {1}\nПропущено интервалов: {2}\nКод: {3} · этап: {4}\nПоследняя причина: {5}\nОшибок журнала: {6}",
    };
}
