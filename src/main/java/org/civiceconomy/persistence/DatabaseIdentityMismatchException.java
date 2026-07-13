package org.civiceconomy.persistence;

public final class DatabaseIdentityMismatchException extends IllegalStateException {
    public DatabaseIdentityMismatchException(DatabaseIdentity expected, DatabaseIdentity actual) {
        super("Civic database identity mismatch: expected " + expected + " but found " + actual);
    }
}
