package org.civiceconomy.compat;

import java.util.Optional;

public interface ModCatalog {
    Optional<String> version(String modId);

    boolean classPresent(String className);
}
