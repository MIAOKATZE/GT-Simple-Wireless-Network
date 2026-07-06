package com.miaokatze.gtswn.common.machine.widgets;

import java.util.function.IntSupplier;

import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

/**
 * 支持科学计数法显示的文本输入框
 * <p>
 * 在 displayMode == 1（科学计数法）时，失焦后会将输入框内容格式化为科学计数法（如 1.5E6）。
 * 用户再次编辑时可：
 * <ul>
 * <li>直接在科学计数法字符串上修改（EvalEx 表达式库支持 1E6 / 1.5E5 等格式解析）</li>
 * <li>清空后输入常规数值（如 1500000），失焦后同样会被格式化为科学计数法</li>
 * </ul>
 * 实际数值仍以 long 形式存储于 LongSyncValue，科学计数法仅用于显示。
 */
public class ScientificTextFieldWidget extends TextFieldWidget {

    /** 显示模式供给器：0=常规计数，1=科学计数（与机器 displayMode 字段同步） */
    private IntSupplier displayModeSupplier = () -> 0;

    /**
     * 设置显示模式供给器
     *
     * @param supplier 返回当前显示模式（0=常规，1=科学计数）
     * @return this（链式调用）
     */
    public ScientificTextFieldWidget displayMode(IntSupplier supplier) {
        this.displayModeSupplier = supplier;
        return this;
    }

    /**
     * 失焦回调：父类先解析文本并同步到 LongSyncValue（ stringValue 为纯整数字符串），
     * 然后若处于科学计数法模式，将显示文本重新格式化为科学计数法。
     * <p>
     * 注意：stringValue 已由父类设置为 "1500000" 形式，此处只改显示文本，不影响实际值。
     */
    @Override
    public void onRemoveFocus(ModularGuiContext context) {
        // 父类行为：应用 validator 解析文本（EvalEx 支持 1E6 / 1.5E5 / 1500000 等格式），
        // 将 stringValue 设置为纯整数字符串（如 "1500000"），由 LongSyncValue 同步
        super.onRemoveFocus(context);

        // 失焦后，若处于科学计数法模式，将显示文本重新格式化为科学计数法
        if (displayModeSupplier.getAsInt() == 1) {
            reformatDisplayToScientific();
        }
    }

    /**
     * 每帧更新：父类会在失焦时将 stringValue（"1500000"）同步到显示文本，
     * 此处覆盖该行为——若处于科学计数法模式，将显示文本重格式化为科学计数法。
     * <p>
     * 同一帧内：父类 setText("1500000") → 此处 setText("1.5E6")，
     * 最终渲染结果为 "1.5E6"，无视觉闪烁。
     */
    @Override
    public void onUpdate() {
        // 父类行为：失焦时将 stringValue 同步到显示文本
        super.onUpdate();

        // 失焦状态下，若处于科学计数法模式，将显示文本重格式化为科学计数法
        // 覆盖父类 onUpdate 设置的纯整数字符串
        if (!isFocused() && displayModeSupplier.getAsInt() == 1) {
            reformatDisplayToScientific();
        }
    }

    /**
     * 将当前显示文本（纯整数字符串）重新格式化为科学计数法
     * <p>
     * 解析失败时保持原文本不动（避免破坏父类已设置的值）。
     */
    private void reformatDisplayToScientific() {
        String plainText = getText();
        if (plainText == null || plainText.isEmpty()) return;
        try {
            long value = Long.parseLong(plainText);
            String scientific = formatScientificLong(value);
            if (!plainText.equals(scientific)) {
                setText(scientific);
            }
        } catch (NumberFormatException ignored) {
            // 解析失败时保持原文本（可能正在过渡或为空）
        }
    }

    /**
     * 将 long 值格式化为科学计数法字符串
     * <p>
     * 规则：
     * <ul>
     * <li>0 → "0"</li>
     * <li>|value| &lt; 10000 → 原值（避免小数值过度格式化，如 100 → 1E2）</li>
     * <li>其他 → "mantissaEexp"（如 1500000 → "1.5E6"，-1500000 → "-1.5E6"）</li>
     * </ul>
     * 使用大写 E，与 Java/EvalEx 科学计数法字面量一致，便于用户编辑后再次解析。
     *
     * @param value 待格式化的 long 值
     * @return 科学计数法字符串
     */
    private static String formatScientificLong(long value) {
        if (value == 0) return "0";
        long abs = Math.abs(value);
        // 小数值（绝对值 < 10000）保持原样，避免 100 → 1E2 之类的过度格式化
        if (abs < 10000L) return Long.toString(value);

        double d = (double) value;
        int exp = (int) Math.floor(Math.log10(Math.abs(d)));
        double mantissa = d / Math.pow(10, exp);
        // 保留 3 位小数
        String mantissaStr = String.format("%.3f", mantissa);
        // 去除尾随 0 和多余小数点：1.500 → 1.5, 1.000 → 1
        if (mantissaStr.contains(".")) {
            mantissaStr = mantissaStr.replaceAll("0+$", "")
                .replaceAll("\\.$", "");
        }
        return mantissaStr + "E" + exp;
    }
}
