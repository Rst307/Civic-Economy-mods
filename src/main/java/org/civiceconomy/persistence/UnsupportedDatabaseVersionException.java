package org.civiceconomy.persistence;

public final class UnsupportedDatabaseVersionException extends IllegalStateException {
    public UnsupportedDatabaseVersionException(int actualVersion, int supportedVersion) {
        super("Unsupported Civic database version " + actualVersion + "; supported version is " + supportedVersion);
    }
}
