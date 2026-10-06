package com.github.tatercertified.nalim.chunk;

/**
 * The four vanilla palette wire shapes a section container can take, mirroring
 * exactly how {@code Palette.write(FriendlyByteBuf, IdMap)} serialises each:
 *
 * <ul>
 *   <li>{@link #SINGLE} — {@code SingleValuePalette}: one varint global id, no
 *       palette-size prefix, no data longs (paired with {@code ZeroBitStorage},
 *       bits = 0).</li>
 *   <li>{@link #LINEAR} — {@code LinearPalette}: varint size, then {@code size}
 *       varint global ids (blocks bits 1-4, biomes 1-3; the storage always uses
 *       the configuration's fixed bits, e.g. 4 for any 1-4 block count).</li>
 *   <li>{@link #HASHMAP} — {@code HashMapPalette}: same wire shape as LINEAR
 *       (varint size + ids); blocks bits 5-8.</li>
 *   <li>{@link #GLOBAL} — {@code GlobalPalette}: <b>no palette body at all</b>
 *       (blocks ≥9 bits, biomes ≥4 bits); the written bits byte is still the
 *       storage's actual bits.</li>
 * </ul>
 *
 * <p>Detected by {@code instanceof}, never by re-deriving {@code Strategy}
 * thresholds — so it stays correct if vanilla ever retunes the bit ranges.
 */
public enum PaletteMode {
    SINGLE,
    LINEAR,
    HASHMAP,
    GLOBAL
}
