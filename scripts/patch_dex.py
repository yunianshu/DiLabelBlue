# -*- coding: utf-8 -*-
"""Byte-level dex patch: force com.tianyu.util.a.a()Z to return false,
so 360 jiagu takes the real-ARM branch (libjiagu_a64.so) instead of the
x86-emulator branch (libX86Bridge.so). Preserves all hidden payload data."""
import struct, sys, hashlib, zlib, shutil

def uleb(buf, off):
    result = 0; shift = 0
    while True:
        b = buf[off]; off += 1
        result |= (b & 0x7F) << shift
        shift += 7
        if not (b & 0x80): break
    return result, off

def get_string(data, string_ids_off, idx):
    off = struct.unpack_from('<I', data, string_ids_off + idx*4)[0]
    _, o = uleb(data, off)
    end = data.index(b'\x00', o)
    return data[o:end].decode('utf-8', 'replace')

def main(src, dst):
    data = bytearray(open(src, 'rb').read())
    string_ids_size, string_ids_off = struct.unpack_from('<II', data, 0x38)
    type_ids_size,  type_ids_off  = struct.unpack_from('<II', data, 0x40)
    proto_ids_size, proto_ids_off = struct.unpack_from('<II', data, 0x48)
    method_ids_size, method_ids_off = struct.unpack_from('<II', data, 0x58)
    class_defs_size, class_defs_off = struct.unpack_from('<II', data, 0x60)

    def type_str(idx):
        si, = struct.unpack_from('<I', data, type_ids_off + idx*4)
        return get_string(data, string_ids_off, si)

    target = None
    for i in range(class_defs_size):
        base = class_defs_off + i*32
        cls_idx, = struct.unpack_from('<I', data, base)
        if type_str(cls_idx) != 'Lcom/tianyu/util/a;':
            continue
        class_data_off, = struct.unpack_from('<I', data, base+24)
        off = class_data_off
        _, off = uleb(data, off)  # static fields
        _, off = uleb(data, off)  # instance fields
        n_direct, off = uleb(data, off)
        n_virtual, off = uleb(data, off)
        for _ in range(n_direct + n_virtual):
            midx, off = uleb(data, off)
            _, off = uleb(data, off)  # access flags
            code_off, off = uleb(data, off)
            # method id: class_idx(u16) proto_idx(u16) name_idx(u32)
            cbase = method_ids_off + midx*8
            pidx, = struct.unpack_from('<H', data, cbase+2)
            name_idx, = struct.unpack_from('<I', data, cbase+4)
            name = get_string(data, string_ids_off, name_idx)
            # proto: return_type_idx at +4
            ridx, = struct.unpack_from('<I', data, proto_ids_off + pidx*12 + 4)
            rtype = type_str(ridx)
            if name == 'a' and rtype == 'Z' and code_off:
                target = (name, rtype, code_off)
        break

    if not target:
        print('PATCH_FAIL: method not found'); sys.exit(1)

    name, rtype, code_off = target
    print('found method a()Z code_off=0x%x' % code_off)
    registers_size, ins_size, outs_size, tries_size, debug_off, insns_size = \
        struct.unpack_from('<HHHHII', data, code_off)
    insns_off = code_off + 16
    print('insns_size=%d (units)' % insns_size)
    # const/4 v0, 0  ; return v0  ; then nops
    patch = bytes([0x12, 0x00, 0x0F, 0x00])
    total = insns_size * 2
    blob = patch + b'\x00\x00' * ((total - len(patch)) // 2)
    data[insns_off:insns_off+total] = blob
    print('patched bytes at 0x%x' % insns_off)

    # fix SHA-1 (offset 0x0C, over bytes from 0x20 to end) and Adler-32 (offset 0x08, from 0x0C to end)
    sha = hashlib.sha1(bytes(data[0x20:])).digest()
    data[0x0C:0x20] = sha
    adler = zlib.adler32(bytes(data[0x0C:])) & 0xFFFFFFFF
    struct.pack_into('<I', data, 0x08, adler)
    open(dst, 'wb').write(bytes(data))
    print('PATCH_OK ->', dst)

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
