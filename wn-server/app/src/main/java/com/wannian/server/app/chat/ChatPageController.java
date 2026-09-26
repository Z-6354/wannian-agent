package com.wannian.server.app.chat;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 工作台单页：根路径 {@code /} 与兼容路径 {@code /chat/} 都转到同一壳。
 * 导航用 hash（{@code /#chat}、{@code /#vendors}…），不再把视图名写进路径中间段。
 */
@Controller
public class ChatPageController {

    @GetMapping({"/"})
    public String root() {
        return "forward:/chat/index.html";
    }

    /** 旧书签 /chat/ 仍可用；页面侧会把地址收成 /#… */
    @GetMapping({"/chat", "/chat/"})
    public String legacyChatPath() {
        return "forward:/chat/index.html";
    }
}
