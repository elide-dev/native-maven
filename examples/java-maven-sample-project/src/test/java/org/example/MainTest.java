package org.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MainTest {

    @Test
    void testStringFormat() {
        String result = String.format("Hello and welcome!");
        assertEquals("Hello and welcome!", result);
    }
}
