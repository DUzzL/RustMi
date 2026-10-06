# NativeSections — нативное хранилище секций (план кампании)

Цель: снять с кучи блок-сторейдж — по дампу 2026-06-12 это 60-70% из 23GB
(4.4M PalettedContainer ×2 на секцию: блоки + биомы; ~5GB backing `long[]`
в SimpleBitStorage). GC перестаёт сканировать/копировать гигабайты массивов
(old-gen паузы), Java читает биты по адресу через Unsafe (стоимость = чтение
массива, ноль FFI на блок), Rust-ядра читают ту же память напрямую
(zero-copy инспекция мира — взрывы/коллизии уже сегодня маршалят секции
вручную, см. oxi_explosion_sweep_packed).

## Статус

- **Stage 1 — ГОТОВО (2026-06-13, не подключено):**
  - `native/src/util/sections.rs` — `oxi_section_alloc/free/stats`:
    zeroed 64-aligned блоки, пер-классовые free-list пулы (16..960 слов,
    cap 4096 на класс ≈ ≤30MiB idle), atomic-статистика live bytes/blocks.
    3 cargo-теста зелёные (зануление при реюзе пула — проверено).
  - `nalim/.../sections/NativeBitStorage.java` — drop-in `BitStorage`:
    ванильная бит-алгебра ДОСЛОВНО (magic-divide cellIndex, маски — MAGIC
    читается рефлективно из SimpleBitStorage, бит-в-бит с задеплоенными
    маппингами), слова через `U.getLong/putLong(addr)`. Lifecycle:
    `java.lang.ref.Cleaner` (+`releaseNow()` для unload fast-path).
    Память-модель = как у `long[]`: plain unfenced reads/writes, ничего
    не добавлено и не убрано.
  - `RustmiLib`: `oxi_section_alloc(long←i32)/free(void)/stats(void)` —
    ABI-правило соблюдено (никаких под-int возвратов).

- **Stage 2 — ВАЙРИНГ (следующий заход, отдельная сертификация):**
  Швы конструирования (C2ME проверен: рантаймовый PalettedContainer НЕ
  трогает — только worldgen-структуры; шов ванильный):
  1. `PalettedContainer.createOrReuseData` — `new SimpleBitStorage(bits, size)`
     → редирект на NativeBitStorage (resize/initial; ZeroBitStorage для
     0 бит остаётся ванильным — он без массива);
  2. codec `unpack` (загрузка с диска) — `new SimpleBitStorage(bits, size, longs)`;
     ВНИМАНИЕ: ваниль ловит `SimpleBitStorage.InitializationException`
     (private ctor!) для битых длин → в редиректе валидировать длину ДО
     конструирования и пробрасывать ванильное исключение через
     SimpleBitStorage-пробу, иначе DataResult.error превратится в краш;
  3. копи-конструктор (снапшоты) — `copy()` уже нативный;
  4. `getRaw()` на save-пути материализует heap-копию (одна на секцию на
     сейв; c2me gcFreeChunkSerializer читает raw — совместимо, но проверить
     его прямой доступ к полю `data` если есть accessor-обходы!).
  Гейт: `rustmi.toml` флаг + `/rustmi sections on|off` (только для
  НОВЫХ аллокаций — живые контейнеры донашивают свой сторейдж до resize).
  Биомы (64-значные контейнеры, класс 16 слов) — тем же швом.

- **Stage 3 — Rust-ядра поверх (после стабилизации stage 2):**
  взрывы/коллизии перестают маршалить секции (адрес+палитра вместо копий);
  кандидат NativeSections-aware random-tick сканер.

## Сертификация stage 2 (обязательная, на копии прод-мира)

1. A/B бит-эквивалентность: загрузка региона → пер-блок сравнение
   getBlockState по всем секциям (native vs vanilla), включая resize-пути
   (заливка области blocks → рост палитры 4→5→8→15 бит).
2. Save-roundtrip: сейв с native → загрузка с vanilla → диф NBT == 0.
3. Память: RSS/heap до-после, GC-логи (old-gen pause до/после), live-stats
   из oxi_section_stats vs ожидание (~5GB native при том же мире).
4. Краш-стресс: chunk load/unload шторм (телепорт-кольца), Cleaner
   догоняет (live blocks не растут монотонно).

## Риски

- **Hot-path регрессия**: getBlockState — самый горячий вызов сервера;
  Unsafe.getLong vs array load — равно, НО потеря bounds-check-elimination
  JIT-ом и auto-vectorization клиентских путей — мерить честно (testbench
  + прод-копия), при регрессии >1% — откат к гибриду (native только
  для секций без частого доступа? нет — тогда не делаем вовсе).
- **Моды, лезущие в SimpleBitStorage.data рефлексией** (моонрайз-стайл
  оптимизаторы, может c2me serializer): grep по classpath на
  `getRaw|SimpleBitStorage` перед вайрингом.
- **OOM native**: нет -Xmx-страховки; добавить порог в alloc (по
  Runtime.maxMemory-производной) + жёсткий лог при первом 0-возврате.
