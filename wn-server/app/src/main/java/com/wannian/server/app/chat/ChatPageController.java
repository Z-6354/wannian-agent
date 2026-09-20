package com.wannian.server.app.chat;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 只把 {@code /chat/} 转到静态页，不建会话，不接收回合。 */
@Controller
public class ChatPageController {

    @GetMapping({"/chat", "/chat/"})
    public String page() {
        return "forward:/chat/index.html";
    }
}
