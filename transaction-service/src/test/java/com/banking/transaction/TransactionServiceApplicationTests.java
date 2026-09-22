package com.banking.transaction;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;

@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"transaction-events"})
class TransactionServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}