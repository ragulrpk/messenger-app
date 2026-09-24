package com.ctd.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the Spring Boot backend and its configured web and data services. */
@SpringBootApplication
public class DemoApplication {

    /** Launches the application from the command line. */
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

}
