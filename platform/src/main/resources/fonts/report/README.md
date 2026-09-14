# Report PDF fonts

Source: https://github.com/notofonts/noto-cjk
Pinned commit: `f8d157532fbfaeda587e826d4cd5b21a49186f7c`
Source file: `Sans/Variable/TTF/Subset/NotoSansSC-VF.ttf`
License: SIL Open Font License 1.1 (see OFL.txt).

Static TrueType instances generated with fontTools 4.59.2 at wght=400 and wght=700.
No character subsetting is applied to these resources. PDFBox embeds only used glyphs.
The packaged fonts provide the upstream Simplified Chinese coverage; unsupported glyphs fail export rather than silently replacing data.

Generation:

```python
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
for weight, name in [(400, "Regular"), (700, "Bold")]:
    font = TTFont("NotoSansSC-VF.ttf")
    instantiateVariableFont(font, {"wght": weight}, inplace=True)
    font.save(f"NotoSansSC-{name}.ttf")
```

SHA-256:

- `aurabot-NotoSansSC-VF.ttf`: `d68bafcb48a2707749396aa12bbbd833cb70401f3a9a689fd2902c7e0d295964`
- `NotoSansSC-Bold.ttf`: `71204c4b0b32fd72ad5a2df7a0a620d528692ce189c300cb4a27a3eff13de2ec`
- `NotoSansSC-Regular.ttf`: `4ac6c010139e56ee76d3e98cb59648bebe5886c092072b53734cae4bd4df60b5`
