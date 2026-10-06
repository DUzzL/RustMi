// Chunk-section packet encoder — the Rust half of the Rustmi chunk-send
// acceleration. One FFI call encodes a whole column of `LevelChunkSection`s into
// the exact vanilla `ClientboundLevelChunkPacketData` section buffer bytes, reading
// `NativeBitStorage` words directly by native address (no `getRaw()` heap copy).
//
// Wire layout reproduced bit-for-bit from `LevelChunkSection.write` (26.2 layout,
// unchanged in 26.3; 26.3 only moved the outer packet framing to StreamCodecs):
//   writeShort(nonEmptyBlockCount)            // big-endian
//   writeShort(fluidCount)                    // big-endian (NEW 26.2)
//   states.write(buffer)                       // PalettedContainer.Data.write
//   biomes.write(buffer)
// where each container writes:
//   writeByte(storage.getBits())              // 1 byte, the palette's memory bits
//   palette.write(buffer, globalMap)          // SINGLE: 1 varint id; LINEAR/HASHMAP:
//                                             //   varint size + ids; GLOBAL: nothing
//   writeFixedSizeLongArray(storage.getRaw()) // each long big-endian, no length prefix
//
// The Java bridge (ChunkSectionPacketEncoder) builds the descriptor + palette-id
// buffers; this kernel only emits bytes. It is validate-before-write: the total
// size is computed first and must fit `out_cap`, else -1 with nothing written.

pub mod ffi {
    // ── packed record layout (64 bytes, no internal padding) ────────────────
    // Two i64 addresses first (states/biomes native storage), then 14 i32 fields.
    // Java writes these at the exact offsets below via sun.misc.Unsafe.
    pub const SECTION_STRIDE: usize = 64;
    pub(crate) const OFF_STATES_ADDR: usize = 0;
    pub(crate) const OFF_BIOMES_ADDR: usize = 8;
    pub(crate) const OFF_NON_EMPTY: usize = 16;
    pub(crate) const OFF_FLUID: usize = 20;
    pub(crate) const OFF_STATES_BITS: usize = 24;
    pub(crate) const OFF_BIOMES_BITS: usize = 44;
    #[allow(dead_code)] // record ABI constants mirrored by Java and exercised in tests
    pub(crate) const OFF_STATES_MODE: usize = 32;
    #[allow(dead_code)] // record ABI constants mirrored by Java and exercised in tests
    pub(crate) const OFF_BIOMES_MODE: usize = 52;
    #[allow(dead_code)] // record ABI constants mirrored by Java and exercised in tests
    pub(crate) const OFF_STATES_PAL_OFF: usize = 36;
    #[allow(dead_code)] // record ABI constants mirrored by Java and exercised in tests
    pub(crate) const OFF_BIOMES_PAL_OFF: usize = 56;
    #[allow(dead_code)] // record ABI constants mirrored by Java and exercised in tests
    pub(crate) const OFF_STATES_PAL_COUNT: usize = 40;
    #[allow(dead_code)] // record ABI constants mirrored by Java and exercised in tests
    pub(crate) const OFF_BIOMES_PAL_COUNT: usize = 60;
    #[allow(dead_code)] // record ABI constants mirrored by Java and exercised in tests
    pub(crate) const OFF_STATES_WORDS: usize = 28;
    #[allow(dead_code)] // record ABI constants mirrored by Java and exercised in tests
    pub(crate) const OFF_BIOMES_WORDS: usize = 48;

    // Palette wire modes — match Java's PaletteMode wiring.
    pub const MODE_SINGLE: i32 = 0;
    pub const MODE_LINEAR: i32 = 1;
    pub const MODE_HASHMAP: i32 = 2;
    pub const MODE_GLOBAL: i32 = 3;

    /// Layout introspection for the Java side (runtime cross-check).
    #[no_mangle]
    pub extern "C" fn oxi_chunk_section_record_size() -> i32 {
        SECTION_STRIDE as i32
    }

