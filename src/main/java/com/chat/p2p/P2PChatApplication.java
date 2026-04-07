package com.chat.p2p;

import com.chat.p2p.service.P2PNetworkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

// start
@SpringBootApplication
@EnableScheduling
public class P2PChatApplication {
    public static void main(String[] args) {
        SpringApplication.run(P2PChatApplication.class, args);
    }

    @Bean
    public CommandLineRunner openBrowser(@Autowired P2PNetworkService networkService) {
        return args -> {
            String url = "http://localhost:8089/";
            String os = System.getProperty("os.name").toLowerCase();
            
            try {
                if (os.contains("win")) {
                    Runtime.getRuntime().exec("rundll32 url.dll,FileProtocolHandler " + url);
                } else if (os.contains("mac")) {
                    Runtime.getRuntime().exec("open " + url);
                } else if (os.contains("linux")) {
                    Runtime.getRuntime().exec("xdg-open " + url);
                }
            } catch (Exception e) {
                System.out.println("Open browser manually: " + url);
            }
        };
    }
}
