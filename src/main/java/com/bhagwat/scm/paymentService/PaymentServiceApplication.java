package com.bhagwat.scm.paymentService;

import com.bhagwat.scm.observability.annotation.EnableObservability;
import com.bhagwat.scm.kafka.annotation.EnableKafkaMessaging;
import com.bhagwat.scm.core.rest.annotation.EnableRestClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableObservability
@EnableKafkaMessaging
@EnableRestClient
public class PaymentServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(PaymentServiceApplication.class, args);
	}

}