    /// Encode `n_sections` sections into `out_ptr`.
    ///
    /// Returns total bytes written (`>= 0`), or `-1` on any validation failure /
    /// unsupported record / `out_cap` too small. Never writes a partial section.
    ///
    /// # Safety
    /// `header_ptr` → 4 readable bytes `[i32 n_sections]`; `sections_ptr` →
    /// `n_sections * 64` readable bytes (layout above); `palette_ids_ptr` → every
    /// `(pal_off + pal_count)` referenced `i32`; `out_ptr` → `out_cap` writable
    /// bytes. Java guarantees these by packing from validated descriptors.
    #[no_mangle]
    pub unsafe extern "C" fn oxi_chunk_section_encode(
        header_ptr: *const u8,
        sections_ptr: *const u8,
        palette_ids_ptr: *const i32,
        out_ptr: *mut u8,
        out_cap: i32,
    ) -> i32 {
        if header_ptr.is_null() || sections_ptr.is_null() || out_ptr.is_null() {
            return -1;
        }
        if out_cap < 0 {
            return -1;
        }
        let n_sections = (header_ptr as *const i32).read_unaligned();
        if n_sections < 0 {
            return -1;
        }
        let n = n_sections as usize;

        // Pass 1: validate every record + compute total bytes. No writes yet.
        let mut total: usize = 0;
        for i in 0..n {
            let base = sections_ptr.add(i * SECTION_STRIDE);
            let non_empty = rd_i32(base, OFF_NON_EMPTY);
            let fluid = rd_i32(base, OFF_FLUID);
            if non_empty < 0 || non_empty > 0xFFFF || fluid < 0 || fluid > 0xFFFF {
                return -1;
            }
            total = match total.checked_add(4) {
                Some(v) => v,
                None => return -1,
            };
            match container_size(base, OFF_STATES_ADDR, OFF_STATES_BITS, palette_ids_ptr) {
                Some(s) => {
                    total = match total.checked_add(s) {
                        Some(v) => v,
                        None => return -1,
                    }
                }
                None => return -1,
            }
            match container_size(base, OFF_BIOMES_ADDR, OFF_BIOMES_BITS, palette_ids_ptr) {
                Some(s) => {
                    total = match total.checked_add(s) {
                        Some(v) => v,
                        None => return -1,
                    }
                }
                None => return -1,
            }
        }
        if total > out_cap as usize {
            return -1;
        }
        if n == 0 {
            return 0;
        }

        // Pass 2: write.
        let out = std::slice::from_raw_parts_mut(out_ptr, out_cap as usize);
        let mut pos: usize = 0;
        for i in 0..n {
            let base = sections_ptr.add(i * SECTION_STRIDE);
            write_short_be(out, &mut pos, rd_i32(base, OFF_NON_EMPTY));
            write_short_be(out, &mut pos, rd_i32(base, OFF_FLUID));
            if write_container(
                out,
                &mut pos,
                base,
                OFF_STATES_ADDR,
                OFF_STATES_BITS,
                palette_ids_ptr,
            )
            .is_err()
            {
                return -1; // validated above; only reachable via memory corruption
            }
            if write_container(
                out,
                &mut pos,
                base,
                OFF_BIOMES_ADDR,
                OFF_BIOMES_BITS,
                palette_ids_ptr,
            )
            .is_err()
            {
                return -1;
            }
        }
        if pos != total {
            return -1;
        }
        total as i32
    }

    // ── field readers (unaligned — no assumption on buffer alignment) ─────────
    #[inline]
    unsafe fn rd_i32(base: *const u8, off: usize) -> i32 {
        (base.add(off) as *const i32).read_unaligned()
    }
    #[inline]
    unsafe fn rd_i64(base: *const u8, off: usize) -> i64 {
        (base.add(off) as *const i64).read_unaligned()
    }
    #[inline]
    unsafe fn rd_id(palette_ids: *const i32, index: usize) -> i32 {
        (palette_ids.add(index) as *const i32).read_unaligned()
    }

    /// Byte length of one container's wire form, or `None` if malformed. The five
    /// container fields sit contiguously: `bits, words, mode, pal_off, pal_count`
    /// at `bits_off .. bits_off + 16`.
    unsafe fn container_size(
        base: *const u8,
        addr_off: usize,
        bits_off: usize,
        palette_ids: *const i32,
    ) -> Option<usize> {
        let bits = rd_i32(base, bits_off);
        let words = rd_i32(base, bits_off + 4);
        let mode = rd_i32(base, bits_off + 8);
        let pal_off = rd_i32(base, bits_off + 12);
        let pal_count = rd_i32(base, bits_off + 16);
        if bits < 0 || bits > 0xFF || words < 0 || pal_off < 0 || pal_count < 0 {
            return None;
        }
        if words > 0 && rd_i64(base, addr_off) == 0 {
            return None;
        }
        let data = (words as usize).checked_mul(8)?;
        let body = match mode {
            MODE_GLOBAL => 0usize,
            MODE_SINGLE => {
                if pal_count < 1 {
                    return None;
                }
                if palette_ids.is_null() {
                    return None;
                }
                varint_size(rd_id(palette_ids, pal_off as usize))
            }
            MODE_LINEAR | MODE_HASHMAP => {
                if palette_ids.is_null() {
                    return None;
                }
                let mut sz = varint_size(pal_count);
                for k in 0..pal_count as usize {
                    sz = sz.checked_add(varint_size(rd_id(palette_ids, pal_off as usize + k)))?;
                }
                sz
            }
            _ => return None,
        };
        Some(1 + body + data) // bits byte + palette body + data longs
    }

