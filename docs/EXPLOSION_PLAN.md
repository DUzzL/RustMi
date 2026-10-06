# Explosions → Rust — Campaign Plan (E0–E3)

Цель: Rust в ванильные взрывы (TNT прежде всего) с бит-в-бит семантикой.
Изучено 2026-06-11 по исходникам 26.1.2 (`ExplosionImpl`, yarn).

## Анатомия ванильного взрыва (ExplosionImpl.explode())

1. **getBlocksToDestroy()** — лучевой свип: 16³-поверхность = 1352 луча из
   центра, шаг 0.3, на шаге: getBlockState + getFluidState +
   behavior.getBlastResistance → power −= (res+0.3)*0.3; собирает
   `HashSet<BlockPos>` → `ObjectArrayList(set)`. ЧИСТАЯ МАТЕМАТИКА над
   данными блоков — главная цель Rust.
2. **damageEntities()** — getOtherEntities(box 2r) → на сущность:
   `calculateReceivedDamage` = сетка точек по AABB (~45 лучей
   `raycast(COLLIDER)` — ДОРОГО, ~50–200µs/сущность) → damage + knockback
   (применение — Java, наблюдаемо).
3. **destroyBlocks()** — `Util.shuffle(positions, world.random)` →
   onExploded per block (setBlock air + дропы с merge ≤16) → Block.dropStack;
   **createFire**: nextInt(3) на позицию. Наблюдаемое, остаётся Java.

## Ловушки точности (все решаемые)

- **RNG-последовательность**: на КАЖДЫЙ луч `world.random.nextFloat()`
  (1352 последовательных draw); shuffle и огонь тоже едят world.random.
  Решение: floats тянет JAVA в ванильном порядке (1352×~5ns) и передаёт
  ядру буфером — состояние Java-RNG остаётся бит-в-бит синхронным.
- **Порядок HashSet** (опять locational!): список на shuffle =
  порядок итерации HashSet<BlockPos> (сотни-тысячи элементов, ресайзы —
  наш jorder моделирует только cap-16!). Решение БЕЗ моделирования:
  Rust возвращает позиции в ПОРЯДКЕ ОТКРЫТИЯ (dedup по первому
  вхождению), Java вставляет их в настоящий HashSet в том же порядке →
  идентичная итерация → идентичный вход shuffle. JVM делает ordering сама.
- **Behavior-гейт**: takeover только для DEFAULT_BEHAVIOR /
  EntityExplosionBehavior с ванильными дефолтами (TNT, крипер);
  кастомные (wind charge AdvancedExplosionBehavior, моды) → ваниль.
- **Снапшот**: куб радиуса ~1.74·power (луч умирает по бюджету);
  resistance считается ПО ПАЛИТРЕ секции (уникальных стейтов мало) →
  снапшот ~50–100µs. Между взрывами одного тика мир МЕНЯЕТСЯ
  (кратер A виден лучам B — так работают пушки!) → **кросс-взрывной
  батчинг ЗАПРЕЩЁН семантикой**; nalim-бенч ×5.9 на rayon-батче сюда
  НЕ переносится. Per-explosion offload only.

## Lithium уже здесь (его пак, 0.24.5)

`world.explosions.block_raycast(+skip_air)` — кэш resistance в свипе;
`entity_raycast` — оптимизация seen-percent; `alloc.explosion_behavior`.
Результат-паритетные оптимизации → наш выигрыш меряется ОТ Lithium, не от
голой ванили. Ожидание: компьют-часть (фазы 1–2) ужмётся в разы, но
destroyBlocks (setBlock+дропы, наблюдаемое) не сжимается → end-to-end на
TNT-штормах оценочно ×1.5–2.5. Мерить до строительства.

## Фазы

- **E0 — профиль + рекордер.** (а) jstack-burst TNT-шторма: сколько
  реально стоят фазы 1/2/3 при живом Lithium; (б) fixture-рекордер
  (паттерн R0): WrapOperation на nextFloat внутри getBlocksToDestroy
  (захват 1352 floats) + вход (центр/power/снапшот) + выход
  (ObjectArrayList ПОРЯДОК!) + seen-percent пары (вход/float) →
  rustmi-explosion-fixtures/. Команда `/rustmi explosion record`.
- **E1 — ядро свипа.** Rust: 1352 луча, f32-точный порт (0.22500001F!),
  вход = снапшот resistance + floats, выход = discovery-ordered позиции.
  Бит-в-бит против фикстур (вкл. порядок списка после Java-HashSet
  реконструкции). Старт — математика из nalim bench_explosion_rust.
- **E2 — seen-percent fast-path.** Снапшот full-cube битмаски (инсайт C0:
  ~80% простых) → DDA-лучи в Rust для air/full-cube боксов; частичные
  шейпы в боксе → Java clip fallback. RNG не участвует — порядконезависимо.
- **E3 — флип** за командой + shadow-режим по паттерну R2 (предсказание
  vs ваниль, сухой лог = гейт).

## Переиспользуем

nalim `bench_explosion_rust` (математика луча), fixture-методологию
(дважды доказана), command+persist паттерн, retention-FFI паттерн,
палитро-уровневый precompute (resistance per palette entry).
