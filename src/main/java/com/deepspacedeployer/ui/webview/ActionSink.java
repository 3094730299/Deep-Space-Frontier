package com.deepspacedeployer.ui.webview;

import com.deepspacedeployer.ui.theme.HtmlNode;

/**
 * <b>动作出口</b>：主题里 {@code data-action="xxx"} 被点击时，界面把动作交给它。
 *
 * <p>为什么要这么一层：主题是<b>数据</b>，点击后要干什么属于<b>游戏逻辑</b>。
 * 界面（{@link WebViewScreen}）只管"命中哪个元素、动作名是什么"，
 * 具体做什么叫由每个界面自己实现 —— 这样同一个动作名在不同界面上可以做不同的事。</p>
 *
 * <p>动作名的约定（写主题的人只认这个）：</p>
 * <ul>
 *   <li>{@code buy:5} —— 带参数的动作，冒号后面是参数（下标 / 公司代号 / 主题名）；</li>
 *   <li>{@code assemble} —— 不带参数；</li>
 *   <li>动作名里<b>不要</b>写空格和中文（中文能跑，但日志里容易看混）。</li>
 * </ul>
 *
 * <p>返回 {@code true} = 处理了（界面会重算数据）；{@code false} = 不认识这个动作（记一条日志）。</p>
 */
@FunctionalInterface
public interface ActionSink {

    boolean onAction(String action, HtmlNode source);

    /** 什么都不做的出口（离线预览/自检用） */
    ActionSink NONE = (action, source) -> false;
}
