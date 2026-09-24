package com.melas.audit_api;

import org.springframework.boot.SpringApplication;

public class TestAuditApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(AuditApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
