package com.banking.account.util;

import org.springframework.stereotype.Component;
import java.util.Random;

@Component
public class IbanGenerator {

    private final Random random = new Random();

    public String generate() {
        StringBuilder iban = new StringBuilder("TR");
        for (int i = 0; i < 24; i++) {
            iban.append(random.nextInt(10));
        }
        return iban.toString();
    }
}