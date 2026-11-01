#
# Logisim-evolution - digital logic design tool and simulator
# Copyright by the Logisim-evolution developers
#
# https://github.com/logisim-evolution/
#
# This is free software released under GNU GPLv3 license
#

"""Fails unless each given JAR ends with its zip end record: "java -jar" rejects any trailing bytes."""

import struct
import sys

for path in sys.argv[1:]:
    with open(path, "rb") as f:
        data = f.read()
    end = data.rfind(b"PK\x05\x06")
    comment = struct.unpack("<H", data[end + 20:end + 22])[0] if end >= 0 else 0
    if end < 0 or end + 22 + comment != len(data):
        sys.exit(f"{path}: {len(data) - (end + 22 + comment)} bytes after the zip end record")
    print(f"{path}: ok")
