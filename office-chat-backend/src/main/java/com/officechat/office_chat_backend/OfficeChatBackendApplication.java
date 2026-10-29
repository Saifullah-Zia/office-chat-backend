package com.officechat.office_chat_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OfficeChatBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(OfficeChatBackendApplication.class, args);
	}

}