    /// Write one container's bytes. `addr_off` is this container's native-storage
    /// address field (OFF_STATES_ADDR or OFF_BIOMES_ADDR); `bits_off` its field block.
    unsafe fn write_container(
        out: &mut [u8],
        pos: &mut usize,
        base: *const u8,
        addr_off: usize,
        bits_off: usize,
        palette_ids: *const i32,
    ) -> Result<(), ()> {
        let bits = rd_i32(base, bits_off);
        let words = rd_i32(base, bits_off + 4);
        let mode = rd_i32(base, bits_off + 8);
        let pal_off = rd_i32(base, bits_off + 12);
        let pal_count = rd_i32(base, bits_off + 16);
        if bits < 0 || bits > 0xFF || words < 0 || pal_off < 0 || pal_count < 0 {
            return Err(());
        }
        out[*pos] = bits as u8;
        *pos += 1;
        match mode {
            MODE_GLOBAL => {}
            MODE_SINGLE => {
                if pal_count < 1 {
                    return Err(());
                }
                if palette_ids.is_null() {
                    return Err(());
                }
                write_varint(out, pos, rd_id(palette_ids, pal_off as usize));
            }
            MODE_LINEAR | MODE_HASHMAP => {
                if palette_ids.is_null() {
                    return Err(());
                }
                write_varint(out, pos, pal_count);
                for k in 0..pal_count as usize {
                    write_varint(out, pos, rd_id(palette_ids, pal_off as usize + k));
                }
            }
            _ => return Err(()),
        }
        // Data longs: read native u64 words (LE in memory), write each big-endian —
        // exactly Netty's ByteBuf.writeLong (vanilla writeFixedSizeLongArray). The
        // address is the NativeBitStorage base, ≥8-aligned; words==0 writes nothing.
        if words > 0 {
            let addr = rd_i64(base, addr_off) as *const u64;
            if addr.is_null() {
                return Err(());
            }
            let slice = std::slice::from_raw_parts(addr, words as usize);
            for &w in slice {
                out[*pos..*pos + 8].copy_from_slice(&w.to_be_bytes());
                *pos += 8;
            }
        }
        Ok(())
    }

    // ── primitive writers ────────────────────────────────────────────────────

    #[inline]
    pub(crate) fn write_short_be(out: &mut [u8], pos: &mut usize, v: i32) {
        let u = v as u16;
        out[*pos] = (u >> 8) as u8;
        out[*pos + 1] = u as u8;
        *pos += 2;
    }

    /// Minecraft protocol varint: 7 bits/byte, LSB first, continuation 0x80 except
    /// the last byte. Mirrors `FriendlyByteBuf.writeVarInt` (signed value shifted
    /// as unsigned, so negatives take 5 bytes).
    #[inline]
    pub(crate) fn write_varint(out: &mut [u8], pos: &mut usize, value: i32) {
        let mut u = value as u32;
        while (u & !0x7Fu32) != 0 {
            out[*pos] = ((u & 0x7F) | 0x80) as u8;
            *pos += 1;
            u >>= 7;
        }
        out[*pos] = u as u8;
        *pos += 1;
    }

    /// Byte length of the varint encoding — mirrors `VarInt.getByteSize`.
    #[inline]
    pub(crate) fn varint_size(value: i32) -> usize {
        let v = value as u32;
        if v >> 7 == 0 {
            1
        } else if v >> 14 == 0 {
            2
        } else if v >> 21 == 0 {
            3
        } else if v >> 28 == 0 {
            4
        } else {
            5
        }
    }
}

#[cfg(test)]
mod tests {
    use super::ffi::*;

