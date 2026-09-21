package com.rescrm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application entry point.
 *
 * <p>Modular monolith: one deployable, with domain modules separated by package and enforced
 * by architecture tests rather than by deployment boundaries (doc 21, decision A1).
 *
 * <p>Epic 0 contains the technical foundation only — no business domain is present. Modules
 * for identity, CRM, inventory, deals, payment plans, collections and commissions arrive in
 * later epics, each as a package under {@code com.rescrm}.
 */
@SpringBootApplication
public class CrmApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrmApplication.class, args);
    }
}
