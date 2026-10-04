# 光合待办 · 品牌设计

名称以「光合」表达陪伴与成长，以「待办」强调从通知中识别需要行动的事项。副标题：**把消息，变成下一步。**

图标以两片折叠的暖白叶片构成向上的完成勾，金色光点呼应「光合」。翡翠绿背景采用克制的渐变，叶片保留轻微立体折面；圆形与圆角遮罩共用主体，通知栏与系统主题使用白色简化轮廓。

图形使用 AI 图像生成工具生成；生成提示词保留于下方，便于理解设计意图。Android 适配使用独立的背景、透明前景和 monochrome 资源；前景在 108 dp 画布中留出安全边距。未将文字绘入图标，以保证小尺寸识别。

资源：[应用内图形](../app/src/main/assets/guanghe-mark.png)、[Android 图形](../app/src/main/res/drawable-nodpi/guanghe_mark.png) 和 [启动图标配置](../app/src/main/res/mipmap-anydpi/ic_launcher.xml)。本目录只保留设计说明，实际资源随应用源码维护。

适配依据：[Android Adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。

## 最终生成提示词

```text
Use case: logo-brand
Asset type: production Android adaptive launcher icon foreground, transparent square PNG.
Primary request: Design a refined original symbol for a Chinese homeroom-teacher productivity app in the "光合" (photosynthesis / bringing light together) product family. Integrate a soft upward checkmark with two sculpted leaf-like folded forms, suggesting an open notebook and growth. One small warm gold sun disc above the left of the rising shape. The whole symbol is one coherent compact silhouette.
Style/medium: precise premium industrial icon design, subtly three-dimensional, satin ivory ceramic / softly folded paper, restrained soft mint reflected light, beautifully clean continuous curves, extremely subtle bevels and self-shadow, almost flat frontal view. Calm, practical, mature, not a toy.
Composition/framing: Square canvas, truly transparent background. Entire symbol including sun contained within the central 56% of the canvas width and height. Center the optical mass. Plenty of transparent padding on all four sides for adaptive launcher masks. The main long leaf/check rises toward upper right; shorter left fold joins the same rounded lower point. A quiet inner crease adds construction without thin decorative lines.
Color palette: warm ivory #F6F5E9, pale sage shadows #BED7C5, single restrained gold #E6C57B sun accent. Designed to be placed on a dark emerald green background.
Constraints: no text, no letters, no frame, no app tile, no background, no detached drop shadow, no transparent checkerboard drawn into the art. Legible as a bold silhouette at 48 pixels. Original symbol; do not use any existing brand logo. No stars, no sparkles, no bell, no tiny checklist lines, no realistic plant stem or leaf veins. No glass refraction or neon. Generate a single mark, not a presentation sheet.
```