    #[test]
    fn varint_size_matches_known_boundaries() {
        for &(v, n) in &[
            (0i32, 1usize),
            (1, 1),
            (127, 1),
            (128, 2),
            (16383, 2),
            (16384, 3),
            (2097151, 3),
            (2097152, 4),
            (268435455, 4),
            (268435456, 5),
            (-1, 5),
            (i32::MIN, 5),
            (i32::MAX, 5),
        ] {
            assert_eq!(varint_size(v), n, "varint_size({}) expected {}", v, n);
        }
    }

    #[test]
    fn varint_encode_matches_bytes() {
        let cases: &[(i32, &[u8])] = &[
            (0, &[0x00]),
            (1, &[0x01]),
            (127, &[0x7F]),
            (128, &[0x80, 0x01]),
            (255, &[0xFF, 0x01]),
            (16383, &[0xFF, 0x7F]),
            (16384, &[0x80, 0x80, 0x01]),
            (-1, &[0xFF, 0xFF, 0xFF, 0xFF, 0x0F]),
        ];
        for &(v, expect) in cases {
            let mut buf = [0u8; 8];
            let mut pos = 0;
            write_varint(&mut buf, &mut pos, v);
            assert_eq!(&buf[..pos], expect, "write_varint({})", v);
            assert_eq!(pos, varint_size(v));
        }
    }

    #[test]
    fn short_and_long_are_big_endian() {
        let mut buf = [0u8; 16];
        let mut pos = 0;
        write_short_be(&mut buf, &mut pos, 0x0102);
        assert_eq!(&buf[..2], &[0x01, 0x02]);

        // u64 -> 8 bytes big-endian (MSB first) == ByteBuf.writeLong.
        let w: u64 = 0x0102030405060708;
        buf[2..10].copy_from_slice(&w.to_be_bytes());
        assert_eq!(
            &buf[2..10],
            &[0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08]
        );
    }

    #[test]
    fn section_record_stride_is_64() {
        assert_eq!(SECTION_STRIDE, 64);
        assert_eq!(oxi_chunk_section_record_size(), 64);
    }

