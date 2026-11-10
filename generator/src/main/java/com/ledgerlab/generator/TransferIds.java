package com.ledgerlab.generator;

import java.util.List;
import java.util.UUID;

public record TransferIds(long count, List<UUID> transferIds) {
}
