package com.polypulse.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaForwardingController {

    @GetMapping(value = {"/market/**", "/correlations"})
    public String forward() {
        return "forward:/index.html";
    }
}
