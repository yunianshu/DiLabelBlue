# -*- coding: utf-8 -*-
"""Repair jiagu-scrambled dex headers by scanning for the real class_defs,
then patch com.tianyu.util.a.a()Z -> return false."""
import struct, hashlib, zlib, sys

data = bytearray(open('apk_extracted/classes.dex','rb').read())
string_ids_size, string_ids_off = struct.unpack_from('<II', data, 0x38)
type_ids_size,  type_ids_off  = struct.unpack_from('<II', data, 0x40)
proto_ids_size, proto_ids_off = struct.unpack_from('<II', data, 0x48)
field_ids_size, field_ids_off = struct.unpack_from('<II', data, 0x50)
method_ids_size, method_ids_off = struct.unpack_from('<II', data, 0x58)

print('string_ids:', string_ids_size, 'type_ids:', type_ids_size,
      'proto_ids:', proto_ids_size, 'field_ids:', field_ids_size, 'method_ids:', method_ids_size)

def uleb(buf, off):
    result = 0; shift = 0
    while True:
        b = buf[off]; off += 1
        result |= (b & 0x7F) << shift
        shift += 7
        if not (b & 0x80): break
    return result, off

_str_cache = {}
def get_str(idx):
    if idx in _str_cache: return _str_cache[idx]
    if idx >= string_ids_size: return None
    off = struct.unpack_from('<I', data, string_ids_off + idx*4)[0]
    if off >= len(data): return None
    try:
        _, o = uleb(data, off)
        end = data.index(b'\x00', o, o+4096)
        s = data[o:end].decode('utf-8','replace')
    except Exception:
        return None
    _str_cache[idx] = s
    return s

def type_str(idx):
    if idx >= type_ids_size: return None
    si, = struct.unpack_from('<I', data, type_ids_off + idx*4)
    return get_str(si)

# sanity check type table
ok = sum(1 for i in range(min(20, type_ids_size)) if type_str(i))
print('type sanity: %d/20 ok' % ok)

# scan for real class_defs: try every dword offset
filesize = len(data)
found = []
for off in range(0x70, filesize - 32, 4):
    cls_idx, access, super_idx, _if, _sf, _v = struct.unpack_from('<IIIIII', data, off)
    if cls_idx >= type_ids_size or super_idx >= type_ids_size: continue
    ts = type_str(cls_idx)
    if not ts or not ts.startswith('L'): continue
    # plausible class def: interfaces_off/static values 0 or valid, class_data in range
    cdo, = struct.unpack_from('<I', data, off+24)
    if cdo == 0 or cdo >= filesize: continue
    # try parse class_data
    try:
        o = cdo
        sf, o = uleb(data, o); inf, o = uleb(data, o); dm, o = uleb(data, o); vm, o = uleb(data, o)
        if sf > 256 or inf > 1024 or dm > 2048 or vm > 8192: continue
        for _ in range(sf + inf):
            _, o = uleb(data, o); _, o = uleb(data, o)
        methods = []
        for _ in range(dm + vm):
            mi, o = uleb(data, o); _, o = uleb(data, o); co, o = uleb(data, o)
            methods.append((mi, co))
        found.append((off, ts, methods))
    except Exception:
        continue

print('candidate class defs:', len(found))
for off, ts, methods in found[:20]:
    print('  off=0x%x %s methods=%d' % (off, ts, len(methods)))