    /// Full end-to-end encode against a hand-computed vanilla byte sequence.
    #[test]
    fn encodes_two_sections_byte_exact() {
        let air_id: i32 = 0;
        let biome_id: i32 = 4;
        let block_ids: [i32; 2] = [10, 20];

        let mut expect: Vec<u8> = Vec::new();
        // Section A: air SINGLE + biome SINGLE.
        expect.extend_from_slice(&[0x00, 0x00]); // nonEmpty
        expect.extend_from_slice(&[0x00, 0x00]); // fluid
        expect.push(0x00); // states bits=0
        push_varint(&mut expect, air_id);
        expect.push(0x00); // biomes bits=0
        push_varint(&mut expect, biome_id);
        // Section B: LINEAR 2 ids + 64 data words; biome SINGLE.
        expect.extend_from_slice(&[0x00, 0x05]); // nonEmpty=5
        expect.extend_from_slice(&[0x00, 0x00]); // fluid=0
        expect.push(0x04); // states bits=4
        push_varint(&mut expect, 2); // palette size
        push_varint(&mut expect, block_ids[0]);
        push_varint(&mut expect, block_ids[1]);
        let mut pattern = [0u64; 64];
        for i in 0..64 {
            pattern[i] = (i as u64).wrapping_mul(0x0102030405060708).rotate_left(7);
        }
        for &w in &pattern {
            expect.extend_from_slice(&w.to_be_bytes());
        }
        expect.push(0x00); // biomes bits=0
        push_varint(&mut expect, biome_id);

        let mut sections = vec![0u8; 2 * SECTION_STRIDE];
        let mut ids: Vec<i32> = Vec::new();

        // Section A.
        let s0 = 0;
        write_i64(&mut sections, s0 + OFF_STATES_ADDR, 0);
        write_i64(&mut sections, s0 + OFF_BIOMES_ADDR, 0);
        write_i32(&mut sections, s0 + OFF_NON_EMPTY, 0);
        write_i32(&mut sections, s0 + OFF_FLUID, 0);
        let air_off = ids.len() as i32;
        ids.push(air_id);
        write_i32(&mut sections, s0 + OFF_STATES_BITS, 0);
        write_i32(&mut sections, s0 + OFF_STATES_WORDS, 0);
        write_i32(&mut sections, s0 + OFF_STATES_MODE, MODE_SINGLE);
        write_i32(&mut sections, s0 + OFF_STATES_PAL_OFF, air_off);
        write_i32(&mut sections, s0 + OFF_STATES_PAL_COUNT, 1);
        let b0_off = ids.len() as i32;
        ids.push(biome_id);
        write_i32(&mut sections, s0 + OFF_BIOMES_BITS, 0);
        write_i32(&mut sections, s0 + OFF_BIOMES_WORDS, 0);
        write_i32(&mut sections, s0 + OFF_BIOMES_MODE, MODE_SINGLE);
        write_i32(&mut sections, s0 + OFF_BIOMES_PAL_OFF, b0_off);
        write_i32(&mut sections, s0 + OFF_BIOMES_PAL_COUNT, 1);

        // Section B.
        let s1 = SECTION_STRIDE;
        let words_addr = pattern.as_mut_ptr() as i64;
        write_i64(&mut sections, s1 + OFF_STATES_ADDR, words_addr);
        write_i64(&mut sections, s1 + OFF_BIOMES_ADDR, 0);
        write_i32(&mut sections, s1 + OFF_NON_EMPTY, 5);
        write_i32(&mut sections, s1 + OFF_FLUID, 0);
        let p_off = ids.len() as i32;
        ids.push(block_ids[0]);
        ids.push(block_ids[1]);
        write_i32(&mut sections, s1 + OFF_STATES_BITS, 4);
        write_i32(&mut sections, s1 + OFF_STATES_WORDS, 64);
        write_i32(&mut sections, s1 + OFF_STATES_MODE, MODE_LINEAR);
        write_i32(&mut sections, s1 + OFF_STATES_PAL_OFF, p_off);
        write_i32(&mut sections, s1 + OFF_STATES_PAL_COUNT, 2);
        let b1_off = ids.len() as i32;
        ids.push(biome_id);
        write_i32(&mut sections, s1 + OFF_BIOMES_BITS, 0);
        write_i32(&mut sections, s1 + OFF_BIOMES_WORDS, 0);
        write_i32(&mut sections, s1 + OFF_BIOMES_MODE, MODE_SINGLE);
        write_i32(&mut sections, s1 + OFF_BIOMES_PAL_OFF, b1_off);
        write_i32(&mut sections, s1 + OFF_BIOMES_PAL_COUNT, 1);

        let header = 2i32.to_ne_bytes();
        let mut out = vec![0u8; expect.len() + 16];
        let rc = unsafe {
            oxi_chunk_section_encode(
                header.as_ptr(),
                sections.as_ptr(),
                ids.as_ptr(),
                out.as_mut_ptr(),
                out.len() as i32,
            )
        };
        assert!(rc >= 0, "encode returned {}", rc);
        assert_eq!(rc as usize, expect.len());
        assert_eq!(
            &out[..rc as usize],
            &expect[..],
            "wire bytes must match vanilla"
        );
    }

    #[test]
    fn rejects_undersized_out() {
        let mut sections = vec![0u8; SECTION_STRIDE];
        write_i32(&mut sections, OFF_NON_EMPTY, 0);
        write_i32(&mut sections, OFF_FLUID, 0);
        write_i32(&mut sections, OFF_STATES_BITS, 0);
        write_i32(&mut sections, OFF_STATES_WORDS, 0);
        write_i32(&mut sections, OFF_STATES_MODE, MODE_GLOBAL);
        write_i32(&mut sections, OFF_BIOMES_BITS, 0);
        write_i32(&mut sections, OFF_BIOMES_WORDS, 0);
        write_i32(&mut sections, OFF_BIOMES_MODE, MODE_GLOBAL);
        let header = 1i32.to_ne_bytes();
        let mut out = [0u8; 4]; // too small (need ≥ 6)
        let rc = unsafe {
            oxi_chunk_section_encode(
                header.as_ptr(),
                sections.as_ptr(),
                std::ptr::null(),
                out.as_mut_ptr(),
                out.len() as i32,
            )
        };
        assert_eq!(rc, -1);
        assert!(
            out.iter().all(|&b| b == 0),
            "must not write on undersized out"
        );
    }

    #[test]
    fn rejects_unknown_mode() {
        let mut sections = vec![0u8; SECTION_STRIDE];
        write_i32(&mut sections, OFF_STATES_MODE, 99);
        let header = 1i32.to_ne_bytes();
        let mut out = [0u8; 1024];
        let rc = unsafe {
            oxi_chunk_section_encode(
                header.as_ptr(),
                sections.as_ptr(),
                std::ptr::null(),
                out.as_mut_ptr(),
                out.len() as i32,
            )
        };
        assert_eq!(rc, -1);
    }

