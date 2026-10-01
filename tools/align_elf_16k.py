"""Give every shared library 16 KB LOAD alignment.

Android 16 warns at launch when a .so LOAD segment is aligned to 4 KB.
Padding is inserted so each LOAD file offset stays congruent to its virtual
address modulo 16384, then p_align is raised to 16384. Already-aligned files
are left untouched.
"""

import struct
import sys
from pathlib import Path

PAGE = 16384


def align_elf(path: Path) -> str:
    data = bytearray(path.read_bytes())
    if data[:4] != b"\x7fELF":
        return "skip"
    is64 = data[4] == 2
    if is64:
        e_phoff = struct.unpack_from("<Q", data, 32)[0]
        e_shoff = struct.unpack_from("<Q", data, 40)[0]
        e_phentsize, e_phnum = struct.unpack_from("<HH", data, 54)
        e_shentsize, e_shnum = struct.unpack_from("<HH", data, 58)
    else:
        e_phoff = struct.unpack_from("<I", data, 28)[0]
        e_shoff = struct.unpack_from("<I", data, 32)[0]
        e_phentsize, e_phnum = struct.unpack_from("<HH", data, 42)
        e_shentsize, e_shnum = struct.unpack_from("<HH", data, 46)

    loads = []
    for index in range(e_phnum):
        off = e_phoff + index * e_phentsize
        if struct.unpack_from("<I", data, off)[0] != 1:
            continue
        if is64:
            p_offset, p_vaddr, _, p_filesz, _, p_align = struct.unpack_from("<QQQQQQ", data, off + 8)
        else:
            p_offset, p_vaddr, _, p_filesz, _, _, p_align = struct.unpack_from("<IIIIIII", data, off + 4)
        loads.append((off, p_offset, p_vaddr, p_filesz, p_align))
    if not loads:
        return "skip"
    if all(align >= PAGE and offset % PAGE == vaddr % PAGE for _, offset, vaddr, _, align in loads):
        return "ok"

    loads.sort(key=lambda item: item[1])
    patches = []
    shift = 0
    for _, offset, vaddr, _, _ in loads:
        current = offset + shift
        need, have = vaddr % PAGE, current % PAGE
        pad = 0 if need == have else (need - have if need > have else PAGE - have + need)
        if pad:
            patches.append((offset, pad))
            shift += pad

    def moved(original: int) -> int:
        return original + sum(pad for position, pad in patches if original >= position)

    if patches:
        for position, pad in reversed(patches):
            # position is the original offset; earlier inserts have not happened yet
            # because we walk from the end.
            data[position:position] = b"\x00" * pad

    for index in range(e_phnum):
        off = e_phoff + index * e_phentsize
        kind = struct.unpack_from("<I", data, off)[0]
        if is64:
            struct.pack_into("<Q", data, off + 8, moved(struct.unpack_from("<Q", data, off + 8)[0]))
            if kind == 1:
                struct.pack_into("<Q", data, off + 48, PAGE)
        else:
            struct.pack_into("<I", data, off + 4, moved(struct.unpack_from("<I", data, off + 4)[0]))
            if kind == 1:
                struct.pack_into("<I", data, off + 28, PAGE)

    new_shoff = moved(e_shoff)
    if is64:
        struct.pack_into("<Q", data, 40, new_shoff)
    else:
        struct.pack_into("<I", data, 32, new_shoff)
    for index in range(e_shnum):
        entry = new_shoff + index * e_shentsize
        if is64:
            struct.pack_into("<Q", data, entry + 24, moved(struct.unpack_from("<Q", data, entry + 24)[0]))
        else:
            struct.pack_into("<I", data, entry + 16, moved(struct.unpack_from("<I", data, entry + 16)[0]))

    path.write_bytes(data)
    check = path.read_bytes()
    for index in range(e_phnum):
        off = e_phoff + index * e_phentsize
        if struct.unpack_from("<I", check, off)[0] != 1:
            continue
        if is64:
            offset, vaddr, align = struct.unpack_from("<QQ", check, off + 8)[0], struct.unpack_from("<Q", check, off + 16)[0], struct.unpack_from("<Q", check, off + 48)[0]
        else:
            offset, vaddr, align = struct.unpack_from("<I", check, off + 4)[0], struct.unpack_from("<I", check, off + 8)[0], struct.unpack_from("<I", check, off + 28)[0]
        if align < PAGE or offset % PAGE != vaddr % PAGE:
            raise SystemExit(f"alignment failed: {path}")
    return "aligned"


def main() -> int:
    root = Path(sys.argv[1])
    changed = 0
    for library in sorted(root.rglob("*.so")):
        state = align_elf(library)
        if state == "aligned":
            changed += 1
            print(library.name)
    print(f"aligned {changed}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
