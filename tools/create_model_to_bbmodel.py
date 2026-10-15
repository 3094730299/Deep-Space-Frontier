# -*- coding: utf-8 -*-
"""
把机械动力（Create）的方块模型 JSON 转成 **Blockbench 工程（.bbmodel）**，
这样可以直接用 Blockbench 打开查看/编辑（贴图也打包进去）。

    python tools/create_model_to_bbmodel.py

输入：桌面 机械动力渲染参考\冲压机建模\ 里的 models/block/mechanical_press/*.json + 贴图
输出：同目录下的 *.bbmodel
"""
import base64
import io
import json
import os
import uuid

SRC = r"C:\Users\30947\Desktop\机械动力渲染参考\冲压机建模"
FACES = ("north", "south", "east", "west", "up", "down")


def new_uuid():
    return str(uuid.uuid4())


def convert(model_json_path, texture_names, out_path):
    model = json.load(io.open(model_json_path, encoding="utf-8"))
    elements_in = model.get("elements") or []
    if not elements_in:
        print("  跳过（没有 elements）：%s" % os.path.basename(model_json_path))
        return

    # 贴图：把用到的 png 嵌进去
    textures = []
    for index, name in enumerate(texture_names):
        png = os.path.join(SRC, "textures", "block", name)
        if not os.path.exists(png):
            continue
        raw = open(png, "rb").read()
        textures.append({
            "path": "",
            "name": name.replace(".png", ""),
            "folder": "block",
            "namespace": "create",
            "id": str(index),
            "particle": index == 0,
            "render_mode": "default",
            "visible": True,
            "mode": "bitmap",
            "source": "data:image/png;base64," + base64.b64encode(raw).decode("ascii"),
        })
    if not textures:
        textures.append({"path": "", "name": "texture", "folder": "", "namespace": "",
                         "id": "0", "particle": True, "render_mode": "default",
                         "visible": True, "mode": "bitmap", "source": ""})

    elements = []
    element_uuids = []
    for element in elements_in:
        frm = element["from"]
        to = element["to"]
        faces = {}
        for face, data in (element.get("faces") or {}).items():
            if face not in FACES:
                continue
            entry = {"uv": data.get("uv", [0, 0, 1, 1]), "texture": 0}
            if data.get("rotation"):
                entry["rotation"] = int(data["rotation"])
            if data.get("tintindex") is not None:
                entry["tint"] = int(data["tintindex"])
            faces[face] = entry
        out = {
            "name": "cube",
            "box_uv": False,
            "rescale": False,
            "locked": False,
            "render_order": "default",
            "allow_mirror_modeling": True,
            "from": frm,
            "to": to,
            "autouv": 0,
            "color": 0,
            "origin": frm,
            "faces": faces,
            "type": "cube",
            "uuid": new_uuid(),
        }
        rot = element.get("rotation")
        if rot:
            out["rotation"] = [rot.get("x", 0), rot.get("y", 0), rot.get("z", 0)]
            out["origin"] = rot.get("origin", frm)
        elements.append(out)
        element_uuids.append(out["uuid"])

    # outliner：一个根骨骼包含所有元素
    root_uuid = new_uuid()
    outliner = [{
        "name": os.path.basename(out_path).replace(".bbmodel", ""),
        "origin": [0, 0, 0],
        "uuid": root_uuid,
        "export": True,
        "mirror_uv": False,
        "isOpen": True,
        "children": element_uuids,
    }]

    bbmodel = {
        "meta": {
            "format_version": "4.10",
            "model_format": "java_block",
            "box_uv": False,
        },
        "name": os.path.basename(out_path).replace(".bbmodel", ""),
        "parent": "",
        "ambientocclusion": model.get("ambientocclusion", True),
        "front_gui_light": False,
        "visible_box": [1, 1, 0],
        "variable_placeholders": "",
        "variable_placeholder_buttons": [],
        "resolution": {"width": 16, "height": 16},
        "elements": elements,
        "outliner": outliner,
        "textures": textures,
        "display": model.get("display", {}),
    }
    io.open(out_path, "w", encoding="utf-8", newline="").write(
        json.dumps(bbmodel, ensure_ascii=False, indent=2))
    print("  已生成 %s（元素 %d，贴图 %d）" % (os.path.basename(out_path), len(elements), len(textures)))


def main():
    jobs = [
        ("models/block/mechanical_press/block.json",
         ["mechanical_press_top.png", "mechanical_press_side.png",
          "mechanical_press_pole.png", "mechanical_press_bottom.png"],
         "压床_机身.bbmodel"),
        ("models/block/mechanical_press/head.json",
         ["mechanical_press_head.png"],
         "压床_压头.bbmodel"),
        ("models/block/mechanical_press/item.json",
         ["mechanical_press_top.png", "mechanical_press_side.png",
          "mechanical_press_pole.png", "mechanical_press_head.png",
          "mechanical_press_bottom.png"],
         "压床_完整.bbmodel"),
    ]
    for rel, textures, out_name in jobs:
        path = os.path.join(SRC, rel.replace("/", os.sep))
        if not os.path.exists(path):
            print("  找不到 %s" % rel)
            continue
        convert(path, textures, os.path.join(SRC, out_name))


if __name__ == "__main__":
    main()
