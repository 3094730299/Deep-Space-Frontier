package com.deepspacedeployer.ui.webview;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>多行文本缓冲</b>（给 UI 编辑器的文本编辑用）。
 *
 * <p>原版 {@code EditBox} 是单行的，编辑 {@code index.json} / {@code .css} 需要多行，
 * 所以这里实现一个最小可用的行式编辑器：</p>
 * <ul>
 *   <li>按键：字符输入、回车换行、退格、删除、左右上下、Home/End；</li>
 *   <li>光标是 {@code (行, 列)}；上下移动时列超界自动夹到行尾；</li>
 *   <li>{@link #toText()} 用 {@code \n} 拼回字符串（保存用）。</li>
 * </ul>
 *
 * <p>刻意不做：选中、剪切粘贴、撤销栈。第 1 版够用即可，后面要加再说。</p>
 */
public final class ThemeTextBuffer {

    private final List<StringBuilder> lines = new ArrayList<>();
    private int caretLine;
    private int caretCol;
    /** 视口第一行（由界面维护，用于滚动） */
    private int scrollLine;

    public ThemeTextBuffer() {
        lines.add(new StringBuilder());
    }

    // ---------------------------------------------------------------- 读写

    public void setText(String text) {
        lines.clear();
        if (text == null) {
            lines.add(new StringBuilder());
        } else {
            String[] parts = text.split("\n", -1);
            for (String p : parts) {
                lines.add(new StringBuilder(p.endsWith("\r")
                        ? p.substring(0, p.length() - 1) : p));
            }
        }
        caretLine = 0;
        caretCol = 0;
        scrollLine = 0;
    }

    /** 保存用：按 {@code \n} 拼回去 */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    public int lineCount() {
        return lines.size();
    }

    public String line(int i) {
        return i >= 0 && i < lines.size() ? lines.get(i).toString() : "";
    }

    public int caretLine() {
        return caretLine;
    }

    public int caretCol() {
        return caretCol;
    }

    public int scrollLine() {
        return scrollLine;
    }

    public void setScrollLine(int v) {
        scrollLine = Math.max(0, Math.min(v, Math.max(0, lines.size() - 1)));
    }

    // ---------------------------------------------------------------- 编辑

    public void insert(char c) {
        if (c == '\n' || c == '\r') {
            newline();
            return;
        }
        if (c < ' ' && c != '\t') {
            return;                       // 其它控制字符忽略
        }
        StringBuilder line = lines.get(caretLine);
        caretCol = Math.min(caretCol, line.length());
        line.insert(caretCol, c);
        caretCol++;
    }

    public void newline() {
        StringBuilder line = lines.get(caretLine);
        caretCol = Math.min(caretCol, line.length());
        String tail = line.substring(caretCol);
        line.delete(caretCol, line.length());
        lines.add(caretLine + 1, new StringBuilder(tail));
        caretLine++;
        caretCol = 0;
    }

    /** 退格：删光标前一个字符；行首则和上一行合并 */
    public void backspace() {
        if (caretCol > 0) {
            StringBuilder line = lines.get(caretLine);
            if (caretCol <= line.length()) {
                line.deleteCharAt(caretCol - 1);
            }
            caretCol--;
            return;
        }
        if (caretLine > 0) {
            StringBuilder prev = lines.get(caretLine - 1);
            int prevLen = prev.length();
            prev.append(lines.get(caretLine));
            lines.remove(caretLine);
            caretLine--;
            caretCol = prevLen;
        }
    }

    /** 删除：删光标后一个字符；行尾则把下一行并上来 */
    public void delete() {
        StringBuilder line = lines.get(caretLine);
        if (caretCol < line.length()) {
            line.deleteCharAt(caretCol);
            return;
        }
        if (caretLine + 1 < lines.size()) {
            line.append(lines.get(caretLine + 1));
            lines.remove(caretLine + 1);
        }
    }

    public void moveLeft() {
        if (caretCol > 0) {
            caretCol--;
        } else if (caretLine > 0) {
            caretLine--;
            caretCol = lines.get(caretLine).length();
        }
    }

    public void moveRight() {
        StringBuilder line = lines.get(caretLine);
        if (caretCol < line.length()) {
            caretCol++;
        } else if (caretLine + 1 < lines.size()) {
            caretLine++;
            caretCol = 0;
        }
    }

    public void moveUp() {
        if (caretLine > 0) {
            caretLine--;
            caretCol = Math.min(caretCol, lines.get(caretLine).length());
        }
    }

    public void moveDown() {
        if (caretLine + 1 < lines.size()) {
            caretLine++;
            caretCol = Math.min(caretCol, lines.get(caretLine).length());
        }
    }

    public void home() {
        caretCol = 0;
    }

    public void end() {
        caretCol = lines.get(caretLine).length();
    }

    /** 让光标留在视口里（界面每次移动光标后调一次） */
    public void ensureVisible(int visibleLines) {
        if (visibleLines <= 0) {
            return;
        }
        if (caretLine < scrollLine) {
            scrollLine = caretLine;
        } else if (caretLine >= scrollLine + visibleLines) {
            scrollLine = caretLine - visibleLines + 1;
        }
        scrollLine = Math.max(0, Math.min(scrollLine, Math.max(0, lines.size() - 1)));
    }
}
