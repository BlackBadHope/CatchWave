# Чекпоинт 0.1.10 — 2026-09-09

## Результат

Продолжение ветки `feat/seek-lag-ui`. Unit-suite `:app:testDebugUnitTest`. APK / Play / OnePlus не публиковались.

versionCode 11, versionName 0.1.10. Рекламный ID, аналитика и AdMob не добавлялись.

## Что сделано

- Запрос Shazam без `sampling` / `sharehub` / `video`; сеть только `amp.shazam.com` и `music.youtube.com`.
- `AD_ID` снят из манифеста; backup и cleartext закрыты; splash и predictive back нативные.
- Микрофон: low-latency + `privacySensitive`, если Android даёт.
- Пока три замера сходятся, каталог YouTube Music ищется заранее — без запуска плеера до подтверждения.
- Экран «Что уходит с телефона».

## Что не проверено

Живой handoff на телефоне, MediaProjection, загрузка в Play. Неофициальные API Shazam/YTM для витрины остаются риском ревью.
