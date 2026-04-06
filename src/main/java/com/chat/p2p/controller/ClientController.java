package com.chat.p2p.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
public class ClientController {

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String index() throws IOException {
        ClassPathResource resource = new ClassPathResource("static/client.html");
        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }
}
