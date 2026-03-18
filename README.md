# YummyAnime Aniyomi Extension
## Что реализовано

- популярное через `GET /anime`
- latest через `GET /anime/schedule`
- поиск и фильтры через `GET /anime`
- карточка тайтла через `GET /anime/{url}?need_videos=true`
- список эпизодов через API YummyAnime
- извлечение видео через `Kodik`

## Токены
После установки расширения их нужно указать в настройках источника внутри Aniyomi:

- `PUBLIC TOKEN` обязателен и отправляется в `X-Application`
- `PRIVATE TOKEN` опционален и отправляется как `Authorization: Yummy <token>`
- для диагностики есть встроенный `Debug лог` в настройках источника (без `adb logcat`)
- в настройках можно выгрузить `Debug лог` на `https://temp.sh/` и получить ссылку