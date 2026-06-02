package ch.creektiger.aerobatics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppTest {
    @Test
    void greetingReturnsExpectedMessage() {
        assertEquals("Hello Aerobatics", App.greeting());
    }
}
