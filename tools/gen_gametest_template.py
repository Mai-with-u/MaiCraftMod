# SPDX-License-Identifier: GPL-3.0-only
"""GameTest 空结构模板生成器。

Minecraft 1.21.1 的资源包结构模板只认二进制 .nbt（不认文本 .snbt），文本编辑器打开是乱码。
项目约定按尺寸共用模板：测试类上标 @PrefixGameTestTemplate(false)，方法上写
@GameTest(template = "empty9x7x9")，全项目只按尺寸各留一份模板文件，场景在测试代码里
程序化搭建。本脚本按名字生成这样的共享模板。

用法（在仓库任意位置执行均可，输出路径相对仓库根）：
  python tools/gen_gametest_template.py empty9x7x9               # 共享模板，默认 9x7x9
  python tools/gen_gametest_template.py empty16x12x16 --size 16,12,16

纯 Python 标准库，无第三方依赖。输出内容确定性（gzip 不带时间戳），重复生成不产生无意义 diff。
升 Minecraft 版本时：DataVersion 常量要跟着版本的数字数据版本改（1.21.1 是 3955）。
"""

import argparse
import gzip
import os
import struct

TAG_END = 0
TAG_INT = 3
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10

DATA_VERSION_1_21_1 = 3955

OUT_DIR = os.path.join("common", "src", "gametest", "resources", "data", "maicraft", "structure")


def named(tag_type, name):
    encoded = name.encode("utf-8")
    return bytes([tag_type]) + struct.pack(">H", len(encoded)) + encoded


def tag_int(value):
    return struct.pack(">i", value)


def tag_string(value):
    encoded = value.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def tag_list(item_type, items):
    return bytes([item_type]) + struct.pack(">i", len(items)) + b"".join(items)


def empty_template(size, data_version):
    """全空气的结构模板：框架用它划出测试区并清空，测试里再程序化搭场景。

    NBT 格式要点：List 里每一项不带类型前缀字节（类型由 List 头统一声明），
    所以 palette 的项直接以字段开头，这是最容易写错的一处。
    """
    payload = b""
    payload += named(TAG_INT, "DataVersion") + tag_int(data_version)
    payload += named(TAG_LIST, "size") + tag_list(TAG_INT, [tag_int(n) for n in size])
    palette_entry = named(TAG_STRING, "Name") + tag_string("minecraft:air") + bytes([TAG_END])
    payload += named(TAG_LIST, "palette") + tag_list(TAG_COMPOUND, [palette_entry])
    payload += named(TAG_LIST, "entities") + tag_list(TAG_END, [])
    payload += named(TAG_LIST, "blocks") + tag_list(TAG_END, [])
    root = bytes([TAG_COMPOUND]) + struct.pack(">H", 0) + payload + bytes([TAG_END])
    return gzip.compress(root, mtime=0)


def main():
    parser = argparse.ArgumentParser(description="生成 GameTest 的空结构模板 .nbt")
    parser.add_argument("name", help='模板名，如 empty9x7x9（对应 @GameTest(template = "empty9x7x9")）')
    parser.add_argument("--size", default="9,7,9", help="测试区尺寸 x,y,z（默认 9,7,9）")
    args = parser.parse_args()

    size = tuple(int(n) for n in args.size.split(","))
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_dir = os.path.join(repo_root, OUT_DIR)
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, f"{args.name.lower()}.nbt")
    with open(path, "wb") as f:
        f.write(empty_template(size, DATA_VERSION_1_21_1))
    print("已生成", os.path.relpath(path, repo_root))


if __name__ == "__main__":
    main()
