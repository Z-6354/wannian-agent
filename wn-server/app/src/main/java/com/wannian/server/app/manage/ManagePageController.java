package com.wannian.server.app.manage;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 只把 {@code /manage/} 转到静态页，不写业务。 */
@Controller
public class ManagePageController {

    @GetMapping({"/manage", "/manage/"})
    public String page() {
        return "forward:/manage/index.html";
    }
}
