package com.lssoluciones.lscore.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ManualIdentityInputTest {
    @Test
    void acceptsRequiredFieldsWithoutEmail() {
        var input = new ManualIdentityInput("Example", "Example Person", null);

        assertEquals("Example", input.username());
        assertEquals("Example Person", input.displayName());
        assertNull(input.email());
        assertFalse(input.emailVerified());
    }

    @Test
    void preservesOriginalValuesWithoutDerivingDatabaseKeys() {
        var input = new ManualIdentityInput("  Example.İ  ", "  María Example  ", "  User@Example.test  ");

        assertEquals("  Example.İ  ", input.username());
        assertEquals("  María Example  ", input.displayName());
        assertEquals("  User@Example.test  ", input.email());
        assertFalse(input.emailVerified());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t", "\n", " \t\r\n ", "\u2003"})
    void rejectsMissingUsernameWithSafeError(String username) {
        assertSafeRejection(() -> new ManualIdentityInput(username, "Private Person", "private@example.test"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t", "\n", " \t\r\n ", "\u2003"})
    void rejectsMissingDisplayNameWithSafeError(String displayName) {
        assertSafeRejection(() -> new ManualIdentityInput("private-user", displayName, "private@example.test"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t", "\n", " \t\r\n ", "\u2003"})
    void rejectsSuppliedBlankEmailWithSafeError(String email) {
        assertSafeRejection(() -> new ManualIdentityInput("private-user", "Private Person", email));
    }

    @ParameterizedTest
    @ValueSource(strings = {"user@example.test", "USER@example.test", "unclassified-contact"})
    void suppliedEmailIsUnverifiedWithoutIntroducingSyntaxPolicy(String email) {
        var input = new ManualIdentityInput("Example", "Example Person", email);

        assertEquals(email, input.email());
        assertFalse(input.emailVerified());
    }

    @Test
    void doesNotCheckIdentityAvailability() {
        var first = new ManualIdentityInput("Example", "First Person", "same@example.test");
        var second = new ManualIdentityInput(" example ", "Second Person", "same@example.test");

        assertEquals("Example", first.username());
        assertEquals(" example ", second.username());
        assertFalse(first.emailVerified());
        assertFalse(second.emailVerified());
    }

    @Test
    void diagnosticsDoNotExposeIdentityValues() {
        var input = new ManualIdentityInput("private-user", "Private Person", "private@example.test");

        assertEquals("ManualIdentityInput[redacted]", input.toString());
    }

    private static void assertSafeRejection(org.junit.jupiter.api.function.Executable creation) {
        var failure = assertThrows(IllegalArgumentException.class, creation);
        assertEquals("Invalid manual identity input", failure.getMessage());
        assertNull(failure.getCause());
    }
}
