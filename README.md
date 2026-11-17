# Nari TV Router (Android TV 14 / Razer Nari Ultimate)

**Экспериментальный проект, не протестирован на TCL. Это ИСХОДНИКИ, не готовый APK.**

ТВ видит USB Nari Ultimate, но выбирает ALSA card=4;device=0 (Chat). При этом card=4;device=1 (предположительно Game) существует в `/dev/snd`. Системный AudioService принимает команды зарегистрировать второй выход только от привилегированного UID. Приложение использует Shizuku UserService, запускаемый как shell UID 2000, чтобы попробовать зарегистрировать D1 через скрытый IAudioService. Без Shizuku / root кнопки не работают. Возможна несовместимость USB HAL.

## Как получить APK без Android Studio или SDK на Mac

В репозитории GitHub Actions есть файл `.github/workflows/build-apk.yml` для облачной сборки. Загрузите проект в свой GitHub-репозиторий, затем запустите **Actions → Build experimental Nari TV APK → Run workflow**. В завершившейся сборке будет артефакт `nari-tv-router-debug-apk` с `app-debug.apk`.

Для локальной сборки при наличии Gradle 8.10.2 + Android SDK Platform 35/Build Tools 35:

```bash
gradle :app:assembleDebug
```

## Запуск на телевизоре

1. Установить Shizuku из официального источника `https://shizuku.rikka.app/download/`.
2. Включить отладку по ADB и (после установки Shizuku) запустить:

```bash
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

3. Установить `app-debug.apk`: `adb install -r app-debug.apk`.
4. Открыть `Nari TV Router` и разрешить доступ через Shizuku.
5. Проверить ALSA card, по умолчанию будет найден из AudioManager (если имя выхода содержит Razer).
6. Нажать «Проверить Game (без отключения Chat)». Проверить в ADB `dumpsys media.audio_policy`, появился ли `device=1`.
7. Если появился, нажать «Переключить весь звук на Game» и проверить воспроизведение на небольшой громкости.
8. Для возврата — «Вернуть Chat» или переподключение донгла.

**Важно:** пока это проверка гипотезы. Имеющееся у пользователя право `MODIFY_AUDIO_ROUTING` у `com.android.shell` означает возможность вызвать API, но не гарантирует, что USB HAL сможет открыть второй выход. На пользовательской прошивке TCL это может быть заблокировано.

## Безопасность

- Программа не изменяет прошивку, файлы `/vendor` и `/system`.
- По нажатию кнопок только отправляет Binder-события подключения/отключения двух выходов.
- Выход D1 сначала добавляется без удаления D0.
- Из-за особенностей прошивки звук может исчезнуть при переключении; переподключение донгла восстановит штатное обнаружение.
- Shizuku требуется запускать после каждой перезагрузки телевизора (в режиме ADB без root).
- Работающую команду надо повторять после переподключения донгла.
- Сторонние приложения не получают право `MODIFY_AUDIO_ROUTING` автоматически.

## Исходники

- `app/src/main/java/dev/naritv/RouteService.java` — привилегированный Shizuku UserService, скрытый Binder AudioService.
- `app/src/main/java/dev/naritv/MainActivity.java` — интерфейс под пульт Android TV.
- `app/src/main/aidl/dev/naritv/IRouteService.aidl` — контракт между UI и UserService.
- `.github/workflows/build-apk.yml` — сборка в GitHub Actions.
