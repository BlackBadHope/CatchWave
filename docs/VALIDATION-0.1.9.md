# Чекпоинт 0.1.9 — 2026-09-09

## Результат

Unit-suite `:app:testDebugUnitTest` для ветки `feat/seek-lag-ui`. APK / OnePlus в этом чекпоинте не ставились: release после APK — отдельно.

versionCode 10, versionName 0.1.9. Spotify, iOS и Play не затрагивались.

## Что проверено тестами

- Нескомпенсированный `seek_lag` равен Δ после settle; команда с learned lag сводит Δ к нулю.
- После handoff/seek в RAM-диагностике есть `t_est`, `t_session_after_seek`, `Δ`, `seek_lag` и отдельная строка «Уточнить по звуку».
- Один номер версии в gradle, UI, отчёте и User-Agent.
- GPL: `licenseStart` указывает на TERMS AND CONDITIONS, не на How to Apply.
- Live Sync off: «Один подхват — дальше сам».
- Прежние границы очереди, паузы, GCC-PHAT и каталога сохранены существующими тестами.

## Как читать логи Δ / seek_lag

Настройки → Диагностика или `adb shell dumpsys activity app.catchwave/.MainActivity`.

1. `Перемотка #N: t_est=… commanded=… seek_lag_comp=… t_session=…` — команда seek.
2. `t_est=… t_session_after_seek=… Δ=… seek_lag=… learned_lag=…` — замер после settle MediaSession. `Δ = t_est − t_session`. `seek_lag` — `lastPositionUpdateTime − seekSentAt`, не динамик.
3. `Уточнить по звуку: …` — доступность кнопки после подхвата.
4. После захвата: `Уточнить по звуку: итог verified=… lag=…` — GCC-PHAT по RAM-буферам, без диска и аналитики.

3×≤120 мс по-прежнему порог согласованности распознавателя. Успешный unit-тест и малая Δ не доказывают слышимый sync.

## Что ещё не проверено

Реальный handoff YouTube Music на OnePlus, системное согласие MediaProjection и слышимое отставание ~300 мс. Если внутренний звук при нулевой громкости недоступен, «Уточнить по звуку» должен остановиться без случайной поправки.