    #[test]
    fn rejects_nonzero_words_without_native_address() {
        let mut sections = vec![0u8; SECTION_STRIDE];
        write_i32(&mut sections, OFF_NON_EMPTY, 0);
        write_i32(&mut sections, OFF_FLUID, 0);
        write_i64(&mut sections, OFF_STATES_ADDR, 0);
        write_i32(&mut sections, OFF_STATES_BITS, 4);
        write_i32(&mut sections, OFF_STATES_WORDS, 1);
        write_i32(&mut sections, OFF_STATES_MODE, MODE_GLOBAL);
        write_i32(&mut sections, OFF_BIOMES_BITS, 0);
        write_i32(&mut sections, OFF_BIOMES_WORDS, 0);
        write_i32(&mut sections, OFF_BIOMES_MODE, MODE_GLOBAL);

        let header = 1i32.to_ne_bytes();
        let mut out = [0x5au8; 64];
        let rc = unsafe {
            oxi_chunk_section_encode(
                header.as_ptr(),
                sections.as_ptr(),
                std::ptr::null(),
                out.as_mut_ptr(),
                out.len() as i32,
            )
        };
        assert_eq!(rc, -1);
        assert!(
            out.iter().all(|&b| b == 0x5a),
            "must not write before rejecting malformed storage"
        );
    }

    #[test]
    fn rejects_null_palette_ids_for_non_global_palette() {
        let mut sections = vec![0u8; SECTION_STRIDE];
        write_i32(&mut sections, OFF_NON_EMPTY, 0);
        write_i32(&mut sections, OFF_FLUID, 0);
        write_i32(&mut sections, OFF_STATES_BITS, 0);
        write_i32(&mut sections, OFF_STATES_WORDS, 0);
        write_i32(&mut sections, OFF_STATES_MODE, MODE_SINGLE);
        write_i32(&mut sections, OFF_STATES_PAL_COUNT, 1);
        write_i32(&mut sections, OFF_BIOMES_BITS, 0);
        write_i32(&mut sections, OFF_BIOMES_WORDS, 0);
        write_i32(&mut sections, OFF_BIOMES_MODE, MODE_GLOBAL);

        let header = 1i32.to_ne_bytes();
        let mut out = [0x3cu8; 64];
        let rc = unsafe {
            oxi_chunk_section_encode(
                header.as_ptr(),
                sections.as_ptr(),
                std::ptr::null(),
                out.as_mut_ptr(),
                out.len() as i32,
            )
        };
        assert_eq!(rc, -1);
        assert!(
            out.iter().all(|&b| b == 0x3c),
            "must reject before writing with null palette ids"
        );
    }

    #[test]
    fn rejects_count_values_outside_unsigned_short_range() {
        let mut sections = vec![0u8; SECTION_STRIDE];
        write_i32(&mut sections, OFF_NON_EMPTY, 0x1_0000);
        write_i32(&mut sections, OFF_FLUID, 0);
        write_i32(&mut sections, OFF_STATES_BITS, 0);
        write_i32(&mut sections, OFF_STATES_WORDS, 0);
        write_i32(&mut sections, OFF_STATES_MODE, MODE_GLOBAL);
        write_i32(&mut sections, OFF_BIOMES_BITS, 0);
        write_i32(&mut sections, OFF_BIOMES_WORDS, 0);
        write_i32(&mut sections, OFF_BIOMES_MODE, MODE_GLOBAL);

        let header = 1i32.to_ne_bytes();
        let mut out = [0u8; 64];
        let rc = unsafe {
            oxi_chunk_section_encode(
                header.as_ptr(),
                sections.as_ptr(),
                std::ptr::null(),
                out.as_mut_ptr(),
                out.len() as i32,
            )
        };
        assert_eq!(rc, -1);
    }

    fn write_i32(buf: &mut [u8], off: usize, v: i32) {
        buf[off..off + 4].copy_from_slice(&v.to_ne_bytes());
    }
    fn write_i64(buf: &mut [u8], off: usize, v: i64) {
        buf[off..off + 8].copy_from_slice(&v.to_ne_bytes());
    }
    fn push_varint(out: &mut Vec<u8>, value: i32) {
        let mut u = value as u32;
        while (u & !0x7Fu32) != 0 {
            out.push(((u & 0x7F) | 0x80) as u8);
            u >>= 7;
        }
        out.push(u as u8);
    }
}
