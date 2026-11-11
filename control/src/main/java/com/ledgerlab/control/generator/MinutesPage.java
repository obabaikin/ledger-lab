package com.ledgerlab.control.generator;

import java.util.List;
import java.util.Objects;

public record MinutesPage(List<MinuteResponses> minutes) {

    public MinutesPage {
        minutes = List.copyOf(Objects.requireNonNull(minutes, "minutes"));
    }
}
