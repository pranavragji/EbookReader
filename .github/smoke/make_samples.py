"""Write tiny sample books (EPUB, CBZ, TXT, PDF) for the emulator smoke test. Stdlib only."""
import struct, sys, zlib, zipfile, os

out = sys.argv[1]
os.makedirs(out, exist_ok=True)


def png(w, h, rgb, stripe):
    rows = b"".join(
        b"\x00" + b"".join(bytes(stripe if (y // 40) % 2 else rgb) for _ in range(w)) for y in range(h)
    )
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + \
        chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b"")


# EPUB 3 with a cover
with zipfile.ZipFile(f"{out}/smoke-novel.epub", "w") as z:
    z.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
    z.writestr("META-INF/container.xml", """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
    z.writestr("OEBPS/content.opf", """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="id">smoke-novel</dc:identifier><dc:title>Smoke Novel</dc:title>
<dc:creator>Test Author</dc:creator><dc:language>en</dc:language>
<meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata>
<manifest>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
<item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>
<item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
<item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
</manifest><spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""")
    z.writestr("OEBPS/nav.xhtml", """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
<nav epub:type="toc"><ol><li><a href="c1.xhtml">Chapter One</a></li><li><a href="c2.xhtml">Chapter Two</a></li></ol></nav></body></html>""")
    para = "<p>" + "The quick brown fox jumps over the lazy dog. " * 12 + "</p>"
    for i, name in ((1, "One"), (2, "Two")):
        z.writestr(f"OEBPS/c{i}.xhtml", f"""<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>{name}</title></head><body>
<h1>Chapter {name}</h1>{para * 20}</body></html>""")
    z.writestr("OEBPS/cover.png", png(300, 450, (40, 70, 120), (220, 180, 90)))

# CBZ, 5 pages
with zipfile.ZipFile(f"{out}/smoke-manga.cbz", "w") as z:
    for i in range(1, 6):
        z.writestr(f"page{i:02}.png", png(400, 600, (30 * i, 90, 160), (240, 240, 240)))

# TXT
open(f"{out}/smoke-notes.txt", "w").write(("A plain text paragraph for the smoke test.\n\n") * 200)

# Minimal 2-page PDF
objs = ["<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>"]
stream = b"BT /F1 36 Tf 72 700 Td (Smoke PDF) Tj ET"
for _ in range(2):
    objs.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents {len(objs) + 2} 0 R "
                "/Resources << /Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> >> >>")
    objs.append(f"<< /Length {len(stream)} >>\nstream\n{stream.decode()}\nendstream")
pdf, offs = b"%PDF-1.4\n", []
for i, o in enumerate(objs, 1):
    offs.append(len(pdf))
    pdf += f"{i} 0 obj\n{o}\nendobj\n".encode()
xref = len(pdf)
pdf += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n".encode()
pdf += b"".join(f"{o:010} 00000 n \n".encode() for o in offs)
pdf += f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
open(f"{out}/smoke-doc.pdf", "wb").write(pdf)
print("samples written to", out)
