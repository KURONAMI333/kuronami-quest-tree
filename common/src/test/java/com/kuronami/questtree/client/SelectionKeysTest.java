package com.kuronami.questtree.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SelectionKeysTest {

    @Test
    void onlyTheChaptersOwnKeysAreTaken() {
        List<String> ids = SelectionKeys.idsIn("basics", List.of(
                "basics/a", "basics2/b", "basics", "other/c", "basics/d/e"));

        assertEquals(List.of("a", "d/e"), ids);
    }
}
