# YummyAnime Aniyomi Extension
Расширение для [Aniyomi](https://github.com/aniyomiorg/aniyomi) и его форков (например, [Animiru](https://github.com/quickdesh/Animiru)).

> [!WARNING]
> Этот проект **не связан** с разработчиками Aniyomi, Animiru, любыми другими форками Aniyomi или официальным сайтом YummyAnime. Расширение предоставляется "как есть" исключительно в ознакомительных целях.

## Что реализовано

- популярное через `GET /anime`
- latest через `GET /anime/schedule`
- поиск и фильтры через `GET /anime`
- карточка тайтла через `GET /anime/{url}?need_videos=true`
- список эпизодов через API YummyAnime
- извлечение видео через `Kodik`

## Токены
После установки расширения автоматически используется Public Token, при желании можно указать в настройках источника внутри Aniyomi:

- `PUBLIC TOKEN` обязателен и отправляется в `X-Application`
- `PRIVATE TOKEN` опционален и отправляется как `Authorization: Yummy <token>`

Для получения своего токена нужно создать приложение на [сайте YummyAnime](https://site.yummyani.me/dev/applications)

Для диагностики есть встроенный `Debug лог` в настройках источника (без `adb logcat`), в настройках можно выгрузить `Debug лог` на `https://temp.sh/`

## Лицензия
Проект распространяется под лицензией [MIT](LICENSE).
